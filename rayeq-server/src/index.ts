import {
  CatalogData,
  CatalogError,
  PluginMeta,
  PluginReport,
  RepoEntry,
  ServerEntry,
  adminServerItems,
  clip,
  kindFields,
  langOf,
  normalizeRepoUrl,
  pluginStatus,
  readRepo,
} from "./catalog";
import {
  buildAdminUserSources,
  buildViewerSources,
  ensureUserSourcesSchema,
} from "./usersources";
import {
  MAX_SYNC_B64_CHARS,
  MAX_SYNC_RAW_BYTES,
  PROGRESS_KEY_RE,
  compressGzipB64,
  decompressGzipB64,
  ensureSyncSchema,
} from "./sync";
import {
  handleOpenSubtitlesProxy,
  handleSkipSegments,
  handleTvdbProxy,
} from "./v05";
import { runSmartSearch } from "./smart";

export interface Env {
  DB: D1Database;
  TMDB_KEY?: string;
  SESSION_SECRET?: string;
  ADMIN_PASS_HASH?: string;
  GOOGLE_WEB_CLIENT_ID?: string;
  AUTH_ENABLED?: string;
  ADMIN_USER?: string;
  AI?: { run: (model: string, input: unknown) => Promise<unknown> };
  SMART_TEST?: string;
  TVDB_KEY?: string;
  OS_API_KEY?: string;
  __customFetch?: typeof fetch;
}

export const OWNER_TZ_OFFSET_MS = 3 * 3600 * 1000; // UTC+3 (توقيت المالك بدون توقيت صيفي)
export const PRESENCE_ONLINE_MS = 150000;          // 2.5 دقيقة
export const DEVICE_ONLINE_MS = 300000;            // 5 دقائق

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "Authorization, Content-Type, X-Rayeq-Client, X-OS-Token, X-OS-Base, X-Rayeq-Admin-Pass, X-Smart-Test, If-None-Match",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
};

export function json(
  data: unknown,
  status = 200,
  extraHeaders: Record<string, string> = {}
): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...CORS_HEADERS,
      ...extraHeaders,
    },
  });
}

export function htmlResponse(html: string, status = 200): Response {
  return new Response(html, {
    status,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      ...CORS_HEADERS,
    },
  });
}

let baseSchemaReady = false;

export async function ensureSchema(db: D1Database): Promise<void> {
  if (!baseSchemaReady) {
    const stmts = [
      `CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        google_sub TEXT NOT NULL UNIQUE,
        email TEXT, name TEXT,
        status TEXT NOT NULL DEFAULT 'pending',
        locked INTEGER NOT NULL DEFAULT 0,
        access_until INTEGER,
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`,
      `CREATE INDEX IF NOT EXISTS users_email ON users(email)`,
      `CREATE TABLE IF NOT EXISTS devices (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        model TEXT, name TEXT, type TEXT,
        approved INTEGER NOT NULL DEFAULT 0,
        token_hash TEXT UNIQUE,
        first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)`,
      `CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id)`,
      `CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL)`,
      `INSERT OR IGNORE INTO config(key, value) VALUES ('sources', '{"repos":[],"plugins":[]}')`,
      `CREATE TABLE IF NOT EXISTS login_attempts (ip TEXT PRIMARY KEY, fails INTEGER NOT NULL DEFAULT 0, window_start INTEGER NOT NULL)`,
      `CREATE TABLE IF NOT EXISTS user_usage (
        user_id INTEGER PRIMARY KEY,
        total_watch_seconds INTEGER NOT NULL DEFAULT 0,
        browse_count INTEGER NOT NULL DEFAULT 0,
        last_seen_at INTEGER,
        current_state TEXT,
        last_app_version TEXT,
        updated_at INTEGER NOT NULL DEFAULT 0)`,
      `CREATE TABLE IF NOT EXISTS user_usage_daily (
        user_id INTEGER NOT NULL,
        day TEXT NOT NULL,
        seconds INTEGER NOT NULL DEFAULT 0,
        screens INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (user_id, day))`,
      `CREATE INDEX IF NOT EXISTS user_usage_daily_day ON user_usage_daily(day)`,
    ];
    for (const s of stmts) {
      await db.prepare(s).run();
    }
    baseSchemaReady = true;
  }
  await ensureSyncSchema(db);
  await ensureUserSourcesSchema(db);
}

export async function cfgGet<T>(db: D1Database, key: string, def: T): Promise<T> {
  const row = await db
    .prepare(`SELECT value FROM config WHERE key = ?`)
    .bind(key)
    .first<{ value: string }>();
  if (!row || row.value === undefined || row.value === null) return def;
  try {
    return JSON.parse(row.value) as T;
  } catch {
    return def;
  }
}

export async function cfgPut(db: D1Database, key: string, val: unknown): Promise<void> {
  const str = typeof val === "string" ? val : JSON.stringify(val);
  await db
    .prepare(
      `INSERT INTO config (key, value) VALUES (?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value`
    )
    .bind(key, str)
    .run();
}

export async function bumpRev(db: D1Database): Promise<number> {
  await db
    .prepare(
      `INSERT INTO config (key, value) VALUES ('rev', '1')
       ON CONFLICT(key) DO UPDATE SET value = CAST(CAST(value AS INTEGER) + 1 AS TEXT)`
    )
    .run();
  const r = await db
    .prepare(`SELECT value FROM config WHERE key = 'rev'`)
    .first<{ value: string }>();
  return Number(r?.value || "1");
}

export async function logAdmin(
  db: D1Database,
  action: string,
  detail: string
): Promise<void> {
  const logs = await cfgGet<Array<{ at: number; action: string; detail: string }>>(
    db,
    "admin_log",
    []
  );
  logs.unshift({ at: Date.now(), action: clip(action, 80), detail: clip(detail, 300) });
  if (logs.length > 200) logs.length = 200;
  await cfgPut(db, "admin_log", logs);
}

export function ownerDayStr(ms = Date.now()): string {
  const d = new Date(ms + OWNER_TZ_OFFSET_MS);
  return d.toISOString().slice(0, 10);
}

export function devType(v: unknown): "phone" | "tablet" | "tv" {
  return v === "tablet" || v === "tv" ? v : "phone";
}

export function parseBool(v: unknown): { ok: true; value: boolean } | { ok: false } {
  if (typeof v === "boolean") return { ok: true, value: v };
  if (v === 1 || v === 0) return { ok: true, value: v === 1 };
  if (typeof v === "string") {
    const s = v.trim().toLowerCase();
    if (s === "true" || s === "1" || s === "on" || s === "yes") {
      return { ok: true, value: true };
    }
    if (s === "false" || s === "0" || s === "off" || s === "no" || s === "") {
      return { ok: true, value: false };
    }
  }
  return { ok: false };
}

export interface UserRow {
  id: number;
  google_sub: string;
  email: string | null;
  name: string | null;
  status: string;
  locked: number;
  access_until: number | null;
  created_at: number;
  updated_at: number;
}

export interface DeviceRow {
  id: number;
  user_id: number;
  model: string | null;
  name: string | null;
  type: string | null;
  approved: number;
  token_hash: string | null;
  first_seen: number;
  last_seen: number;
}

export type EffectiveStatus = "blocked" | "locked" | "pending" | "expired" | "approved";

export function effectiveStatus(
  user: Pick<UserRow, "status" | "locked" | "access_until">,
  device: Pick<DeviceRow, "approved"> | null,
  now = Date.now()
): EffectiveStatus {
  if (user.status === "blocked") return "blocked";
  if (user.locked === 1 || (user.locked > 1 && now < user.locked)) return "locked";
  if (user.status !== "approved" || !device || device.approved === 0) return "pending";
  if (user.access_until !== null && user.access_until !== undefined && now > user.access_until) {
    return "expired";
  }
  return "approved";
}

export function statusPayload(
  user: Pick<UserRow, "status" | "locked" | "access_until">,
  device: Pick<DeviceRow, "approved"> | null,
  now = Date.now(),
  token?: string
): Record<string, unknown> {
  const status = effectiveStatus(user, device, now);
  const out: Record<string, unknown> = {
    status,
    accessUntil: user.access_until ?? null,
  };
  if (token) out.token = token;
  if (status === "locked" && user.locked > 1 && now < user.locked) {
    out.lockedUntil = user.locked;
  }
  return out;
}

export function untilFrom(
  b: unknown,
  base = Date.now()
): { ok: true; until: number | null } | { ok: false } {
  if (b === null || b === undefined) return { ok: true, until: null };
  if (typeof b !== "object" || Array.isArray(b)) return { ok: false };
  const obj = b as Record<string, unknown>;
  const keys = Object.keys(obj);
  if (keys.length === 0 || obj.unlimited === true) {
    return { ok: true, until: null };
  }
  if ("until" in obj || "accessUntil" in obj) {
    const u = "until" in obj ? obj.until : obj.accessUntil;
    if (u === null) return { ok: true, until: null };
    if (typeof u === "number" && Number.isFinite(u) && u > 0) {
      return { ok: true, until: Math.floor(u) };
    }
    return { ok: false };
  }
  if ("hours" in obj) {
    const h = obj.hours;
    if (typeof h === "number" && Number.isFinite(h) && h > 0 && h <= 87600) {
      return { ok: true, until: Math.floor(base + h * 3600 * 1000) };
    }
    return { ok: false };
  }
  if ("days" in obj) {
    const d = obj.days;
    if (typeof d === "number" && Number.isFinite(d) && d > 0 && d <= 36500) {
      return { ok: true, until: Math.floor(base + d * 86400 * 1000) };
    }
    return { ok: false };
  }
  if ("months" in obj) {
    const m = obj.months;
    if (typeof m === "number" && Number.isInteger(m) && m > 0 && m <= 1200) {
      const dt = new Date(base);
      dt.setUTCMonth(dt.getUTCMonth() + m);
      return { ok: true, until: dt.getTime() };
    }
    return { ok: false };
  }
  return { ok: false };
}

export async function sha256Hex(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlDecode(str: string): Uint8Array {
  let b64 = str.replace(/-/g, "+").replace(/_/g, "/");
  while (b64.length % 4 !== 0) b64 += "=";
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function timingSafeEqualBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export function generateSessionToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

export async function verifyPbkdf2(password: string, stored: string): Promise<boolean> {
  const parts = stored.trim().split("$");
  if (parts.length !== 5 || parts[0] !== "pbkdf2" || parts[1] !== "sha256") {
    return false;
  }
  const iter = Number(parts[2]);
  if (!Number.isInteger(iter) || iter < 10000 || iter > 100000) return false;
  let salt: Uint8Array;
  let expected: Uint8Array;
  try {
    salt = base64UrlDecode(parts[3]);
    expected = base64UrlDecode(parts[4]);
  } catch {
    return false;
  }
  if (salt.length < 8 || expected.length < 16) return false;

  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt,
      iterations: iter,
    },
    keyMaterial,
    expected.length * 8
  );
  return timingSafeEqualBytes(new Uint8Array(bits), expected);
}

