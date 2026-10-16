export interface SkipSegment {
  type: "intro" | "recap" | "outro" | "preview";
  start_ms: number;
  end_ms: number | null;
  source: string;
}

let cachedTvdbToken: { token: string; exp: number } | null = null;

export async function handleTvdbProxy(
  req: Request,
  path: string,
  url: URL,
  tvdbKey?: string,
  customFetch?: typeof fetch
): Promise<{ status: number; body: unknown; headers?: Record<string, string> }> {
  if (!tvdbKey) {
    return { status: 503, body: { error: "tvdb_not_configured" } };
  }
  const clean = path.replace(/^\/+|\/+$/g, "");
  const allowed =
    /^search$/.test(clean) ||
    /^series\/\d+$/.test(clean) ||
    /^series\/\d+\/extended$/.test(clean) ||
    /^series\/\d+\/episodes\/(default|official|absolute|dvd|alternate|regional)(\/[a-zA-Z-]+)?$/.test(clean) ||
    /^search\/remoteid\/[A-Za-z0-9_-]+$/.test(clean) ||
    /^episodes\/\d+$/.test(clean) ||
    /^seasons\/\d+\/extended$/.test(clean);

  if (!allowed) {
    return { status: 404, body: { error: "path not allowed" } };
  }

  const f = customFetch || fetch;
  const now = Date.now();
  if (!cachedTvdbToken || now >= cachedTvdbToken.exp) {
    try {
      const loginRes = await f("https://api4.thetvdb.com/v4/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Accept": "application/json" },
        body: JSON.stringify({ apikey: tvdbKey }),
      });
      if (!loginRes.ok) {
        return { status: 502, body: { error: "tvdb_login_failed" } };
      }
      const loginJson = (await loginRes.json()) as { data?: { token?: string } };
      const tok = loginJson?.data?.token;
      if (!tok) return { status: 502, body: { error: "tvdb_login_failed" } };
      cachedTvdbToken = { token: tok, exp: now + 12 * 3600 * 1000 };
    } catch {
      return { status: 502, body: { error: "tvdb_login_failed" } };
    }
  }

  const targetUrl = `https://api4.thetvdb.com/v4/${clean}${url.search}`;
  try {
    const res = await f(targetUrl, {
      headers: {
        "Authorization": `Bearer ${cachedTvdbToken.token}`,
        "Accept": "application/json",
      },
    });
    const data = await res.json();
    return {
      status: res.status,
      body: data,
      headers: res.ok ? { "Cache-Control": "private, max-age=43200" } : undefined,
    };
  } catch {
    return { status: 502, body: { error: "tvdb_upstream_error" } };
  }
}

export async function handleOpenSubtitlesProxy(
  req: Request,
  subPath: string,
  url: URL,
  osKey?: string,
  customFetch?: typeof fetch
): Promise<{ status: number; body: unknown }> {
  if (!osKey) {
    return { status: 503, body: { error: "os_not_configured" } };
  }
  const clean = subPath.replace(/^\/+|\/+$/g, "");
  const allowed =
    (req.method === "POST" && clean === "login") ||
    (req.method === "DELETE" && clean === "logout") ||
    (req.method === "GET" && clean === "subtitles") ||
    (req.method === "POST" && clean === "download") ||
    (req.method === "GET" && /^infos\/(user|formats|languages)$/.test(clean));

  if (!allowed) {
    return { status: 404, body: { error: "path not allowed" } };
  }

  const customBase = req.headers.get("X-OS-Base")?.trim() || "";
  let host = "api.opensubtitles.com";
  if (customBase) {
    if (!/^[a-z0-9-]+\.opensubtitles\.com$/i.test(customBase)) {
      return { status: 400, body: { error: "bad_os_base" } };
    }
    host = customBase.toLowerCase();
  }

  const headers: Record<string, string> = {
    "Api-Key": osKey,
    "User-Agent": req.headers.get("X-Rayeq-Client")
      ? `Rayeq ${req.headers.get("X-Rayeq-Client")}`
      : "Rayeq v1.0",
    "Accept": "application/json",
  };
  const userTok = req.headers.get("X-OS-Token")?.trim();
  if (userTok) {
    headers["Authorization"] = `Bearer ${userTok}`;
  }
  let bodyText: string | undefined;
  if (req.method === "POST") {
    headers["Content-Type"] = "application/json";
    bodyText = await req.text();
  }

  const f = customFetch || fetch;
  try {
    const res = await f(`https://${host}/api/v1/${clean}${url.search}`, {
      method: req.method,
      headers,
      body: bodyText,
    });
    const data = await res.json().catch(() => ({ error: "upstream_non_json" }));
    return { status: res.status, body: data };
  } catch {
    return { status: 502, body: { error: "os_upstream_error" } };
  }
}

