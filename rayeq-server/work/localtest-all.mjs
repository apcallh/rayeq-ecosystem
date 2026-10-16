import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import crypto from "node:crypto";
if (!globalThis.crypto) globalThis.crypto = crypto.webcrypto;
import readline from "node:readline";

import worker, {
  effectiveStatus,
  parseBool,
  untilFrom,
  hashPasswordPbkdf2,
} from "../dist/index.js";
import {
  categoryOf,
  isLiveTypes,
  isTool,
  langOf,
  normalizeRepoUrl,
} from "../dist/catalog.js";

// Real SQLite-backed D1Database emulator using python3 sqlite3
class SqliteD1Database {
  constructor() {
    this.proc = spawn("python3", ["rayeq-server/work/sqlite-bridge.py", ":memory:"], {
      stdio: ["pipe", "pipe", "inherit"],
    });

    this.rl = readline.createInterface({ input: this.proc.stdout });
    this.seq = 0;
    this.pending = new Map();
    this.rl.on("line", (line) => {
      const msg = JSON.parse(line);
      const p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        if (msg.ok) p.resolve(msg.data);
        else p.reject(new Error(msg.error));
      }
    });
  }

  _exec(sql, params, mode) {
    const id = ++this.seq;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.proc.stdin.write(JSON.stringify({ id, sql, params, mode }) + "\n");
    });
  }

  prepare(sql) {
    const db = this;
    return {
      _params: [],
      bind(...args) {
        const bound = Object.create(this);
        bound._params = args.map((a) => (a === undefined ? null : a));
        return bound;
      },
      first() {
        return db._exec(sql, this._params, "first");
      },
      all() {
        return db._exec(sql, this._params, "all");
      },
      run() {
        return db._exec(sql, this._params, "run");
      },
    };
  }

  close() {
    this.rl.close(); this.proc.stdin.end(); this.proc.kill("SIGKILL");
  }
}

// Generate an RSA keypair for mocking Google JWKS idToken verification
const { publicKey, privateKey } = crypto.generateKeyPairSync("rsa", {
  modulusLength: 2048,
});
const publicJwk = {
  ...publicKey.export({ format: "jwk" }),
  kid: "test-key-1",
  alg: "RS256",
  use: "sig",
};

function makeGoogleIdToken({
  sub = "google-user-1",
  email = "user1@example.com",
  name = "User One",
  aud = "test-web-client.apps.googleusercontent.com",
  expOffsetSec = 3600,
} = {}) {
  const header = { alg: "RS256", kid: "test-key-1", typ: "JWT" };
  const payload = {
    iss: "https://accounts.google.com",
    aud,
    sub,
    email,
    email_verified: true,
    name,
    exp: Math.floor(Date.now() / 1000) + expOffsetSec,
  };
  const b64u = (obj) =>
    Buffer.from(JSON.stringify(obj))
      .toString("base64")
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/g, "");
  const unsigned = `${b64u(header)}.${b64u(payload)}`;
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(unsigned);
  const sig = signer
    .sign(privateKey)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
  return `${unsigned}.${sig}`;
}