export async function hashPasswordPbkdf2(password: string, iter = 100000): Promise<string> {
  const salt = new Uint8Array(16);
  crypto.getRandomValues(salt);
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: iter },
    keyMaterial,
    256
  );
  const toStdB64 = (u: Uint8Array) => {
    let s = "";
    for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s);
  };
  return `pbkdf2$sha256$${iter}$${toStdB64(salt)}$${toStdB64(new Uint8Array(bits))}`;
}

export async function signAdminToken(
  secret: string,
  now = Date.now()
): Promise<{ token: string; expiresAt: number }> {
  const expiresAt = now + 7 * 86400 * 1000;
  const randBytes = new Uint8Array(9);
  crypto.getRandomValues(randBytes);
  const randB64 = base64UrlEncode(randBytes);
  const payload = `admin.${expiresAt}.${randB64}`;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload));
  const sigB64 = base64UrlEncode(new Uint8Array(sig));
  return { token: `${payload}.${sigB64}`, expiresAt };
}

export async function verifyAdminToken(
  token: string,
  secret: string,
  now = Date.now()
): Promise<boolean> {
  const parts = token.split(".");
  if (parts.length !== 4 || parts[0] !== "admin") return false;
  const exp = Number(parts[1]);
  if (!Number.isFinite(exp) || now > exp) return false;
  const payload = `${parts[0]}.${parts[1]}.${parts[2]}`;
  let sigBytes: Uint8Array;
  try {
    sigBytes = base64UrlDecode(parts[3]);
  } catch {
    return false;
  }
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"]
  );
  const expectedSig = await crypto.subtle.sign(
    "HMAC",
    await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    ),
    new TextEncoder().encode(payload)
  );
  return timingSafeEqualBytes(sigBytes, new Uint8Array(expectedSig));
}

export function extractBearer(req: Request): string | null {
  const auth = req.headers.get("Authorization") || "";
  if (!auth.startsWith("Bearer ")) return null;
  const tok = auth.slice(7).trim();
  if (!tok || tok.length > 200) return null;
  return tok;
}

export async function sessionFor(
  req: Request,
  env: Env
): Promise<{ user: UserRow; device: DeviceRow; tokenHash: string } | null> {
  const tok = extractBearer(req);
  if (!tok) return null;
  const tokenHash = await sha256Hex(tok);
  const dev = await env.DB.prepare(`SELECT * FROM devices WHERE token_hash = ?`)
    .bind(tokenHash)
    .first<DeviceRow>();
  if (!dev) return null;
  const user = await env.DB.prepare(`SELECT * FROM users WHERE id = ?`)
    .bind(dev.user_id)
    .first<UserRow>();
  if (!user) return null;
  return { user, device: dev, tokenHash };
}

export async function requireApproved(
  req: Request,
  env: Env,
  always = false
): Promise<
  | { ok: true; session: { user: UserRow; device: DeviceRow; tokenHash: string } | null }
  | { ok: false; res: Response }
> {
  const authOn = (env.AUTH_ENABLED || "true").toLowerCase() !== "false";
  const sess = await sessionFor(req, env);
  if (!authOn && !always && !sess) {
    return { ok: true, session: null };
  }
  if (!sess) {
    return { ok: false, res: json({ error: "unauthorized" }, 401) };
  }
  const st = effectiveStatus(sess.user, sess.device);
  if (st !== "approved") {
    if (!authOn && !always) {
      return { ok: true, session: sess };
    }
    return { ok: false, res: json({ error: "not_approved", status: st }, 401) };
  }
  return { ok: true, session: sess };
}

export async function verifyGoogleIdToken(
  idToken: string,
  env: Env
): Promise<{ sub: string; email: string | null; name: string | null } | null> {
  const parts = idToken.split(".");
  if (parts.length !== 3) return null;
  let header: { alg?: string; kid?: string };
  let payload: {
    iss?: string;
    aud?: string | string[];
    exp?: number;
    sub?: string;
    email?: string;
    email_verified?: boolean;
    name?: string;
  };
  try {
    header = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[0])));
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(parts[1])));
  } catch {
    return null;
  }
  if (header.alg !== "RS256" || !header.kid || !payload.sub) return null;
  if (
    payload.iss !== "accounts.google.com" &&
    payload.iss !== "https://accounts.google.com"
  ) {
    return null;
  }
  const now = Date.now();
  if (typeof payload.exp !== "number" || payload.exp * 1000 < now - 60000) {
    return null;
  }

  const allowedAuds = (env.GOOGLE_WEB_CLIENT_ID || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  if (allowedAuds.length === 0) return null;
  const tokenAuds = Array.isArray(payload.aud)
    ? payload.aud
    : payload.aud
    ? [payload.aud]
    : [];
  if (!tokenAuds.some((a) => allowedAuds.includes(a))) {
    return null;
  }

  const f = env.__customFetch || fetch;
  let jwks: { keys?: Array<JsonWebKey & { kid?: string }> } | null = null;
  const cache: Cache | null =
    typeof caches !== "undefined" && (caches as unknown as { default?: Cache }).default
      ? (caches as unknown as { default: Cache }).default
      : null;
  const jwksReq = new Request("https://www.googleapis.com/oauth2/v3/certs");

  if (cache) {
    const hit = await cache.match(jwksReq);
    if (hit) {
      try {
        jwks = (await hit.json()) as { keys?: Array<JsonWebKey & { kid?: string }> };
      } catch {
        jwks = null;
      }
    }
  }
  if (!jwks) {
    try {
      const res = await f("https://www.googleapis.com/oauth2/v3/certs");
      if (!res.ok) return null;
      jwks = (await res.json()) as { keys?: Array<JsonWebKey & { kid?: string }> };
      if (cache) {
        await cache.put(
          jwksReq,
          new Response(JSON.stringify(jwks), {
            headers: { "Cache-Control": "public, max-age=3600" },
          })
        );
      }
    } catch {
      return null;
    }
  }

  const jwk = (jwks?.keys || []).find((k) => k.kid === header.kid);
  if (!jwk) return null;

  try {
    const pubKey = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"]
    );
    const signedData = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    const sig = base64UrlDecode(parts[2]);
    const valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      pubKey,
      sig,
      signedData
    );
    if (!valid) return null;
  } catch {
    return null;
  }

  const email =
    payload.email_verified !== false && typeof payload.email === "string"
      ? payload.email.trim().toLowerCase()
      : null;
  const name = typeof payload.name === "string" ? clip(payload.name, 120) : null;
  return { sub: clip(payload.sub, 120), email, name };
}

const TMDB_ALLOWED_PATTERNS: RegExp[] = [
  /^trending\/(all|tv|movie)\/(day|week)$/,
  /^movie\/(popular|top_rated|now_playing|upcoming)$/,
  /^tv\/(popular|top_rated|on_the_air|airing_today)$/,
  /^discover\/(tv|movie)$/,
  /^search\/(multi|tv|movie|person|collection|keyword)$/,
  /^(movie|tv)\/\d+$/,
  /^tv\/\d+\/season\/\d+$/,
  /^tv\/\d+\/season\/\d+\/episode\/\d+$/,
  /^genre\/(tv|movie)\/list$/,
  /^(movie|tv)\/\d+\/(images|videos|similar|recommendations|credits|aggregate_credits|reviews|keywords|watch\/providers|external_ids|content_ratings|release_dates)$/,
  /^tv\/\d+\/episode_groups$/,
  /^tv\/episode_group\/[a-f0-9]{24}$/,
  /^person\/\d+$/,
  /^person\/\d+\/(combined_credits|images)$/,
  /^collection\/\d+$/,
  /^trending\/person\/(day|week)$/,
  /^person\/popular$/,
  /^keyword\/\d+$/,
  /^network\/\d+$/,
  /^company\/\d+$/,
];

export function isTmdbPathAllowed(subPath: string): boolean {
  return TMDB_ALLOWED_PATTERNS.some((re) => re.test(subPath));
}

export function isTmdbListPath(subPath: string): boolean {
  return (
    subPath.startsWith("trending/") ||
    subPath.startsWith("discover/") ||
    subPath.startsWith("search/") ||
    subPath.startsWith("genre/") ||
    /^(movie|tv)\/(popular|top_rated|now_playing|upcoming|on_the_air|airing_today)$/.test(
      subPath
    )
  );
}

export async function getCatalog(db: D1Database): Promise<CatalogData> {
  const cat = await cfgGet<CatalogData | null>(db, "catalog", null);
  if (cat && Array.isArray(cat.repos) && Array.isArray(cat.servers)) {
    return cat;
  }
  const legacy = await cfgGet<{ repos?: string[]; plugins?: string[] }>(db, "sources", {
    repos: [],
    plugins: [],
  });
  return {
    repos: (legacy.repos || []).map((u) => ({
      url: u,
      name: u,
      description: "",
      iconUrl: null,
      addedAt: 0,
      lastFetched: null,
      error: null,
    })),
    servers: [],
  };
}

export async function saveCatalog(db: D1Database, cat: CatalogData): Promise<void> {
  await cfgPut(db, "catalog", cat);
  const enabledRepos = Array.from(
    new Set(cat.servers.filter((s) => s.enabled).map((s) => s.repo))
  );
  const enabledPlugins = cat.servers.filter((s) => s.enabled).map((s) => s.internalName);
  await cfgPut(db, "sources", { repos: enabledRepos, plugins: enabledPlugins });
}