export async function handleSkipSegments(
  url: URL,
  customFetch?: typeof fetch
): Promise<{
  segments: SkipSegment[];
  sources: Record<string, "ok" | "none" | "error">;
}> {
  const tmdb = url.searchParams.get("tmdb")?.trim() || "";
  const imdb = url.searchParams.get("imdb")?.trim() || "";
  const season = Number(url.searchParams.get("season") || "0");
  const episode = Number(url.searchParams.get("episode") || "0");
  const durationMs = Number(url.searchParams.get("duration_ms") || "0");
  const animeMal = url.searchParams.get("anime_mal")?.trim() || "";
  const abs = Number(url.searchParams.get("abs") || "0") || episode;

  const byType = new Map<SkipSegment["type"], SkipSegment>();
  const sources: Record<string, "ok" | "none" | "error"> = {};
  const f = customFetch || fetch;

  const addSeg = (
    type: SkipSegment["type"],
    startMs: number,
    endMs: number | null,
    source: string
  ) => {
    if (byType.has(type)) return;
    if (!Number.isFinite(startMs) || startMs < 0) return;
    if (endMs !== null && (!Number.isFinite(endMs) || endMs <= startMs)) return;
    if (durationMs > 0 && startMs >= durationMs) return;
    byType.set(type, {
      type,
      start_ms: Math.round(startMs),
      end_ms: endMs !== null ? Math.round(endMs) : null,
      source,
    });
  };

  const queryAniSkip = async () => {
    if (!animeMal || !abs) return;
    try {
      const lenSec = durationMs > 0 ? Math.round(durationMs / 1000) : 0;
      const u = `https://api.aniskip.com/v2/skip-times/${encodeURIComponent(
        animeMal
      )}/${abs}?types[]=op&types[]=ed&types[]=recap&episodeLength=${lenSec}`;
      const res = await f(u, { headers: { "Accept": "application/json" } });
      if (res.status === 404) {
        sources.aniskip = "none";
        return;
      }
      if (!res.ok) {
        sources.aniskip = "error";
        return;
      }
      const data = (await res.json()) as {
        found?: boolean;
        results?: Array<{
          skipType?: string;
          interval?: { startTime?: number; endTime?: number };
        }>;
      };
      if (!data?.found || !Array.isArray(data.results) || data.results.length === 0) {
        sources.aniskip = "none";
        return;
      }
      let added = false;
      for (const r of data.results) {
        const st = (r.interval?.startTime ?? -1) * 1000;
        const et = (r.interval?.endTime ?? -1) * 1000;
        if (r.skipType === "op") {
          addSeg("intro", st, et, "aniskip");
          added = true;
        } else if (r.skipType === "ed") {
          addSeg("outro", st, et, "aniskip");
          added = true;
        } else if (r.skipType === "recap") {
          addSeg("recap", st, et, "aniskip");
          added = true;
        }
      }
      sources.aniskip = added ? "ok" : "none";
    } catch {
      sources.aniskip = "error";
    }
  };

  const queryTheIntroDb = async () => {
    if (!tmdb) return;
    try {
      const q =
        season > 0 && episode > 0
          ? `tmdb_id=${encodeURIComponent(tmdb)}&season=${season}&episode=${episode}`
          : `tmdb_id=${encodeURIComponent(tmdb)}`;
      const res = await f(`https://api.theintrodb.org/v1/media?${q}`, {
        headers: { "Accept": "application/json" },
      });
      if (res.status === 404) {
        sources.theintrodb = "none";
        return;
      }
      if (!res.ok) {
        sources.theintrodb = "error";
        return;
      }
      const data = (await res.json()) as Record<string, unknown>;
      let added = false;
      for (const k of ["intro", "recap", "outro", "preview"] as const) {
        const seg = data?.[k] as { start_ms?: number; end_ms?: number | null } | undefined;
        if (seg && typeof seg.start_ms === "number") {
          addSeg(k, seg.start_ms, typeof seg.end_ms === "number" ? seg.end_ms : null, "theintrodb");
          added = true;
        }
      }
      sources.theintrodb = added ? "ok" : "none";
    } catch {
      sources.theintrodb = "error";
    }
  };

  const queryIntroDb = async () => {
    if (!imdb || season <= 0 || episode <= 0) return;
    try {
      const res = await f(
        `https://api.introdb.app/segments?imdb_id=${encodeURIComponent(imdb)}&season=${season}&episode=${episode}`,
        { headers: { "Accept": "application/json" } }
      );
      if (res.status === 404) {
        sources.introdb = "none";
        return;
      }
      if (!res.ok) {
        sources.introdb = "error";
        return;
      }
      const data = (await res.json()) as Record<string, { start_ms?: number; end_ms?: number }>;
      let added = false;
      for (const k of ["intro", "recap", "outro"] as const) {
        const seg = data?.[k];
        if (seg && typeof seg.start_ms === "number") {
          addSeg(k, seg.start_ms, typeof seg.end_ms === "number" ? seg.end_ms : null, "introdb");
          added = true;
        }
      }
      sources.introdb = added ? "ok" : "none";
    } catch {
      sources.introdb = "error";
    }
  };

  if (animeMal) {
    await queryAniSkip();
    await queryTheIntroDb();
    await queryIntroDb();
  } else {
    await queryTheIntroDb();
    await queryIntroDb();
  }

  return {
    segments: Array.from(byType.values()),
    sources,
  };
}