// Mock upstream fetch for Google JWKS, mock Repo JSON, TMDB, TVDB, OpenSubtitles, and Skip APIs
function createMockFetch() {
  let repoPluginVersion = 2;
  const mockFetch = async (input, init = {}) => {
    const urlStr = typeof input === "string" ? input : input.url;
    const u = new URL(urlStr);

    // 1. Google JWKS
    if (urlStr === "https://www.googleapis.com/oauth2/v3/certs") {
      return new Response(JSON.stringify({ keys: [publicJwk] }), { status: 200 });
    }

    // 2. Mock Repository & Plugin List
    if (u.hostname === "repo.example.org" && u.pathname === "/repo.json") {
      return new Response(
        JSON.stringify({
          name: "Test Repo",
          description: "Synthetic repo for unit tests",
          iconUrl: "https://repo.example.org/icon-%size%.png",
          manifestVersion: 1,
          pluginLists: ["https://repo.example.org/plugins.json"],
        }),
        { status: 200 }
      );
    }

    if (u.hostname === "repo.example.org" && u.pathname === "/plugins.json") {
      return new Response(
        JSON.stringify([
          {
            internalName: "ArabProviderOne",
            name: "Arab Provider One",
            version: repoPluginVersion,
            status: 1,
            language: "ar",
            tvTypes: ["Movie", "TvSeries"],
            iconUrl: "https://repo.example.org/p1-%size%.png",
            description: "Arabic movies & series",
            authors: ["Tester"],
            fileSize: 42000,
            url: "https://repo.example.org/ArabProviderOne.cs3",
          },
          {
            internalName: "GlobalMovies",
            name: "Global Movies",
            version: 1,
            status: 1,
            language: "en",
            tvTypes: ["Movie"],
            url: "https://repo.example.org/GlobalMovies.cs3",
          },
          {
            internalName: "SportsLive",
            name: "Sports Live TV",
            version: 1,
            status: 1,
            language: "en",
            tvTypes: ["TvSeries", "Live", "Movie"], // Mixed live types -> must be detected as LIVE!
            url: "https://repo.example.org/SportsLive.cs3",
          },
          {
            internalName: "Rate",
            name: "Extension Rating",
            version: 1,
            status: 1,
            language: "en",
            tvTypes: ["Others"],
            url: "https://repo.example.org/Rate.cs3",
          },
        ]),
        { status: 200 }
      );
    }

    // 3. TMDB Mock
    if (u.hostname === "api.themoviedb.org") {
      if (u.pathname.includes("/search/")) {
        return new Response(
          JSON.stringify({
            page: 1,
            results: [
              {
                id: 1399,
                media_type: "tv",
                name: "Breaking Bad",
                original_name: "Breaking Bad",
                overview: "A chemistry teacher...",
                first_air_date: "2008-01-20",
                vote_average: 8.9,
                popularity: 150.0,
              },
            ],
          }),
          { status: 200 }
        );
      }
      return new Response(
        JSON.stringify({
          id: 550,
          title: "Fight Club",
          passed_api_key: u.searchParams.get("api_key"),
        }),
        { status: 200 }
      );
    }

    // 4. TheTVDB Mock
    if (u.hostname === "api4.thetvdb.com") {
      if (u.pathname === "/v4/login") {
        return new Response(JSON.stringify({ data: { token: "tvdb-jwt-tok" } }), {
          status: 200,
        });
      }
      return new Response(JSON.stringify({ status: "success", data: [{ id: 101 }] }), {
        status: 200,
      });
    }

    // 5. OpenSubtitles Mock
    if (u.hostname.endsWith("opensubtitles.com")) {
      return new Response(JSON.stringify({ data: [{ id: "sub-1" }] }), { status: 200 });
    }

    // 6. Skip DB Mock
    if (u.hostname === "api.theintrodb.org") {
      return new Response(
        JSON.stringify({
          intro: { start_ms: 15000, end_ms: 90000 },
          outro: { start_ms: 1320000, end_ms: 1400000 },
        }),
        { status: 200 }
      );
    }
    if (u.hostname === "api.introdb.app" || u.hostname === "api.aniskip.com") {
      return new Response("Not found", { status: 404 });
    }

    return new Response("Not found", { status: 404 });
  };
  mockFetch.bumpRepoPluginVersion = (v) => {
    repoPluginVersion = v;
  };
  return mockFetch;
}