async function buildUserItems(
  db: D1Database,
  now: number,
  filterUserId?: number
): Promise<Record<string, unknown>[]> {
  const usersQuery = filterUserId
    ? await db.prepare(`SELECT * FROM users WHERE id = ?`).bind(filterUserId).all<UserRow>()
    : await db.prepare(`SELECT * FROM users ORDER BY created_at DESC`).all<UserRow>();
  const users = usersQuery.results || [];
  if (users.length === 0) return [];

  const devsQuery = filterUserId
    ? await db
        .prepare(`SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen DESC`)
        .bind(filterUserId)
        .all<DeviceRow>()
    : await db.prepare(`SELECT * FROM devices ORDER BY last_seen DESC`).all<DeviceRow>();
  const usageQuery = await db.prepare(`SELECT * FROM user_usage`).all<{
    user_id: number;
    total_watch_seconds: number;
    browse_count: number;
    last_seen_at: number | null;
    current_state: string | null;
    last_app_version: string | null;
  }>();

  const todayStr = ownerDayStr(now);
  const sevenDaysAgoStr = ownerDayStr(now - 6 * 86400 * 1000);
  const dailyQuery = await db
    .prepare(`SELECT user_id, day, seconds, screens FROM user_usage_daily WHERE day >= ?`)
    .bind(sevenDaysAgoStr)
    .all<{ user_id: number; day: string; seconds: number; screens: number }>();

  const overridesQuery = await db
    .prepare(`SELECT user_id, COUNT(*) AS cnt FROM user_sources GROUP BY user_id`)
    .all<{ user_id: number; cnt: number }>();
  const rulesQuery = await db
    .prepare(`SELECT user_id, hide_non_arabic, hide_live FROM user_source_rules`)
    .all<{ user_id: number; hide_non_arabic: number; hide_live: number }>();

  const devsByUser = new Map<number, DeviceRow[]>();
  for (const d of devsQuery.results || []) {
    const arr = devsByUser.get(d.user_id) || [];
    arr.push(d);
    devsByUser.set(d.user_id, arr);
  }

  const usageByUser = new Map<
    number,
    {
      total_watch_seconds: number;
      browse_count: number;
      last_seen_at: number | null;
      current_state: string | null;
      last_app_version: string | null;
    }
  >();
  for (const u of usageQuery.results || []) {
    usageByUser.set(u.user_id, u);
  }

  const todaySecByUser = new Map<number, number>();
  const weekSecByUser = new Map<number, number>();
  for (const d of dailyQuery.results || []) {
    weekSecByUser.set(d.user_id, (weekSecByUser.get(d.user_id) || 0) + (d.seconds || 0));
    if (d.day === todayStr) {
      todaySecByUser.set(d.user_id, (todaySecByUser.get(d.user_id) || 0) + (d.seconds || 0));
    }
  }

  const customByUser = new Map<number, number>();
  for (const o of overridesQuery.results || []) {
    customByUser.set(o.user_id, o.cnt || 0);
  }
  for (const r of rulesQuery.results || []) {
    const add = (r.hide_non_arabic ? 1 : 0) + (r.hide_live ? 1 : 0);
    if (add > 0) {
      customByUser.set(r.user_id, (customByUser.get(r.user_id) || 0) + add);
    }
  }

  return users.map((u) => {
    const devs = devsByUser.get(u.id) || [];
    const activeDevs = devs.filter((d) => d.token_hash !== null);
    const primaryDev = activeDevs[0] || devs[0] || null;
    const anyApprovedDev = activeDevs.some((d) => d.approved === 1);
    const effStatus = effectiveStatus(
      u,
      anyApprovedDev ? { approved: 1 } : primaryDev ? { approved: primaryDev.approved } : null,
      now
    );
    const hasPendingDevice = activeDevs.some((d) => d.approved === 0);
    const isPending = u.status === "pending" || (u.status === "approved" && hasPendingDevice);

    const usg = usageByUser.get(u.id);
    const maxDevLastSeen = devs.reduce((acc, d) => Math.max(acc, d.last_seen || 0), 0);
    const lastSeen = Math.max(maxDevLastSeen, usg?.last_seen_at || 0) || null;
    const anySignedIn = activeDevs.length > 0;
    const online =
      anySignedIn && lastSeen !== null && now - lastSeen < PRESENCE_ONLINE_MS;

    const lockedBool = u.locked === 1 || (u.locked > 1 && now < u.locked);
    const lockedUntil = u.locked > 1 && now < u.locked ? u.locked : null;

    return {
      id: u.id,
      email: u.email,
      name: u.name,
      status: u.status,
      locked: lockedBool,
      lockedUntil,
      effectiveStatus: effStatus,
      pending: isPending,
      accessUntil: u.access_until ?? null,
      createdAt: u.created_at,
      deviceModel: primaryDev?.model || null,
      deviceType: primaryDev?.type || "phone",
      lastSeen,
      online,
      state: online ? usg?.current_state || "browsing" : null,
      appVersion: usg?.last_app_version || null,
      watchSecondsTotal: usg?.total_watch_seconds || 0,
      watchSecondsToday: todaySecByUser.get(u.id) || 0,
      watchSeconds7d: weekSecByUser.get(u.id) || 0,
      browseCount: usg?.browse_count || 0,
      customSources: customByUser.get(u.id) || 0,
      devices: devs.map((d) => ({
        id: d.id,
        model: d.model,
        name: d.name,
        type: d.type || "phone",
        approved: d.approved === 1,
        signedIn: d.token_hash !== null,
        firstSeen: d.first_seen,
        lastSeen: d.last_seen,
        online: d.token_hash !== null && now - d.last_seen < DEVICE_ONLINE_MS,
      })),
    };
  });
}

async function deleteUserCascade(db: D1Database, userId: number): Promise<void> {
  await db.prepare(`DELETE FROM devices WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM user_usage WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM user_usage_daily WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM sync_data WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM sync_progress WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM user_sources WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM user_source_rules WHERE user_id = ?`).bind(userId).run();
  await db.prepare(`DELETE FROM users WHERE id = ?`).bind(userId).run();
}

async function applyUserAction(
  db: D1Database,
  userId: number,
  action: string,
  body: Record<string, unknown>,
  now: number
): Promise<{ ok: true } | { ok: false; status: number; error: string }> {
  const user = await db
    .prepare(`SELECT * FROM users WHERE id = ?`)
    .bind(userId)
    .first<UserRow>();
  if (!user) return { ok: false, status: 404, error: "no such user" };

  if (action === "approve") {
    const dur = untilFrom(body, now);
    if (!dur.ok) return { ok: false, status: 400, error: "bad duration" };
    await db
      .prepare(
        `UPDATE users SET status = 'approved', locked = 0, access_until = ?, updated_at = ? WHERE id = ?`
      )
      .bind(dur.until, now, userId)
      .run();
    await db
      .prepare(
        `UPDATE devices SET approved = 1 WHERE user_id = ? AND token_hash IS NOT NULL`
      )
      .bind(userId)
      .run();
    return { ok: true };
  }

  if (action === "access") {
    const dur = untilFrom(body, now);
    if (!dur.ok) return { ok: false, status: 400, error: "bad duration" };
    await db
      .prepare(`UPDATE users SET access_until = ?, updated_at = ? WHERE id = ?`)
      .bind(dur.until, now, userId)
      .run();
    return { ok: true };
  }

  if (action === "block") {
    await db
      .prepare(`UPDATE users SET status = 'blocked', updated_at = ? WHERE id = ?`)
      .bind(now, userId)
      .run();
    await db
      .prepare(`UPDATE devices SET approved = 0 WHERE user_id = ?`)
      .bind(userId)
      .run();
    return { ok: true };
  }

  if (action === "unblock") {
    await db
      .prepare(
        `UPDATE users SET status = 'pending', locked = 0, updated_at = ? WHERE id = ?`
      )
      .bind(now, userId)
      .run();
    return { ok: true };
  }

  if (action === "lock") {
    let lockVal = 1;
    if (body && Object.keys(body).length > 0 && body.unlimited !== true) {
      const dur = untilFrom(body, now);
      if (!dur.ok) return { ok: false, status: 400, error: "bad duration" };
      if (dur.until !== null) lockVal = Math.max(2, dur.until);
    }
    await db
      .prepare(`UPDATE users SET locked = ?, updated_at = ? WHERE id = ?`)
      .bind(lockVal, now, userId)
      .run();
    return { ok: true };
  }

  if (action === "unlock") {
    await db
      .prepare(`UPDATE users SET locked = 0, updated_at = ? WHERE id = ?`)
      .bind(now, userId)
      .run();
    return { ok: true };
  }

  if (action === "kick") {
    await db
      .prepare(
        `UPDATE devices SET token_hash = NULL, approved = 0 WHERE user_id = ?`
      )
      .bind(userId)
      .run();
    return { ok: true };
  }

  if (action === "delete") {
    await deleteUserCascade(db, userId);
    return { ok: true };
  }

  return { ok: false, status: 400, error: "bad_action" };
}