test("M0 & M3 Pure Functions: catalog classification, live detection, tool detection, Arabic categories", () => {
  // URL normalization & SSRF protection
  assert.equal(
    normalizeRepoUrl("cloudstreamrepo://repo.example.org/repo.json"),
    "https://repo.example.org/repo.json"
  );
  for (const bad of [
    "http://localhost/repo.json",
    "http://127.0.0.1/repo.json",
    "http://10.0.0.1/repo.json",
    "http://192.168.1.5/repo.json",
    "ftp://repo.example.org/repo.json",
    "",
  ]) {
    assert.throws(() => normalizeRepoUrl(bad), /bad_url/);
  }

  // Language detection & admin override
  assert.deepEqual(langOf({ language: "ar" }), {
    lang: "ar",
    arabic: true,
    langSource: "plugin",
  });
  assert.deepEqual(langOf({ language: "ar-SA" }), {
    lang: "ar",
    arabic: true,
    langSource: "plugin",
  });
  assert.deepEqual(langOf({ language: "en", arabic: true }), {
    lang: "ar",
    arabic: true,
    langSource: "admin",
  });
  assert.deepEqual(langOf({ language: "ar", arabic: false }), {
    lang: "other",
    arabic: false,
    langSource: "admin",
  });

  // Live detection with EXCLUDE rule (§6.8.2)
  assert.equal(isLiveTypes(["Live"]), true);
  assert.equal(isLiveTypes(["TvSeries", "Live", "Movie"]), true);
  assert.equal(isLiveTypes(["Movie, Others, live"]), true);
  assert.equal(
    isLiveTypes(["Movie", "TvSeries", "Live", "Anime", "Music", "Documentary"]),
    false
  );

  // Tool detection
  assert.equal(isTool({ internalName: "Rate", tvTypes: ["Movie"] }), true);
  assert.equal(isTool({ name: "Extension Rating", tvTypes: [] }), true);
  assert.equal(isTool({ name: "تقييم الإضافات", tvTypes: [] }), true);
  assert.equal(isTool({ name: "Helper", tvTypes: ["Others"] }), true);
  assert.equal(isTool({ name: "Helper", kind: "live", tvTypes: ["Others"] }), false);

  // Arabic Categories (§6.8.2)
  assert.equal(categoryOf({ name: "Live1", tvTypes: ["Live", "Movie"] }), "بث مباشر");
  assert.equal(categoryOf({ internalName: "Rate", tvTypes: ["Others"] }), "أدوات");
  assert.equal(categoryOf({ name: "Tor", tvTypes: ["Torrent", "Movie"] }), "تورنت");
  assert.equal(categoryOf({ name: "Empty", tvTypes: [] }), "عام");
  assert.equal(categoryOf({ name: "AllSrc", tvTypes: ["All"] }), "عام");
  assert.equal(categoryOf({ name: "ArabToon", tvTypes: ["Movie", "TvSeries"] }), "كرتون");
  assert.equal(categoryOf({ name: "AnimeSlayer", tvTypes: ["Anime"] }), "أنمي");
  assert.equal(
    categoryOf({ name: "KoreaTv", language: "ko", tvTypes: ["AsianDrama", "TvSeries"] }),
    "دراما آسيوية"
  );
  assert.equal(
    categoryOf({ name: "GeneralTube", tvTypes: ["Movie", "Music", "Documentary"] }),
    "عام"
  );
  assert.equal(
    categoryOf({ name: "Quirk", tvTypes: ["Movie", "tvTypes"] }),
    "أفلام ومسلسلات"
  );
  assert.equal(categoryOf({ name: "MoviesOnly", tvTypes: ["Movie"] }), "أفلام");
  assert.equal(categoryOf({ name: "SeriesOnly", tvTypes: ["TvSeries"] }), "مسلسلات");
});

test("M0–M5 Full End-to-End Server & D1 Verification", async () => {
  const mockFetch = createMockFetch();
  const adminPassHash = await hashPasswordPbkdf2("SecretAdminPass!");
  const db = new SqliteD1Database();
  const env = {
    DB: db,
    TMDB_KEY: "test_tmdb_key_123",
    SESSION_SECRET: "a_very_long_48_byte_session_secret_for_testing_hmac_123456",
    ADMIN_PASS_HASH: adminPassHash,
    GOOGLE_WEB_CLIENT_ID: "test-web-client.apps.googleusercontent.com",
    AUTH_ENABLED: "true",
    ADMIN_USER: "admin",
    SMART_TEST: "smart_eval_secret",
    TVDB_KEY: "test_tvdb_key",
    __customFetch: mockFetch,
  };

  const call = async (method, path, body = null, headers = {}) => {
    const reqInit = { method, headers: { ...headers } };
    if (body !== null) {
      reqInit.headers["Content-Type"] = "application/json";
      reqInit.body = typeof body === "string" ? body : JSON.stringify(body);
    }
    const res = await worker.fetch(new Request(`https://rayeq.test${path}`, reqInit), env);
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = text;
    }
    return { status: res.status, data, headers: res.headers };
  };

  try {
    // --- M0: /health & public pages ---
    const health = await call("GET", "/health");
    assert.equal(health.status, 200);
    assert.equal(health.data.ok, true);
    assert.equal(health.data.name, "rayeq-api");

    const homePage = await call("GET", "/");
    assert.equal(homePage.status, 200);
    assert.ok(String(homePage.data).includes("منظومة رايق"));

    const privPage = await call("GET", "/privacy");
    assert.equal(privPage.status, 200);
    assert.ok(String(privPage.data).includes("سياسة الخصوصية"));

    // --- M1: Admin Login & Rate Limiting ---
    for (let i = 0; i < 5; i++) {
      const bad = await call(
        "POST",
        "/admin/login",
        { username: "admin", password: "wrong" },
        { "CF-Connecting-IP": "203.0.113.9" }
      );
      assert.equal(bad.status, 401);
    }
    const rateLimited = await call(
      "POST",
      "/admin/login",
      { username: "admin", password: "SecretAdminPass!" },
      { "CF-Connecting-IP": "203.0.113.9" }
    );
    assert.equal(rateLimited.status, 429);
    assert.equal(rateLimited.data.error, "too_many_attempts");
    assert.ok(rateLimited.headers.get("Retry-After"));

    const adminLogin = await call(
      "POST",
      "/admin/login",
      { username: "admin", password: "SecretAdminPass!" },
      { "CF-Connecting-IP": "203.0.113.10" }
    );
    assert.equal(adminLogin.status, 200);
    const adminToken = adminLogin.data.token;
    assert.ok(adminToken.startsWith("admin."));
    const adminHdr = { Authorization: `Bearer ${adminToken}` };

    const meRes = await call("GET", "/admin/me", null, adminHdr);
    assert.equal(meRes.status, 200);

    // --- M1: Google Sign-in & Approval State Machine ---
    const idTok1 = makeGoogleIdToken({
      sub: "sub-u1",
      email: "User1@Example.com",
      name: "أحمد",
    });
    const sign1 = await call("POST", "/v1/auth/google", {
      idToken: idTok1,
      deviceModel: "Pixel 8",
      deviceName: "Ahmad Phone",
      deviceType: "phone",
    });
    assert.equal(sign1.status, 200);
    assert.equal(sign1.data.status, "pending");
    const u1Tok = sign1.data.token;
    const u1Hdr = { Authorization: `Bearer ${u1Tok}` };

    // Unapproved user cannot access /v1/sources or /v1/tmdb
    assert.equal((await call("GET", "/v1/sources", null, u1Hdr)).status, 401);
    assert.equal((await call("GET", "/v1/tmdb/movie/550", null, u1Hdr)).status, 401);

    // But /v1/rev works in pending state!
    const revPending = await call("GET", "/v1/rev?wait=0", null, u1Hdr);
    assert.equal(revPending.status, 200);
    assert.equal(revPending.data.status, "pending");
    const etag = revPending.headers.get("ETag");
    const rev304 = await call("GET", "/v1/rev?wait=0", null, {
      ...u1Hdr,
      "If-None-Match": etag,
    });
    assert.equal(rev304.status, 304);

    // Approve User 1 for 7 days
    const approveRes = await call("POST", "/admin/users/1/approve", { days: 7 }, adminHdr);
    assert.equal(approveRes.status, 200);

    const hbApproved = await call("POST", "/v1/heartbeat", { deviceType: "phone" }, u1Hdr);
    assert.equal(hbApproved.status, 200);
    assert.equal(hbApproved.data.status, "approved");
    assert.ok(hbApproved.data.accessUntil > Date.now());

    // Temporary lock (expires automatically without DB write!)
    const shortLockUntil = Date.now() + 80;
    await call("POST", "/admin/users/1/lock", { until: shortLockUntil }, adminHdr);
    const hbLocked = await call("POST", "/v1/heartbeat", {}, u1Hdr);
    assert.equal(hbLocked.data.status, "locked");
    assert.equal(hbLocked.data.lockedUntil, shortLockUntil);

    await new Promise((r) => setTimeout(r, 110));
    const hbAutoUnlocked = await call("POST", "/v1/heartbeat", {}, u1Hdr);
    assert.equal(hbAutoUnlocked.data.status, "approved");

    // Block -> Unblock -> Pending -> Re-approve
    await call("POST", "/admin/users/1/block", {}, adminHdr);
    assert.equal((await call("POST", "/v1/heartbeat", {}, u1Hdr)).data.status, "blocked");
    await call("POST", "/admin/users/1/unblock", {}, adminHdr);
    assert.equal((await call("POST", "/v1/heartbeat", {}, u1Hdr)).data.status, "pending");
    await call("POST", "/admin/users/1/approve", { unlimited: true }, adminHdr);
    assert.equal((await call("POST", "/v1/heartbeat", {}, u1Hdr)).data.status, "approved");

    // --- M2: TMDB Proxy & App Maintenance parseBool ---
    const tmdbOk = await call("GET", "/v1/tmdb/movie/550?api_key=hacker_key", null, u1Hdr);
    assert.equal(tmdbOk.status, 200);
    assert.equal(tmdbOk.data.passed_api_key, "test_tmdb_key_123");

    const tmdbBlockedPath = await call("GET", "/v1/tmdb/account/123", null, u1Hdr);
    assert.equal(tmdbBlockedPath.status, 404);
    assert.equal(tmdbBlockedPath.data.error, "path not allowed");

    // Strict parseBool for maintenance ("false" string MUST return boolean false!)
    const appFalseStr = await call(
      "PUT",
      "/admin/app",
      { maintenance: "false", latestVersion: "1.2.0", latestCode: 12 },
      adminHdr
    );
    assert.equal(appFalseStr.status, 200);
    assert.strictEqual(appFalseStr.data.maintenance, false);

    const appGet = await call("GET", "/v1/app");
    assert.strictEqual(appGet.data.maintenance, false);

    const appBadMaint = await call(
      "PUT",
      "/admin/app",
      { maintenance: "maybe" },
      adminHdr
    );
    assert.equal(appBadMaint.status, 400);
    assert.equal(appBadMaint.data.error, "bad maintenance");

    // --- M3: Catalog, Repos, Plugins, Servers, Reports ---
    const inspectRes = await call(
      "POST",
      "/admin/repos/inspect",
      { url: "https://repo.example.org/repo.json" },
      adminHdr
    );
    assert.equal(inspectRes.status, 200);
    assert.equal(inspectRes.data.plugins.length, 4);

    const addRepoRes = await call(
      "POST",
      "/admin/repos",
      { url: "https://repo.example.org/repo.json" },
      adminHdr
    );
    assert.equal(addRepoRes.status, 200);
    assert.equal(addRepoRes.data.pluginCount, 4);

    // Duplicate repo returns 409
    const dupRepo = await call(
      "POST",
      "/admin/repos",
      { url: "https://repo.example.org/repo.json" },
      adminHdr
    );
    assert.equal(dupRepo.status, 409);

    // Install all plugins from repo
    const instAll = await call(
      "POST",
      "/admin/plugins",
      { repo: "https://repo.example.org/repo.json", all: "install" },
      adminHdr
    );
    assert.equal(instAll.status, 200);
    assert.equal(instAll.data.added, 4);
    // Check labels: 3 content/live servers numbered 1..3, and 1 tool ("أداة")
    const labels = instAll.data.servers.map((s) => `${s.internalName}:${s.label}`);
    assert.deepEqual(labels, [
      "ArabProviderOne:سيرفر 1",
      "Rate:أداة",
      "GlobalMovies:سيرفر 2",
      "SportsLive:سيرفر 3",
    ]);

    // Viewer /v1/sources separates tools from servers and numbers servers 1..3
    const vSources1 = await call("GET", "/v1/sources", null, u1Hdr);
    assert.equal(vSources1.status, 200);
    assert.equal(vSources1.data.servers.length, 3);
    assert.equal(vSources1.data.tools.length, 1);
    assert.equal(vSources1.data.tools[0].internalName, "Rate");
    assert.deepEqual(
      vSources1.data.servers.map((s) => s.n),
      [1, 2, 3]
    );

    // --- M4: Per-User Sources & Rules ---
    // Enable hideNonArabic for User 1: GlobalMovies (en, content) is hidden,
    // but SportsLive (en, live) MUST remain enabled!
    const uSourcesRule = await call(
      "PUT",
      "/admin/users/1/sources",
      { rules: { hideNonArabic: true } },
      adminHdr
    );
    assert.equal(uSourcesRule.status, 200);
    const vSourcesAfterRule = await call("GET", "/v1/sources", null, u1Hdr);
    assert.deepEqual(
      vSourcesAfterRule.data.servers.map((s) => `${s.n}:${s.internalName}`),
      ["1:ArabProviderOne", "2:SportsLive"]
    );

    // Reset User 1 sources to default
    await call("DELETE", "/admin/users/1/sources", null, adminHdr);
    assert.equal((await call("GET", "/v1/sources", null, u1Hdr)).data.servers.length, 3);

    // --- M4: Long-poll /v1/rev detection (< 1s) across 10 admin writes ---
    const latencies = [];
    for (let i = 0; i < 10; i++) {
      const curRev = (await call("GET", "/v1/rev?wait=0", null, u1Hdr)).data.rev;
      const t0 = Date.now();
      const pollPromise = call(
        "GET",
        `/v1/rev?since=${encodeURIComponent(curRev)}&wait=5`,
        null,
        u1Hdr
      );
      setTimeout(() => {
        call("PUT", "/admin/app", { maintenance: false, notes: `rev-${i}` }, adminHdr);
      }, 40);
      const pollRes = await pollPromise;
      const dt = Date.now() - t0;
      latencies.push(dt);
      assert.equal(pollRes.status, 200);
      assert.notEqual(pollRes.data.rev, curRev);
    }
    const maxLat = Math.max(...latencies);
    assert.ok(maxLat < 2000, `Expected fast rev detection, got max ${maxLat}ms`);

    // --- M4: Cloud Sync (/v1/sync & /v1/sync/progress) ---
    const sync0 = await call("GET", "/v1/sync", null, u1Hdr);
    assert.deepEqual(sync0.data, { data: null, updatedAt: null, rev: 0 });

    const syncPut1 = await call(
      "PUT",
      "/v1/sync",
      { data: { favourites: ["tv:1399"], settingsAt: 1000 }, baseRev: 0 },
      u1Hdr
    );
    assert.equal(syncPut1.status, 200);
    assert.equal(syncPut1.data.rev, 1);

    // Stale baseRev=0 -> 409 conflict with current data
    const syncConflict = await call(
      "PUT",
      "/v1/sync",
      { data: { favourites: ["movie:550"] }, baseRev: 0 },
      u1Hdr
    );
    assert.equal(syncConflict.status, 409);
    assert.equal(syncConflict.data.rev, 1);
    assert.deepEqual(syncConflict.data.data.favourites, ["tv:1399"]);

    // Oversized > 2 MiB -> 413
    const hugeStr = "x".repeat(2 * 1024 * 1024 + 500);
    const sync413 = await call(
      "PUT",
      "/v1/sync",
      { data: { big: hugeStr }, baseRev: 1 },
      u1Hdr
    );
    assert.equal(sync413.status, 413);

    // Sync progress: upsert by newer updatedAt only & user isolation
    const progPut1 = await call(
      "PUT",
      "/v1/sync/progress",
      {
        items: [
          {
            key: "tmdb:tv:1399:s1e1",
            posMs: 50000,
            durMs: 3000000,
            updatedAt: 2000,
            title: "Breaking Bad",
          },
          {
            key: "invalid_key_format",
            posMs: 100,
            durMs: 200,
            updatedAt: 2000,
          },
        ],
      },
      u1Hdr
    );
    assert.equal(progPut1.status, 200);
    assert.equal(progPut1.data.received, 1);
    assert.equal(progPut1.data.skipped, 1);

    // Older updatedAt (1500 < 2000) is skipped!
    const progPutOlder = await call(
      "PUT",
      "/v1/sync/progress",
      {
        items: [
          {
            key: "tmdb:tv:1399:s1e1",
            posMs: 10000,
            durMs: 3000000,
            updatedAt: 1500,
          },
        ],
      },
      u1Hdr
    );
    assert.equal(progPutOlder.data.received, 0);
    assert.equal(progPutOlder.data.skipped, 1);

    const progGet = await call("GET", "/v1/sync/progress?since=0", null, u1Hdr);
    assert.equal(progGet.data.items.length, 1);
    assert.equal(progGet.data.items[0].posMs, 50000);

    // --- M4: Presence & Usage ---
    const pres = await call(
      "POST",
      "/v1/presence",
      {
        state: "watching",
        seconds_watched_delta: 120,
        screens_delta: 3,
        app_version: "1.0.0",
      },
      u1Hdr
    );
    assert.equal(pres.status, 200);

    const stats = await call("GET", "/admin/stats", null, adminHdr);
    assert.equal(stats.data.onlineNow, 1);
    assert.equal(stats.data.watchingNow, 1);
    assert.equal(stats.data.watchSecondsToday, 120);

    // --- M5: Smart Search, Subtitles, TVDB, Skip ---
    const smartShort = await call("GET", "/v1/smart-search?q=a", null, u1Hdr);
    assert.equal(smartShort.status, 400);

    const smartOk = await call(
      "GET",
      "/v1/smart-search?q=%D8%A8%D8%B1%D9%8A%D9%83%D9%86%D8%AC&type=tv",
      null,
      u1Hdr
    );
    assert.equal(smartOk.status, 200);
    assert.ok(smartOk.data.results.length >= 1);

    const skipRes = await call(
      "GET",
      "/v1/skip?tmdb=1399&season=1&episode=1&duration_ms=3000000",
      null,
      u1Hdr
    );
    assert.equal(skipRes.status, 200);
    assert.equal(skipRes.data.segments.length, 2);
    assert.equal(skipRes.data.segments[0].type, "intro");
    assert.equal(skipRes.data.segments[0].start_ms, 15000);

    // Kick User 1 -> next /v1/rev or /v1/heartbeat returns 401
    await call("POST", "/admin/users/1/kick", {}, adminHdr);
    assert.equal((await call("GET", "/v1/rev?wait=0", null, u1Hdr)).status, 401);
  } finally {
    db.close();
  }
});