async function handleAdminRoute(
  req: Request,
  path: string,
  url: URL,
  env: Env,
  ctx?: ExecutionContext
): Promise<Response> {
  const now = Date.now();
  if (!env.SESSION_SECRET || !env.ADMIN_PASS_HASH) {
    return json({ error: "admin not configured" }, 503);
  }

  if (req.method === "POST" && path === "/admin/login") {
    const ip = req.headers.get("CF-Connecting-IP") || "local";
    const windowMs = 15 * 60 * 1000;
    const attempt = await env.DB.prepare(
      `SELECT fails, window_start FROM login_attempts WHERE ip = ?`
    )
      .bind(ip)
      .first<{ fails: number; window_start: number }>();

    if (attempt && now - attempt.window_start < windowMs && attempt.fails >= 5) {
      const retryAfterSec = Math.max(
        1,
        Math.ceil((attempt.window_start + windowMs - now) / 1000)
      );
      return json(
        { error: "too_many_attempts", retryAfterSec },
        429,
        { "Retry-After": String(retryAfterSec) }
      );
    }

    let body: { username?: string; password?: string } = {};
    try {
      body = (await req.json()) as { username?: string; password?: string };
    } catch {
      body = {};
    }
    const username = (body.username || "").trim();
    const password =
      req.headers.get("X-Rayeq-Admin-Pass") ||
      (typeof body.password === "string" ? body.password : "");
    const expectedUser = (env.ADMIN_USER || "admin").trim();

    const customHash = await cfgGet<string | null>(env.DB, "admin_pass", null);
    const activeHash = customHash || env.ADMIN_PASS_HASH;

    const userMatch = username === expectedUser;
    const passMatch = password ? await verifyPbkdf2(password, activeHash) : false;

    if (!userMatch || !passMatch) {
      const newFails =
        attempt && now - attempt.window_start < windowMs ? attempt.fails + 1 : 1;
      const winStart =
        attempt && now - attempt.window_start < windowMs ? attempt.window_start : now;
      await env.DB.prepare(
        `INSERT INTO login_attempts (ip, fails, window_start) VALUES (?, ?, ?)
         ON CONFLICT(ip) DO UPDATE SET fails = excluded.fails, window_start = excluded.window_start`
      )
        .bind(ip, newFails, winStart)
        .run();
      return json({ error: "invalid_credentials" }, 401);
    }

    await env.DB.prepare(`DELETE FROM login_attempts WHERE ip = ?`).bind(ip).run();
    const signed = await signAdminToken(env.SESSION_SECRET, now);
    await logAdmin(env.DB, "login", `Admin login from ${ip}`);
    return json(signed);
  }

  // Verify admin bearer token for all other /admin/* routes
  const tok = extractBearer(req);
  if (!tok || !(await verifyAdminToken(tok, env.SESSION_SECRET, now))) {
    return json({ error: "unauthorized" }, 401);
  }

  if (req.method === "GET" && path === "/admin/me") {
    return json({ ok: true });
  }

  if (req.method === "GET" && path === "/admin/stats") {
    const userItems = await buildUserItems(env.DB, now);
    const cat = await getCatalog(env.DB);
    const soonWindow = now + 3 * 86400 * 1000;
    const weekAgo = now - 7 * 86400 * 1000;
    const dayAgo = now - 86400 * 1000;

    let pending = 0,
      active = 0,
      blocked = 0,
      locked = 0,
      expired = 0,
      expiringSoon = 0,
      newWeek = 0,
      onlineNow = 0,
      watchingNow = 0,
      watchSecondsToday = 0;

    let devTotal = 0,
      devOnline = 0,
      devPending = 0,
      phones = 0,
      tablets = 0,
      tvs = 0,
      activeDay = 0;

    for (const u of userItems) {
      const eff = u.effectiveStatus as EffectiveStatus;
      if (u.pending) pending++;
      if (eff === "approved") active++;
      if (eff === "blocked") blocked++;
      if (eff === "locked") locked++;
      if (eff === "expired") expired++;
      const until = u.accessUntil as number | null;
      if (eff === "approved" && until !== null && until > now && until <= soonWindow) {
        expiringSoon++;
      }
      if ((u.createdAt as number) >= weekAgo) newWeek++;
      if (u.online) onlineNow++;
      if (u.online && u.state === "watching") watchingNow++;
      watchSecondsToday += (u.watchSecondsToday as number) || 0;

      const devs = (u.devices as Array<Record<string, unknown>>) || [];
      for (const d of devs) {
        devTotal++;
        if (d.online) devOnline++;
        if (d.signedIn && !d.approved) devPending++;
        if (d.type === "tablet") tablets++;
        else if (d.type === "tv") tvs++;
        else phones++;
        if (((d.lastSeen as number) || 0) >= dayAgo) activeDay++;
      }
    }

    const contentServers = cat.servers.filter((s) => !kindFields(s).tool);
    return json({
      now,
      users: {
        total: userItems.length,
        pending,
        active,
        blocked,
        locked,
        expired,
        expiringSoon,
        newWeek,
      },
      devices: {
        total: devTotal,
        online: devOnline,
        pending: devPending,
        phones,
        tablets,
        tvs,
        activeDay,
      },
      sources: {
        repos: cat.repos.length,
        servers: contentServers.length,
        enabled: contentServers.filter((s) => s.enabled).length,
      },
      onlineNow,
      watchingNow,
      watchSecondsToday,
    });
  }

  if (req.method === "GET" && path === "/admin/users") {
    const sort = url.searchParams.get("sort") || "";
    const users = await buildUserItems(env.DB, now);
    if (sort === "online") {
      users.sort((a, b) => {
        if (a.online !== b.online) return a.online ? -1 : 1;
        return ((b.lastSeen as number) || 0) - ((a.lastSeen as number) || 0);
      });
    } else if (sort === "last_seen") {
      users.sort(
        (a, b) => ((b.lastSeen as number) || 0) - ((a.lastSeen as number) || 0)
      );
    } else if (sort === "watch") {
      users.sort(
        (a, b) =>
          ((b.watchSecondsTotal as number) || 0) -
          ((a.watchSecondsTotal as number) || 0)
      );
    }
    return json({ now, users });
  }

  const userDetailMatch = path.match(/^\/admin\/users\/(\d+)$/);
  if (userDetailMatch && req.method === "GET") {
    const uid = Number(userDetailMatch[1]);
    const items = await buildUserItems(env.DB, now, uid);
    if (items.length === 0) return json({ error: "no such user" }, 404);
    const days: Array<{ day: string; seconds: number; screens: number }> = [];
    const dayRows = await env.DB.prepare(
      `SELECT day, seconds, screens FROM user_usage_daily WHERE user_id = ? AND day >= ?`
    )
      .bind(uid, ownerDayStr(now - 6 * 86400 * 1000))
      .all<{ day: string; seconds: number; screens: number }>();
    const map = new Map<string, { seconds: number; screens: number }>();
    for (const r of dayRows.results || []) {
      map.set(r.day, { seconds: r.seconds || 0, screens: r.screens || 0 });
    }
    for (let i = 6; i >= 0; i--) {
      const dStr = ownerDayStr(now - i * 86400 * 1000);
      const v = map.get(dStr) || { seconds: 0, screens: 0 };
      days.push({ day: dStr, seconds: v.seconds, screens: v.screens });
    }
    return json({ now, user: { ...items[0], days } });
  }

  if (userDetailMatch && req.method === "DELETE") {
    const uid = Number(userDetailMatch[1]);
    const res = await applyUserAction(env.DB, uid, "delete", {}, now);
    if (!res.ok) return json({ error: res.error }, res.status);
    await logAdmin(env.DB, "user_delete", `Deleted user #${uid}`);
    return json({ ok: true });
  }

  const userActionMatch = path.match(
    /^\/admin\/users\/(\d+)\/(approve|access|block|unblock|lock|unlock|kick)$/
  );
  if (userActionMatch && req.method === "POST") {
    const uid = Number(userActionMatch[1]);
    const act = userActionMatch[2];
    let body: Record<string, unknown> = {};
    try {
      const txt = await req.text();
      if (txt.trim()) body = JSON.parse(txt);
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    const res = await applyUserAction(env.DB, uid, act, body, now);
    if (!res.ok) return json({ error: res.error }, res.status);
    await logAdmin(env.DB, `user_${act}`, `User #${uid}`);
    return json({ ok: true });
  }

  if (req.method === "POST" && path === "/admin/users/bulk") {
    let body: {
      ids?: unknown;
      action?: string;
      duration?: Record<string, unknown>;
    } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    if (
      !Array.isArray(body.ids) ||
      body.ids.length === 0 ||
      body.ids.length > 500 ||
      typeof body.action !== "string"
    ) {
      return json({ error: "bad_body" }, 400);
    }
    let done = 0;
    for (const rawId of body.ids) {
      const uid = Number(rawId);
      if (!Number.isInteger(uid) || uid <= 0) continue;
      const res = await applyUserAction(
        env.DB,
        uid,
        body.action,
        body.duration || {},
        now
      );
      if (res.ok) done++;
    }
    await logAdmin(env.DB, `bulk_${body.action}`, `Processed ${done} users`);
    return json({ ok: true, done });
  }

  const devActionMatch = path.match(/^\/admin\/devices\/(\d+)\/(kick|approve)$/);
  if (devActionMatch && req.method === "POST") {
    const devId = Number(devActionMatch[1]);
    const act = devActionMatch[2];
    const dev = await env.DB.prepare(`SELECT * FROM devices WHERE id = ?`)
      .bind(devId)
      .first<DeviceRow>();
    if (!dev) return json({ error: "no such device" }, 404);
    if (act === "kick") {
      await env.DB.prepare(
        `UPDATE devices SET token_hash = NULL, approved = 0 WHERE id = ?`
      )
        .bind(devId)
        .run();
    } else {
      await env.DB.prepare(`UPDATE devices SET approved = 1 WHERE id = ?`)
        .bind(devId)
        .run();
    }
    await logAdmin(env.DB, `device_${act}`, `Device #${devId}`);
    return json({ ok: true });
  }

  if (req.method === "POST" && path === "/admin/devices/kick-all") {
    await env.DB.prepare(`UPDATE devices SET token_hash = NULL, approved = 0`).run();
    await logAdmin(env.DB, "kick_all", "Kicked all devices");
    return json({ ok: true });
  }

  // Per-user sources (/admin/users/{id}/sources)
  const userSourcesMatch = path.match(/^\/admin\/users\/(\d+)\/sources$/);
  if (userSourcesMatch) {
    const uid = Number(userSourcesMatch[1]);
    const user = await env.DB.prepare(`SELECT id FROM users WHERE id = ?`)
      .bind(uid)
      .first();
    if (!user) return json({ error: "no such user" }, 404);
    const cat = await getCatalog(env.DB);

    if (req.method === "GET") {
      return json(await buildAdminUserSources(env.DB, uid, cat));
    }
    if (req.method === "DELETE") {
      await env.DB.prepare(`DELETE FROM user_sources WHERE user_id = ?`).bind(uid).run();
      await env.DB.prepare(`DELETE FROM user_source_rules WHERE user_id = ?`).bind(uid).run();
      await logAdmin(env.DB, "user_sources_reset", `User #${uid}`);
      return json(await buildAdminUserSources(env.DB, uid, cat));
    }
    if (req.method === "PUT") {
      let body: unknown;
      try {
        body = await req.json();
      } catch {
        return json({ error: "bad_json" }, 400);
      }
      if (!body || typeof body !== "object" || Array.isArray(body)) {
        return json({ error: "bad_body" }, 400);
      }
      const b = body as {
        rules?: { hideNonArabic?: unknown; hideLive?: unknown };
        servers?: unknown;
      };

      if (b.rules !== undefined) {
        if (!b.rules || typeof b.rules !== "object" || Array.isArray(b.rules)) {
          return json({ error: "bad_rules" }, 400);
        }
        const cur = await env.DB.prepare(
          `SELECT hide_non_arabic, hide_live FROM user_source_rules WHERE user_id = ?`
        )
          .bind(uid)
          .first<{ hide_non_arabic: number; hide_live: number }>();
        let hideNonArabic = Boolean(cur?.hide_non_arabic);
        let hideLive = Boolean(cur?.hide_live);
        if ("hideNonArabic" in b.rules) {
          if (typeof b.rules.hideNonArabic !== "boolean") {
            return json({ error: "bad_rules" }, 400);
          }
          hideNonArabic = b.rules.hideNonArabic;
        }
        if ("hideLive" in b.rules) {
          if (typeof b.rules.hideLive !== "boolean") {
            return json({ error: "bad_rules" }, 400);
          }
          hideLive = b.rules.hideLive;
        }
        await env.DB.prepare(
          `INSERT INTO user_source_rules (user_id, hide_non_arabic, hide_live, updated_at)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET
             hide_non_arabic = excluded.hide_non_arabic,
             hide_live = excluded.hide_live,
             updated_at = excluded.updated_at`
        )
          .bind(uid, hideNonArabic ? 1 : 0, hideLive ? 1 : 0, now)
          .run();
      }

      if (b.servers !== undefined) {
        if (!Array.isArray(b.servers)) {
          return json({ error: "bad_servers" }, 400);
        }
        const knownNames = new Set(cat.servers.map((s) => s.internalName));
        for (const item of b.servers) {
          if (!item || typeof item !== "object") {
            return json({ error: "bad_servers" }, 400);
          }
          const it = item as { internalName?: unknown; enabled?: unknown };
          if (typeof it.internalName !== "string" || !it.internalName) {
            return json({ error: "bad_servers" }, 400);
          }
          if (!knownNames.has(it.internalName)) {
            return json({ error: "unknown_server" }, 400);
          }
          if (it.enabled !== true && it.enabled !== false && it.enabled !== null) {
            return json({ error: "bad_servers" }, 400);
          }
        }
        for (const item of b.servers as Array<{
          internalName: string;
          enabled: boolean | null;
        }>) {
          if (item.enabled === null) {
            await env.DB.prepare(
              `DELETE FROM user_sources WHERE user_id = ? AND internal_name = ?`
            )
              .bind(uid, item.internalName)
              .run();
          } else {
            await env.DB.prepare(
              `INSERT INTO user_sources (user_id, internal_name, enabled, updated_at)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(user_id, internal_name) DO UPDATE SET
                 enabled = excluded.enabled,
                 updated_at = excluded.updated_at`
            )
              .bind(uid, item.internalName, item.enabled ? 1 : 0, now)
              .run();
          }
        }
      }

      await logAdmin(env.DB, "user_sources_update", `User #${uid}`);
      return json(await buildAdminUserSources(env.DB, uid, cat));
    }
  }

  // Catalog & Repos
  if (req.method === "POST" && path === "/admin/repos/inspect") {
    let body: { url?: unknown } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    try {
      const inspected = await readRepo(body.url as string, true, ctx, env.__customFetch);
      return json(inspected);
    } catch (e) {
      if (e instanceof CatalogError) return json({ error: e.code }, e.status);
      return json({ error: "fetch_failed" }, 502);
    }
  }

  if (req.method === "POST" && path === "/admin/repos") {
    let body: { url?: unknown } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    const cat = await getCatalog(env.DB);
    if (cat.repos.length >= 50) {
      return json({ error: "too_many_repos" }, 400);
    }
    try {
      const normUrl = normalizeRepoUrl(body.url);
      if (cat.repos.some((r) => r.url === normUrl)) {
        return json({ error: "repo_exists" }, 409);
      }
      const inspected = await readRepo(normUrl, true, ctx, env.__customFetch);
      const entry: RepoEntry = {
        url: inspected.repo.url,
        name: inspected.repo.name,
        description: inspected.repo.description,
        iconUrl: inspected.repo.iconUrl,
        addedAt: now,
        lastFetched: now,
        error: null,
      };
      cat.repos.push(entry);
      await saveCatalog(env.DB, cat);
      await logAdmin(env.DB, "repo_add", entry.name);
      return json({ ok: true, repo: entry, pluginCount: inspected.plugins.length });
    } catch (e) {
      if (e instanceof CatalogError) return json({ error: e.code }, e.status);
      return json({ error: "fetch_failed" }, 502);
    }
  }

  if (req.method === "POST" && path === "/admin/repos/remove") {
    let body: { url?: unknown } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    let normUrl: string;
    try {
      normUrl = normalizeRepoUrl(body.url);
    } catch {
      normUrl = String(body.url || "").trim();
    }
    const cat = await getCatalog(env.DB);
    cat.repos = cat.repos.filter((r) => r.url !== normUrl);
    cat.servers = cat.servers.filter((s) => s.repo !== normUrl);
    await saveCatalog(env.DB, cat);
    await logAdmin(env.DB, "repo_remove", normUrl);
    return json({ ok: true });
  }

  if (req.method === "POST" && path === "/admin/repos/refresh") {
    let body: { url?: unknown } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    try {
      const normUrl = normalizeRepoUrl(body.url);
      const cat = await getCatalog(env.DB);
      const repoEntry = cat.repos.find((r) => r.url === normUrl);
      if (!repoEntry) return json({ error: "no_such_repo" }, 404);
      const inspected = await readRepo(normUrl, true, ctx, env.__customFetch);
      repoEntry.name = inspected.repo.name;
      repoEntry.description = inspected.repo.description;
      repoEntry.iconUrl = inspected.repo.iconUrl;
      repoEntry.lastFetched = now;
      repoEntry.error = null;

      const byName = new Map(inspected.plugins.map((p) => [p.internalName, p]));
      cat.servers = cat.servers.filter((s) => {
        if (s.repo !== normUrl) return true;
        const meta = byName.get(s.internalName);
        if (!meta) return false; // حُذف من مستودع قُرئ بنجاح
        s.name = meta.name;
        s.iconUrl = meta.iconUrl;
        s.language = meta.language;
        s.tvTypes = meta.tvTypes;
        return true;
      });
      await saveCatalog(env.DB, cat);
      return json({ ok: true, pluginCount: inspected.plugins.length });
    } catch (e) {
      if (e instanceof CatalogError) return json({ error: e.code }, e.status);
      return json({ error: "fetch_failed" }, 502);
    }
  }

  if (req.method === "GET" && path === "/admin/catalog") {
    const fresh = url.searchParams.get("fresh") === "1";
    const cat = await getCatalog(env.DB);
    const reports = await cfgGet<Record<string, PluginReport>>(
      env.DB,
      "plugin_reports",
      {}
    );
    let catalogChanged = false;
    const reposOut: unknown[] = [];

    for (const r of cat.repos) {
      try {
        const res = await readRepo(r.url, fresh, ctx, env.__customFetch);
        r.name = res.repo.name || r.name;
        r.description = res.repo.description || r.description;
        r.iconUrl = res.repo.iconUrl ?? r.iconUrl;
        r.lastFetched = now;
        r.error = null;

        const metaMap = new Map(res.plugins.map((p) => [p.internalName, p]));
        const beforeLen = cat.servers.length;
        cat.servers = cat.servers.filter((s) => {
          if (s.repo !== r.url) return true;
          const m = metaMap.get(s.internalName);
          if (!m) return false;
          if (
            s.language !== m.language ||
            s.iconUrl !== m.iconUrl ||
            JSON.stringify(s.tvTypes || []) !== JSON.stringify(m.tvTypes || [])
          ) {
            s.language = m.language;
            s.iconUrl = m.iconUrl;
            s.tvTypes = m.tvTypes;
            s.name = m.name || s.name;
            catalogChanged = true;
          }
          return true;
        });
        if (cat.servers.length !== beforeLen) catalogChanged = true;

        const srvByName = new Map(
          cat.servers.filter((s) => s.repo === r.url).map((s) => [s.internalName, s])
        );
        const pluginsWithStatus = res.plugins.map((pm) => {
          const srv = srvByName.get(pm.internalName);
          const rep = reports[pm.internalName];
          const st = pluginStatus(pm, srv, rep);
          const kf = kindFields({
            internalName: pm.internalName,
            name: pm.name,
            language: pm.language,
            kind: srv?.kind || "auto",
            tvTypes: pm.tvTypes,
          });
          return {
            ...pm,
            pluginStatus: st,
            installedVersion: srv?.version ?? null,
            enabled: srv ? Boolean(srv.enabled) : false,
            ...kf,
            report: rep || null,
          };
        });

        reposOut.push({
          ...r,
          ok: true,
          pluginCount: res.plugins.length,
          installedCount: srvByName.size,
          plugins: pluginsWithStatus,
        });
      } catch (e) {
        const errCode = e instanceof CatalogError ? e.code : "fetch_failed";
        r.error = errCode;
        // المستودع الذي فشلت قراءته لا يُحذف منه شيء
        const installedForRepo = cat.servers.filter((s) => s.repo === r.url);
        reposOut.push({
          ...r,
          ok: false,
          pluginCount: installedForRepo.length,
          installedCount: installedForRepo.length,
          plugins: [],
        });
      }
    }

    if (catalogChanged) {
      await saveCatalog(env.DB, cat);
    }

    return json({
      repos: reposOut,
      servers: adminServerItems(cat.servers, reports),
    });
  }

  if (req.method === "POST" && path === "/admin/plugins") {
    let body: {
      repo?: string;
      install?: string[];
      uninstall?: string[];
      all?: "install" | "uninstall";
    } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    let repoUrl: string;
    try {
      repoUrl = normalizeRepoUrl(body.repo);
    } catch {
      return json({ error: "bad_url" }, 400);
    }
    const cat = await getCatalog(env.DB);
    const reports = await cfgGet<Record<string, PluginReport>>(
      env.DB,
      "plugin_reports",
      {}
    );

    let added = 0;
    let removed = 0;

    if (body.all === "uninstall") {
      const before = cat.servers.length;
      cat.servers = cat.servers.filter((s) => s.repo !== repoUrl);
      removed = before - cat.servers.length;
    } else {
      const toRemove = new Set(Array.isArray(body.uninstall) ? body.uninstall : []);
      if (toRemove.size > 0) {
        const before = cat.servers.length;
        cat.servers = cat.servers.filter(
          (s) => !(s.repo === repoUrl && toRemove.has(s.internalName))
        );
        removed += before - cat.servers.length;
      }

      const needInstall =
        body.all === "install" ||
        (Array.isArray(body.install) && body.install.length > 0);
      if (needInstall) {
        try {
          const inspected = await readRepo(repoUrl, false, ctx, env.__customFetch);
          const candidates =
            body.all === "install"
              ? inspected.plugins
              : inspected.plugins.filter((p) =>
                  (body.install || []).includes(p.internalName)
                );
          for (const pm of candidates) {
            const exists = cat.servers.find((s) => s.internalName === pm.internalName);
            if (exists) {
              exists.enabled = true;
              exists.version = pm.version;
              exists.name = pm.name;
              exists.iconUrl = pm.iconUrl;
              exists.language = pm.language;
              exists.tvTypes = pm.tvTypes;
            } else {
              if (cat.servers.length >= 300) break;
              cat.servers.push({
                repo: repoUrl,
                internalName: pm.internalName,
                name: pm.name,
                version: pm.version,
                enabled: true,
                addedAt: now,
                iconUrl: pm.iconUrl,
                language: pm.language,
                arabic: null,
                kind: "auto",
                tvTypes: pm.tvTypes,
              });
              added++;
            }
          }
        } catch (e) {
          if (e instanceof CatalogError) return json({ error: e.code }, e.status);
          return json({ error: "fetch_failed" }, 502);
        }
      }
    }

    await saveCatalog(env.DB, cat);
    await logAdmin(env.DB, "plugins_change", `+${added} / -${removed}`);
    return json({
      ok: true,
      added,
      removed,
      servers: adminServerItems(cat.servers, reports),
    });
  }

  if (req.method === "POST" && path === "/admin/plugins/update") {
    let body: { repo?: string; internalName?: string; all?: boolean } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    let repoUrl: string;
    try {
      repoUrl = normalizeRepoUrl(body.repo);
    } catch {
      return json({ error: "bad_url" }, 400);
    }
    try {
      const inspected = await readRepo(repoUrl, true, ctx, env.__customFetch);
      const byName = new Map(inspected.plugins.map((p) => [p.internalName, p]));
      const cat = await getCatalog(env.DB);
      let updated = 0;
      for (const s of cat.servers) {
        if (s.repo !== repoUrl) continue;
        if (!body.all && s.internalName !== body.internalName) continue;
        const m = byName.get(s.internalName);
        if (m && m.version > (s.version || 0)) {
          s.version = m.version;
          s.name = m.name;
          s.iconUrl = m.iconUrl;
          s.language = m.language;
          s.tvTypes = m.tvTypes;
          updated++;
        }
      }
      await saveCatalog(env.DB, cat);
      await logAdmin(env.DB, "plugins_update", `Updated ${updated} plugins`);
      return json({ ok: true, updated });
    } catch (e) {
      if (e instanceof CatalogError) return json({ error: e.code }, e.status);
      return json({ error: "fetch_failed" }, 502);
    }
  }

  if (req.method === "GET" && path === "/admin/servers") {
    const cat = await getCatalog(env.DB);
    const reports = await cfgGet<Record<string, PluginReport>>(
      env.DB,
      "plugin_reports",
      {}
    );
    return json(adminServerItems(cat.servers, reports));
  }

  if (req.method === "PUT" && path === "/admin/servers") {
    let body: {
      servers?: Array<{
        repo?: string;
        internalName?: string;
        enabled?: boolean;
        arabic?: boolean | null;
        kind?: "auto" | "live" | "content";
      }>;
    } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    if (!Array.isArray(body.servers)) {
      return json({ error: "bad_servers" }, 400);
    }
    const cat = await getCatalog(env.DB);
    const existingByKey = new Map(
      cat.servers.map((s) => [`${s.repo}|${s.internalName}`, s])
    );
    const existingByName = new Map(cat.servers.map((s) => [s.internalName, s]));
    const placed = new Set<ServerEntry>();
    const reordered: ServerEntry[] = [];

    for (const item of body.servers) {
      if (!item || typeof item.internalName !== "string") continue;
      const srv =
        (item.repo && existingByKey.get(`${item.repo}|${item.internalName}`)) ||
        existingByName.get(item.internalName);
      if (!srv || placed.has(srv)) continue;
      if (typeof item.enabled === "boolean") srv.enabled = item.enabled;
      if (item.arabic === true || item.arabic === false || item.arabic === null) {
        srv.arabic = item.arabic;
      }
      if (item.kind === "auto" || item.kind === "live" || item.kind === "content") {
        srv.kind = item.kind;
      }
      placed.add(srv);
      reordered.push(srv);
    }
    for (const srv of cat.servers) {
      if (!placed.has(srv)) reordered.push(srv);
    }
    cat.servers = reordered;
    await saveCatalog(env.DB, cat);
    const reports = await cfgGet<Record<string, PluginReport>>(
      env.DB,
      "plugin_reports",
      {}
    );
    await logAdmin(env.DB, "servers_update", `Updated ${reordered.length} servers`);
    return json(adminServerItems(cat.servers, reports));
  }

  if (path === "/admin/sources") {
    if (req.method === "GET") {
      return json(
        await cfgGet(env.DB, "sources", { repos: [], plugins: [] })
      );
    }
    if (req.method === "PUT") {
      let body: { repos?: string[]; plugins?: string[] } = {};
      try {
        body = await req.json();
      } catch {
        return json({ error: "bad_json" }, 400);
      }
      const next = {
        repos: Array.isArray(body.repos) ? body.repos : [],
        plugins: Array.isArray(body.plugins) ? body.plugins : [],
      };
      await cfgPut(env.DB, "sources", next);
      return json(next);
    }
  }

  if (path === "/admin/app") {
    const cur = await cfgGet<Record<string, unknown>>(env.DB, "app", {});
    if (req.method === "GET") {
      const m = parseBool(cur.maintenance);
      return json({
        latestVersion: cur.latestVersion ?? null,
        latestCode: cur.latestCode ?? null,
        downloadUrl: cur.downloadUrl ?? null,
        notes: cur.notes ?? "",
        minCode: cur.minCode ?? null,
        announcement: cur.announcement ?? "",
        announcementId: cur.announcementId ?? 0,
        maintenance: m.ok ? m.value : false,
        maintenanceMessage: cur.maintenanceMessage ?? "",
        supportContact: cur.supportContact ?? "",
      });
    }
    if (req.method === "PUT") {
      let body: Record<string, unknown> = {};
      try {
        body = await req.json();
      } catch {
        return json({ error: "bad_json" }, 400);
      }
      let maintBool = false;
      if ("maintenance" in body) {
        const parsed = parseBool(body.maintenance);
        if (!parsed.ok) {
          return json({ error: "bad maintenance" }, 400);
        }
        maintBool = parsed.value;
      } else {
        const prev = parseBool(cur.maintenance);
        maintBool = prev.ok ? prev.value : false;
      }

      let dlUrl: string | null = null;
      if (body.downloadUrl !== undefined && body.downloadUrl !== null && body.downloadUrl !== "") {
        if (
          typeof body.downloadUrl !== "string" ||
          body.downloadUrl.length > 1000 ||
          (!body.downloadUrl.startsWith("http://") && !body.downloadUrl.startsWith("https://"))
        ) {
          return json({ error: "bad_downloadUrl" }, 400);
        }
        dlUrl = body.downloadUrl.trim();
      }

      const nextApp = {
        latestVersion:
          typeof body.latestVersion === "string" ? clip(body.latestVersion, 40) : null,
        latestCode:
          typeof body.latestCode === "number" && Number.isInteger(body.latestCode)
            ? body.latestCode
            : null,
        downloadUrl: dlUrl,
        notes: typeof body.notes === "string" ? clip(body.notes, 2000) : "",
        minCode:
          typeof body.minCode === "number" && Number.isInteger(body.minCode)
            ? body.minCode
            : null,
        announcement:
          typeof body.announcement === "string" ? clip(body.announcement, 2000) : "",
        announcementId:
          typeof body.announcementId === "number" && Number.isInteger(body.announcementId)
            ? body.announcementId
            : Number(cur.announcementId || 0),
        maintenance: maintBool,
        maintenanceMessage:
          typeof body.maintenanceMessage === "string"
            ? clip(body.maintenanceMessage, 500)
            : "",
        supportContact:
          typeof body.supportContact === "string" ? clip(body.supportContact, 200) : "",
      };
      await cfgPut(env.DB, "app", nextApp);
      await logAdmin(
        env.DB,
        "app_config",
        `maintenance=${nextApp.maintenance}, latest=${nextApp.latestVersion || "-"}`
      );
      return json(nextApp);
    }
  }

  if (path === "/admin/settings/subs") {
    const subs = await cfgGet<{ opensubtitlesKey?: string }>(env.DB, "subs", {});
    const keyVal = subs.opensubtitlesKey || env.OS_API_KEY || "";
    if (req.method === "GET") {
      return json({
        opensubtitlesKeySet: Boolean(keyVal),
        opensubtitlesKeyHint: keyVal.length >= 4 ? `…${keyVal.slice(-4)}` : null,
      });
    }
    if (req.method === "PUT") {
      let body: { opensubtitlesKey?: unknown } = {};
      try {
        body = await req.json();
      } catch {
        return json({ error: "bad_json" }, 400);
      }
      if (typeof body.opensubtitlesKey !== "string") {
        return json({ error: "bad_key" }, 400);
      }
      const clean = body.opensubtitlesKey.trim();
      await cfgPut(env.DB, "subs", { opensubtitlesKey: clean });
      await logAdmin(env.DB, "subs_config", clean ? "Set OS key" : "Cleared OS key");
      const eff = clean || env.OS_API_KEY || "";
      return json({
        opensubtitlesKeySet: Boolean(eff),
        opensubtitlesKeyHint: eff.length >= 4 ? `…${eff.slice(-4)}` : null,
      });
    }
  }

  if (req.method === "GET" && path === "/admin/integrations") {
    const subs = await cfgGet<{ opensubtitlesKey?: string }>(env.DB, "subs", {});
    return json({
      tmdb: Boolean(env.TMDB_KEY),
      tvdb: Boolean(env.TVDB_KEY),
      opensubtitles: Boolean(subs.opensubtitlesKey || env.OS_API_KEY),
    });
  }

  if (req.method === "POST" && path === "/admin/password") {
    let body: { old?: string; new?: string } = {};
    try {
      body = await req.json();
    } catch {
      return json({ error: "bad_json" }, 400);
    }
    if (
      typeof body.old !== "string" ||
      typeof body.new !== "string" ||
      body.new.length < 8
    ) {
      return json({ error: "bad_password" }, 400);
    }
    const customHash = await cfgGet<string | null>(env.DB, "admin_pass", null);
    const activeHash = customHash || env.ADMIN_PASS_HASH;
    if (!(await verifyPbkdf2(body.old, activeHash))) {
      return json({ error: "invalid_credentials" }, 401);
    }
    const newHash = await hashPasswordPbkdf2(body.new);
    await cfgPut(env.DB, "admin_pass", newHash);
    await logAdmin(env.DB, "admin_password", "Changed admin password");
    return json({ ok: true });
  }

  if (req.method === "GET" && path === "/admin/log") {
    return json(await cfgGet(env.DB, "admin_log", []));
  }

  return json({ error: "not_found" }, 404);
}

export default {
  async fetch(req: Request, env: Env, ctx?: ExecutionContext): Promise<Response> {
    try {
      if (req.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: CORS_HEADERS });
      }

      const url = new URL(req.url);
      const path =
        url.pathname.length > 1 ? url.pathname.replace(/\/+$/, "") : url.pathname;

      await ensureSchema(env.DB);

      // 1. Public pages & health
      if (req.method === "GET" && path === "/") {
        return htmlResponse(`<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8"><title>رايق</title></head>
<body style="font-family:sans-serif;background:#0b1020;color:#eef2ff;padding:2rem;text-align:center">
  <h1>منظومة رايق (Rayeq)</h1>
  <p>مشغّل وسائط شخصي ومزامنة سحابية — <a href="/privacy" style="color:#38bdf8">سياسة الخصوصية</a></p>
</body></html>`);
      }

      if (req.method === "GET" && path === "/privacy") {
        return htmlResponse(`<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8"><title>سياسة الخصوصية — رايق</title></head>
<body style="font-family:sans-serif;background:#0b1020;color:#eef2ff;padding:2rem;max-width:720px;margin:auto;line-height:1.8">
  <h1>سياسة الخصوصية لتطبيق رايق</h1>
  <p>يستخدم تطبيق «رايق» تسجيل الدخول عبر حساب Google للتحقق من هوية المستخدم ومزامنة مكتبته ومواضع المشاهدة الخاصة به عبر أجهزته فقط.</p>
  <p>لا يشارك التطبيق أي بيانات شخصية مع أي طرف ثالث، ولا يسجّل عناوين المحتوى المشاهد في سجلات الحضور.</p>
</body></html>`);
      }

      if (req.method === "GET" && path === "/health") {
        let schemaStatus = "ok";
        try {
          await ensureSchema(env.DB);
        } catch {
          schemaStatus = "error";
        }
        const out: Record<string, unknown> = {
          ok: schemaStatus === "ok",
          name: "rayeq-api",
          v: "1.0.0",
          rev: true,
          sync: true,
          userSources: true,
          schema: schemaStatus,
          tvdb: Boolean(env.TVDB_KEY),
        };
        if (url.searchParams.get("check") === "tvdb" && env.TVDB_KEY) {
          const check = await handleTvdbProxy(
            req,
            "search",
            new URL("https://x/v1/tvdb/search?query=test"),
            env.TVDB_KEY,
            env.__customFetch
          );
          out.tvdbCheck = check.status === 200 ? "ok" : `http_${check.status}`;
        }
        return json(out);
      }

      // 2. Admin routes with single rev-bump wrapper
      if (path.startsWith("/admin/")) {
        const res = await handleAdminRoute(req, path, url, env, ctx);
        if (
          req.method !== "GET" &&
          path !== "/admin/login" &&
          res.status < 400
        ) {
          await bumpRev(env.DB);
        }
        return res;
      }

      // 3. Viewer Auth & Heartbeat & Rev
      if (req.method === "POST" && path === "/v1/auth/google") {
        let body: {
          idToken?: string;
          deviceModel?: string;
          deviceName?: string;
          deviceType?: string;
        } = {};
        try {
          body = await req.json();
        } catch {
          return json({ error: "bad_json" }, 400);
        }
        if (!body.idToken || typeof body.idToken !== "string") {
          return json({ error: "idToken required" }, 400);
        }
        const gUser = await verifyGoogleIdToken(body.idToken, env);
        if (!gUser) {
          return json({ error: "invalid_google_token" }, 401);
        }

        const now = Date.now();
        let user = await env.DB.prepare(`SELECT * FROM users WHERE google_sub = ?`)
          .bind(gUser.sub)
          .first<UserRow>();
        if (!user) {
          await env.DB.prepare(
            `INSERT INTO users (google_sub, email, name, status, locked, access_until, created_at, updated_at)
             VALUES (?, ?, ?, 'pending', 0, NULL, ?, ?)`
          )
            .bind(gUser.sub, gUser.email, gUser.name, now, now)
            .run();
          user = await env.DB.prepare(`SELECT * FROM users WHERE google_sub = ?`)
            .bind(gUser.sub)
            .first<UserRow>();
        } else {
          await env.DB.prepare(
            `UPDATE users SET email = COALESCE(?, email), name = COALESCE(?, name), updated_at = ? WHERE id = ?`
          )
            .bind(gUser.email, gUser.name, now, user.id)
            .run();
          user = await env.DB.prepare(`SELECT * FROM users WHERE id = ?`)
            .bind(user.id)
            .first<UserRow>();
        }
        if (!user) return json({ error: "server_error" }, 500);

        const rawToken = generateSessionToken();
        const tokenHash = await sha256Hex(rawToken);
        const model = clip(body.deviceModel, 120);
        const dName = clip(body.deviceName, 120);
        const dType = devType(body.deviceType);

        // كل تسجيل دخول ينشئ جهازاً جديداً بـapproved=0
        await env.DB.prepare(
          `INSERT INTO devices (user_id, model, name, type, approved, token_hash, first_seen, last_seen)
           VALUES (?, ?, ?, ?, 0, ?, ?, ?)`
        )
          .bind(user.id, model, dName, dType, tokenHash, now, now)
          .run();

        await bumpRev(env.DB);
        return json(statusPayload(user, { approved: 0 }, now, rawToken));
      }

      if (req.method === "POST" && path === "/v1/heartbeat") {
        const sess = await sessionFor(req, env);
        if (!sess) {
          return json({ error: "kicked" }, 401);
        }
        let body: { deviceType?: string } = {};
        try {
          const t = await req.text();
          if (t.trim()) body = JSON.parse(t);
        } catch {
          body = {};
        }
        const now = Date.now();
        const dt = body.deviceType ? devType(body.deviceType) : sess.device.type || "phone";
        await env.DB.prepare(
          `UPDATE devices SET last_seen = ?, type = ? WHERE id = ?`
        )
          .bind(now, dt, sess.device.id)
          .run();
        return json(statusPayload(sess.user, sess.device, now));
      }

      if (req.method === "GET" && path === "/v1/rev") {
        const tok = extractBearer(req);
        if (!tok) return json({ error: "unauthorized" }, 401);
        const tokenHash = await sha256Hex(tok);
        const since = url.searchParams.get("since") || "";
        const rawWait = Number(url.searchParams.get("wait") || "0");
        const waitSec = Number.isFinite(rawWait)
          ? Math.max(0, Math.min(20, Math.floor(rawWait)))
          : 0;

        const queryState = async () => {
          const row = await env.DB.prepare(
            `SELECT u.*, d.approved AS d_approved, (SELECT value FROM config WHERE key='rev') AS rev
             FROM devices d JOIN users u ON u.id = d.user_id WHERE d.token_hash = ?`
          )
            .bind(tokenHash)
            .first<UserRow & { d_approved: number; rev: string | null }>();
          if (!row) return null;
          const now = Date.now();
          const st = effectiveStatus(row, { approved: row.d_approved }, now);
          const n = Number(row.rev || "0");
          const accessUntil = row.access_until ?? 0;
          const lockedUntil = st === "locked" && row.locked > 1 && now < row.locked ? row.locked : 0;
          const revToken = `${n}.${st}.${accessUntil}.${lockedUntil}`;
          return {
            rev: revToken,
            n,
            status: st,
            accessUntil: row.access_until ?? null,
            lockedUntil: lockedUntil > 0 ? lockedUntil : undefined,
          };
        };

        let cur = await queryState();
        if (!cur) return json({ error: "unauthorized" }, 401);

        if (waitSec === 0) {
          const inm = (req.headers.get("If-None-Match") || "").replace(/^"|"$/g, "");
          if (inm && inm === cur.rev) {
            return new Response(null, {
              status: 304,
              headers: { ETag: `"${cur.rev}"`, ...CORS_HEADERS },
            });
          }
          const payload: Record<string, unknown> = {
            rev: cur.rev,
            n: cur.n,
            status: cur.status,
            accessUntil: cur.accessUntil,
            poll: 3000,
          };
          if (cur.lockedUntil) payload.lockedUntil = cur.lockedUntil;
          return json(payload, 200, { ETag: `"${cur.rev}"` });
        }

        const deadline = Date.now() + waitSec * 1000 - 200;
        while (since && cur.rev === since && Date.now() < deadline) {
          await new Promise((r) => setTimeout(r, 1000));
          cur = await queryState();
          if (!cur) return json({ error: "unauthorized" }, 401);
        }

        const payload: Record<string, unknown> = {
          rev: cur.rev,
          n: cur.n,
          status: cur.status,
          accessUntil: cur.accessUntil,
          poll: 3000,
        };
        if (cur.lockedUntil) payload.lockedUntil = cur.lockedUntil;
        return json(payload, 200, { ETag: `"${cur.rev}"` });
      }

      // 4. App Config (/v1/app)
      if (req.method === "GET" && path === "/v1/app") {
        const cur = await cfgGet<Record<string, unknown>>(env.DB, "app", {});
        const m = parseBool(cur.maintenance);
        return json({
          latestVersion: cur.latestVersion ?? null,
          latestCode: cur.latestCode ?? null,
          downloadUrl: cur.downloadUrl ?? null,
          notes: cur.notes ?? "",
          minCode: cur.minCode ?? null,
          announcement: cur.announcement ?? "",
          announcementId: cur.announcementId ?? 0,
          maintenance: m.ok ? m.value : false,
          maintenanceMessage: cur.maintenanceMessage ?? "",
          supportContact: cur.supportContact ?? "",
        });
      }

      // 5. Presence (/v1/presence)
      if (req.method === "POST" && path === "/v1/presence") {
        const sess = await sessionFor(req, env);
        if (!sess) return json({ error: "unauthorized" }, 401);
        let body: {
          state?: string;
          seconds_watched_delta?: number;
          screens_delta?: number;
          app_version?: string;
        } = {};
        try {
          body = await req.json();
        } catch {
          return json({ error: "bad_json" }, 400);
        }
        const now = Date.now();
        const st = effectiveStatus(sess.user, sess.device, now);
        if (st !== "approved") {
          return json({ ok: true, status: st, serverTime: now });
        }

        const state =
          body.state === "watching" || body.state === "background"
            ? body.state
            : "browsing";
        const secDelta = Math.max(
          0,
          Math.min(900, Math.floor(Number(body.seconds_watched_delta) || 0))
        );
        const scrDelta = Math.max(
          0,
          Math.min(100, Math.floor(Number(body.screens_delta) || 0))
        );
        const appVer =
          typeof body.app_version === "string"
            ? clip(body.app_version, 40)
            : clip(req.headers.get("X-Rayeq-Client") || "", 40);

        await env.DB.prepare(
          `INSERT INTO user_usage (user_id, total_watch_seconds, browse_count, last_seen_at, current_state, last_app_version, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET
             total_watch_seconds = user_usage.total_watch_seconds + excluded.total_watch_seconds,
             browse_count = user_usage.browse_count + excluded.browse_count,
             last_seen_at = excluded.last_seen_at,
             current_state = excluded.current_state,
             last_app_version = COALESCE(NULLIF(excluded.last_app_version, ''), user_usage.last_app_version),
             updated_at = excluded.updated_at`
        )
          .bind(sess.user.id, secDelta, scrDelta, now, state, appVer, now)
          .run();

        const dayStr = ownerDayStr(now);
        await env.DB.prepare(
          `INSERT INTO user_usage_daily (user_id, day, seconds, screens)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(user_id, day) DO UPDATE SET
             seconds = user_usage_daily.seconds + excluded.seconds,
             screens = user_usage_daily.screens + excluded.screens`
        )
          .bind(sess.user.id, dayStr, secDelta, scrDelta)
          .run();

        await env.DB.prepare(`UPDATE devices SET last_seen = ? WHERE id = ?`)
          .bind(now, sess.device.id)
          .run();

        return json({ ok: true, status: st, serverTime: now });
      }

      // 6. Sources & Reports (/v1/sources, /v1/sources/report) - ALWAYS require approved
      if (req.method === "GET" && path === "/v1/sources") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const cat = await getCatalog(env.DB);
        return json(await buildViewerSources(env.DB, guard.session!.user.id, cat));
      }

      if (req.method === "POST" && path === "/v1/sources/report") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        let body: {
          plugins?: Array<{
            internalName?: string;
            version?: number;
            ok?: boolean;
            error?: string | null;
          }>;
        } = {};
        try {
          body = await req.json();
        } catch {
          return json({ error: "bad_json" }, 400);
        }
        if (!Array.isArray(body.plugins) || body.plugins.length > 300) {
          return json({ error: "bad_plugins" }, 400);
        }
        const reports = await cfgGet<Record<string, PluginReport>>(
          env.DB,
          "plugin_reports",
          {}
        );
        const now = Date.now();
        for (const p of body.plugins) {
          if (!p || typeof p.internalName !== "string") continue;
          const iname = clip(p.internalName, 100);
          if (!iname) continue;
          const cur: PluginReport = reports[iname] || {
            version: 0,
            ok: 0,
            fail: 0,
            lastOk: null,
            lastFail: null,
            error: null,
          };
          if (typeof p.version === "number" && Number.isFinite(p.version)) {
            cur.version = Math.floor(p.version);
          }
          if (p.ok) {
            cur.ok = (cur.ok || 0) + 1;
            cur.lastOk = now;
            cur.error = null;
          } else {
            cur.fail = (cur.fail || 0) + 1;
            cur.lastFail = now;
            cur.error = clip(p.error, 150) || "load_failed";
          }
          reports[iname] = cur;
        }
        await cfgPut(env.DB, "plugin_reports", reports);
        return json({ ok: true });
      }

      // 7. Cloud Sync (/v1/sync, /v1/sync/progress) - ALWAYS require approved
      if (path === "/v1/sync") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const userId = guard.session!.user.id;

        if (req.method === "GET") {
          const row = await env.DB.prepare(
            `SELECT data, enc, rev, updated_at FROM sync_data WHERE user_id = ?`
          )
            .bind(userId)
            .first<{ data: string; enc: string; rev: number; updated_at: number }>();
          if (!row) {
            return json({ data: null, updatedAt: null, rev: 0 });
          }
          const decompressed = await decompressGzipB64(row.data, row.enc);
          return json({
            data: JSON.parse(decompressed),
            updatedAt: row.updated_at,
            rev: row.rev,
          });
        }

        if (req.method === "PUT") {
          const rawText = await req.text();
          if (new TextEncoder().encode(rawText).byteLength > 8 * 1024 * 1024) {
            return json({ error: "too_large", max: MAX_SYNC_RAW_BYTES }, 413);
          }
          let body: { data?: unknown; baseRev?: unknown };
          try {
            body = JSON.parse(rawText);
          } catch {
            return json({ error: "bad_json" }, 400);
          }
          if (
            !body ||
            typeof body !== "object" ||
            !body.data ||
            typeof body.data !== "object" ||
            Array.isArray(body.data)
          ) {
            return json({ error: "bad_data" }, 400);
          }
          if (
            typeof body.baseRev !== "number" ||
            !Number.isInteger(body.baseRev) ||
            body.baseRev < 0
          ) {
            return json({ error: "bad_baseRev" }, 400);
          }

          const dataStr = JSON.stringify(body.data);
          const rawBytes = new TextEncoder().encode(dataStr).byteLength;
          if (rawBytes > MAX_SYNC_RAW_BYTES) {
            return json({ error: "too_large", max: MAX_SYNC_RAW_BYTES }, 413);
          }

          const existing = await env.DB.prepare(
            `SELECT data, enc, rev, updated_at FROM sync_data WHERE user_id = ?`
          )
            .bind(userId)
            .first<{ data: string; enc: string; rev: number; updated_at: number }>();

          const currentRev = existing ? existing.rev : 0;
          if (body.baseRev !== currentRev) {
            const existingData = existing
              ? JSON.parse(await decompressGzipB64(existing.data, existing.enc))
              : null;
            return json(
              {
                error: "conflict",
                data: existingData,
                updatedAt: existing?.updated_at ?? null,
                rev: currentRev,
              },
              409
            );
          }

          const compressed = await compressGzipB64(dataStr);
          if (compressed.length > MAX_SYNC_B64_CHARS) {
            return json({ error: "too_large", max: MAX_SYNC_RAW_BYTES }, 413);
          }

          const now = Date.now();
          const nextRev = currentRev + 1;
          await env.DB.prepare(
            `INSERT INTO sync_data (user_id, data, enc, bytes, rev, updated_at)
             VALUES (?, ?, 'gzip-b64', ?, ?, ?)
             ON CONFLICT(user_id) DO UPDATE SET
               data = excluded.data,
               enc = excluded.enc,
               bytes = excluded.bytes,
               rev = excluded.rev,
               updated_at = excluded.updated_at`
          )
            .bind(userId, compressed, rawBytes, nextRev, now)
            .run();

          return json({ ok: true, rev: nextRev, updatedAt: now });
        }
      }

      if (path === "/v1/sync/progress") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const userId = guard.session!.user.id;
        const now = Date.now();

        if (req.method === "GET") {
          const since = Math.max(0, Math.floor(Number(url.searchParams.get("since") || "0") || 0));
          const rows = await env.DB.prepare(
            `SELECT key, pos_ms AS posMs, dur_ms AS durMs, updated_at AS updatedAt, title, srv_at AS srvAt
             FROM sync_progress
             WHERE user_id = ? AND srv_at >= ?
             ORDER BY srv_at ASC, key ASC
             LIMIT 1001`
          )
            .bind(userId, since)
            .all<{
              key: string;
              posMs: number;
              durMs: number;
              updatedAt: number;
              title: string | null;
              srvAt: number;
            }>();
          const all = rows.results || [];
          const more = all.length > 1000;
          const page = more ? all.slice(0, 1000) : all;
          const next = more ? page[page.length - 1].srvAt : null;
          return json({
            items: page.map((r) => ({
              key: r.key,
              posMs: r.posMs,
              durMs: r.durMs,
              updatedAt: r.updatedAt,
              title: r.title,
            })),
            serverTime: now,
            more,
            next,
          });
        }

        if (req.method === "PUT") {
          let body: { items?: unknown };
          try {
            body = await req.json();
          } catch {
            return json({ error: "bad_json" }, 400);
          }
          if (!body || !Array.isArray(body.items) || body.items.length === 0) {
            return json({ error: "bad_items" }, 400);
          }
          if (body.items.length > 200) {
            return json({ error: "too_many_items", max: 200 }, 413);
          }

          let received = 0;
          let skipped = 0;
          const maxFuture = now + 5 * 60 * 1000;

          for (const raw of body.items) {
            if (!raw || typeof raw !== "object") {
              skipped++;
              continue;
            }
            const it = raw as {
              key?: unknown;
              posMs?: unknown;
              durMs?: unknown;
              updatedAt?: unknown;
              title?: unknown;
            };
            if (typeof it.key !== "string" || !PROGRESS_KEY_RE.test(it.key)) {
              skipped++;
              continue;
            }
            const posMs = Math.max(0, Math.floor(Number(it.posMs) || 0));
            const durMs = Math.max(0, Math.floor(Number(it.durMs) || 0));
            let upd = Math.max(0, Math.floor(Number(it.updatedAt) || now));
            if (upd > maxFuture) upd = now;
            const title = typeof it.title === "string" ? clip(it.title, 300) : null;

            const existing = await env.DB.prepare(
              `SELECT updated_at FROM sync_progress WHERE user_id = ? AND key = ?`
            )
              .bind(userId, it.key)
              .first<{ updated_at: number }>();

            if (existing && existing.updated_at >= upd) {
              skipped++;
              continue;
            }

            await env.DB.prepare(
              `INSERT INTO sync_progress (user_id, key, pos_ms, dur_ms, updated_at, title, srv_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(user_id, key) DO UPDATE SET
                 pos_ms = excluded.pos_ms,
                 dur_ms = excluded.dur_ms,
                 updated_at = excluded.updated_at,
                 title = excluded.title,
                 srv_at = excluded.srv_at
               WHERE excluded.updated_at > sync_progress.updated_at`
            )
              .bind(userId, it.key, posMs, durMs, upd, title, now)
              .run();
            received++;
          }

          // الاحتفاظ بأحدث 5000 عنصر لكل مستخدم
          await env.DB.prepare(
            `DELETE FROM sync_progress
             WHERE user_id = ? AND key NOT IN (
               SELECT key FROM sync_progress WHERE user_id = ? ORDER BY updated_at DESC LIMIT 5000
             )`
          )
            .bind(userId, userId)
            .run();

          return json({ ok: true, received, skipped, serverTime: now });
        }
      }

      // 8. TMDB Proxy (/v1/tmdb/*)
      if (req.method === "GET" && path.startsWith("/v1/tmdb/")) {
        const guard = await requireApproved(req, env, false);
        if (!guard.ok) return guard.res;
        if (!env.TMDB_KEY) return json({ error: "tmdb_not_configured" }, 503);

        const subPath = path.slice("/v1/tmdb/".length).replace(/^\/+|\/+$/g, "");
        if (!isTmdbPathAllowed(subPath)) {
          return json({ error: "path not allowed" }, 404);
        }

        const params = new URLSearchParams(url.search);
        params.delete("api_key");
        const sortedEntries = Array.from(params.entries()).sort(([a], [b]) =>
          a.localeCompare(b)
        );
        const canonicalSearch = new URLSearchParams(sortedEntries).toString();
        const cacheUrl = `https://tmdb-cache.rayeq.local/${subPath}${
          canonicalSearch ? `?${canonicalSearch}` : ""
        }`;
        const cache: Cache | null =
          typeof caches !== "undefined" && (caches as unknown as { default?: Cache }).default
            ? (caches as unknown as { default: Cache }).default
            : null;

        if (cache) {
          const hit = await cache.match(new Request(cacheUrl));
          if (hit) {
            const bodyText = await hit.text();
            return new Response(bodyText, {
              status: hit.status,
              headers: {
                "Content-Type": "application/json; charset=utf-8",
                "Cache-Control": "private, max-age=600",
                ...CORS_HEADERS,
              },
            });
          }
        }

        const upstreamParams = new URLSearchParams(sortedEntries);
        upstreamParams.set("api_key", env.TMDB_KEY);
        const upstreamUrl = `https://api.themoviedb.org/3/${subPath}?${upstreamParams.toString()}`;
        const f = env.__customFetch || fetch;
        const upstreamRes = await f(upstreamUrl, {
          headers: { "Accept": "application/json" },
        });
        const upstreamBody = await upstreamRes.text();

        if (upstreamRes.ok && cache) {
          const ttl = isTmdbListPath(subPath) ? 6 * 3600 : 24 * 3600;
          const putPromise = cache.put(
            new Request(cacheUrl),
            new Response(upstreamBody, {
              status: upstreamRes.status,
              headers: {
                "Content-Type": "application/json; charset=utf-8",
                "Cache-Control": `public, max-age=${ttl}`,
              },
            })
          );
          if (ctx?.waitUntil) ctx.waitUntil(putPromise);
          else await putPromise;
        }

        return new Response(upstreamBody, {
          status: upstreamRes.status,
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": upstreamRes.ok ? "private, max-age=600" : "no-store",
            ...CORS_HEADERS,
          },
        });
      }

      // 9. Smart Search (/v1/smart-search)
      if (req.method === "GET" && path === "/v1/smart-search") {
        const smartTestHeader = req.headers.get("X-Smart-Test") || "";
        const smartBypass =
          Boolean(env.SMART_TEST) && smartTestHeader === env.SMART_TEST;
        if (!smartBypass) {
          const guard = await requireApproved(req, env, true);
          if (!guard.ok) return guard.res;
        }
        const q = (url.searchParams.get("q") || "").trim();
        if (q.length < 2) {
          return json({ error: "query_too_short" }, 400);
        }
        if (!env.TMDB_KEY) return json({ error: "tmdb_not_configured" }, 503);
        const rawType = url.searchParams.get("type") || "multi";
        const typeFilter: "tv" | "movie" | "multi" =
          rawType === "tv" || rawType === "movie" ? rawType : "multi";

        const result = await runSmartSearch(
          q,
          typeFilter,
          env.TMDB_KEY,
          env.AI,
          env.__customFetch
        );
        return json(result, 200, { "Cache-Control": "private, max-age=604800" });
      }

      // 10. Subtitles config & OpenSubtitles proxy & free subs
      if (req.method === "GET" && path === "/v1/subs/config") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const subs = await cfgGet<{ opensubtitlesKey?: string }>(env.DB, "subs", {});
        const configured = Boolean(subs.opensubtitlesKey || env.OS_API_KEY);
        return json({
          opensubtitles: { configured, proxy: "/v1/subs/os" },
          subdl: { login: false, needs: "api_key" },
          subsource: { login: false, needs: "api_key" },
        });
      }

      if (path.startsWith("/v1/subs/os/")) {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const subs = await cfgGet<{ opensubtitlesKey?: string }>(env.DB, "subs", {});
        const osKey = subs.opensubtitlesKey || env.OS_API_KEY;
        const subPath = path.slice("/v1/subs/os/".length);
        const out = await handleOpenSubtitlesProxy(
          req,
          subPath,
          url,
          osKey,
          env.__customFetch
        );
        return json(out.body, out.status);
      }

      if (req.method === "GET" && path === "/v1/subs/free") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        return json({ source: "none", count: 0, subtitles: [] });
      }

      // 11. TheTVDB Proxy (/v1/tvdb/*)
      if (req.method === "GET" && path.startsWith("/v1/tvdb/")) {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const subPath = path.slice("/v1/tvdb/".length);
        const out = await handleTvdbProxy(
          req,
          subPath,
          url,
          env.TVDB_KEY,
          env.__customFetch
        );
        return json(out.body, out.status, out.headers);
      }

      // 12. Skip segments (/v1/skip)
      if (req.method === "GET" && path === "/v1/skip") {
        const guard = await requireApproved(req, env, true);
        if (!guard.ok) return guard.res;
        const out = await handleSkipSegments(url, env.__customFetch);
        return json(out);
      }

      return json({ error: "not_found" }, 404);
    } catch (e) {
      console.error("Unhandled worker error:", e);
      return json({ error: "server_error" }, 500);
    }
  },
};
