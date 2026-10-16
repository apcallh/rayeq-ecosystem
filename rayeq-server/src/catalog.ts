export interface RepoEntry {
  url: string;
  name: string;
  description: string;
  iconUrl: string | null;
  addedAt: number;
  lastFetched: number | null;
  error: string | null;
}

export interface ServerEntry {
  repo: string;
  internalName: string;
  name: string;
  version: number;
  enabled: boolean;
  addedAt: number;
  iconUrl?: string | null;
  language?: string | null;
  arabic?: boolean | null;
  kind?: "auto" | "live" | "content" | null;
  tvTypes?: string[];
}

export interface PluginMeta {
  internalName: string;
  name: string;
  version: number;
  status: number;
  language: string;
  tvTypes: string[];
  iconUrl: string | null;
  description: string;
  authors: string[];
  fileSize: number | null;
  url: string;
}

export interface PluginReport {
  version: number;
  ok: number;
  fail: number;
  lastOk: number | null;
  lastFail: number | null;
  error: string | null;
}

export interface CatalogData {
  repos: RepoEntry[];
  servers: ServerEntry[];
}

export class CatalogError extends Error {
  status: number;
  code: string;
  constructor(code: string, status: number) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

export const LIVE_EXCLUDE = new Set([
  "anime",
  "animemovie",
  "ova",
  "asiandrama",
  "drama",
  "cartoon",
  "music",
  "documentary",
  "torrent",
]);

export function clip(v: unknown, max: number): string {
  if (typeof v !== "string") return "";
  return v.trim().slice(0, max);
}

export function normalizeRepoUrl(raw: unknown): string {
  if (typeof raw !== "string") throw new CatalogError("bad_url", 400);
  let s = raw.trim();
  if (!s || s.length > 1000) throw new CatalogError("bad_url", 400);
  if (s.toLowerCase().startsWith("cloudstreamrepo://")) {
    s = "https://" + s.slice("cloudstreamrepo://".length);
  }
  let u: URL;
  try {
    u = new URL(s);
  } catch {
    throw new CatalogError("bad_url", 400);
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") {
    throw new CatalogError("bad_url", 400);
  }
  const host = u.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (
    !host ||
    host === "localhost" ||
    host === "::1" ||
    host === "0.0.0.0" ||
    host.endsWith(".internal") ||
    host.endsWith(".local") ||
    /^127\./.test(host) ||
    /^10\./.test(host) ||
    /^192\.168\./.test(host) ||
    /^169\.254\./.test(host) ||
    /^0\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[0-1])\./.test(host)
  ) {
    throw new CatalogError("bad_url", 400);
  }
  return u.toString();
}

export async function fetchJson(
  url: string,
  fresh = false,
  ctx?: { waitUntil?: (p: Promise<unknown>) => void },
  customFetch?: typeof fetch
): Promise<unknown> {
  const validUrl = normalizeRepoUrl(url);
  const cache: Cache | null = typeof caches !== "undefined" && (caches as unknown as { default?: Cache }).default
    ? (caches as unknown as { default: Cache }).default
    : null;
  const cacheKey = new Request(validUrl, { method: "GET" });

  if (!fresh && cache) {
    const hit = await cache.match(cacheKey);
    if (hit) {
      try {
        return await hit.json();
      } catch {
        // ignore corrupt cache entry
      }
    }
  }

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 12000);
  let res: Response;
  try {
    const f = customFetch || fetch;
    res = await f(validUrl, {
      method: "GET",
      headers: {
        "Accept": "application/json",
        "User-Agent": "rayeq-api",
      },
      signal: ctrl.signal,
    });
  } catch {
    clearTimeout(timer);
    throw new CatalogError("fetch_failed", 502);
  }
  clearTimeout(timer);

  if (!res.ok) {
    throw new CatalogError(`http_${res.status}`, 502);
  }

  const cl = Number(res.headers.get("content-length") || "0");
  if (cl > 3 * 1024 * 1024) {
    throw new CatalogError("too_large", 502);
  }

  let text: string;
  try {
    text = await res.text();
  } catch {
    throw new CatalogError("fetch_failed", 502);
  }
  if (new TextEncoder().encode(text).byteLength > 3 * 1024 * 1024) {
    throw new CatalogError("too_large", 502);
  }

  // Strip BOM
  if (text.charCodeAt(0) === 0xfeff) {
    text = text.slice(1);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new CatalogError("not_json", 422);
  }

  if (cache) {
    const cachedResp = new Response(JSON.stringify(parsed), {
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "public, max-age=600",
      },
    });
    const putPromise = cache.put(cacheKey, cachedResp);
    if (ctx?.waitUntil) ctx.waitUntil(putPromise);
    else await putPromise;
  }

  return parsed;
}

export function normalizePluginMeta(raw: unknown): PluginMeta | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  const internalName = clip(r.internalName, 100);
  if (!internalName) return null;
  const rawUrl = typeof r.url === "string" ? r.url.trim().replace(/ /g, "%20") : "";
  if (!rawUrl || (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))) {
    return null;
  }
  const name = clip(r.name, 100) || internalName;
  const version = typeof r.version === "number" && Number.isFinite(r.version) ? Math.floor(r.version) : 1;
  const status = typeof r.status === "number" && Number.isFinite(r.status) ? Math.floor(r.status) : 1;
  const language = clip(r.language, 20);
  const rawTypes = Array.isArray(r.tvTypes) ? r.tvTypes : [];
  const tvTypes: string[] = [];
  for (const t of rawTypes) {
    if (typeof t === "string") {
      for (const part of t.split(",")) {
        const clean = part.trim();
        if (clean && tvTypes.length < 12) tvTypes.push(clean);
      }
    }
  }
  const rawIcon = typeof r.iconUrl === "string" ? r.iconUrl.trim().replace(/%size%/gi, "128") : "";
  const iconUrl = rawIcon && (rawIcon.startsWith("http://") || rawIcon.startsWith("https://")) ? rawIcon : null;
  const description = clip(r.description, 400);
  const authors = Array.isArray(r.authors)
    ? r.authors.filter((a): a is string => typeof a === "string").map((a) => clip(a, 60)).filter(Boolean).slice(0, 6)
    : [];
  const fileSize = typeof r.fileSize === "number" && Number.isFinite(r.fileSize) ? Math.floor(r.fileSize) : null;

  return {
    internalName,
    name,
    version,
    status,
    language,
    tvTypes,
    iconUrl,
    description,
    authors,
    fileSize,
    url: rawUrl,
  };
}

export async function readRepo(
  rawUrl: string,
  fresh = false,
  ctx?: { waitUntil?: (p: Promise<unknown>) => void },
  customFetch?: typeof fetch
): Promise<{
  repo: { url: string; name: string; description: string; iconUrl: string | null };
  plugins: PluginMeta[];
  listErrors: { url: string; error: string }[];
}> {
  const url = normalizeRepoUrl(rawUrl);
  const root = await fetchJson(url, fresh, ctx, customFetch);

  let repoName = "";
  let repoDesc = "";
  let repoIcon: string | null = null;
  let rawPlugins: unknown[] = [];
  const listErrors: { url: string; error: string }[] = [];

  if (Array.isArray(root)) {
    repoName = new URL(url).hostname;
    rawPlugins = root;
  } else if (root && typeof root === "object") {
    const obj = root as Record<string, unknown>;
    repoName = clip(obj.name, 120) || new URL(url).hostname;
    repoDesc = clip(obj.description, 400);
    const ic = typeof obj.iconUrl === "string" ? obj.iconUrl.trim().replace(/%size%/gi, "128") : "";
    repoIcon = ic && (ic.startsWith("http://") || ic.startsWith("https://")) ? ic : null;

    if (Array.isArray(obj.pluginLists) && obj.pluginLists.length > 0) {
      const lists = obj.pluginLists
        .filter((u): u is string => typeof u === "string" && u.trim().length > 0)
        .slice(0, 15);
      if (lists.length === 0) {
        throw new CatalogError("not_a_repo", 422);
      }
      let okCount = 0;
      for (const listUrl of lists) {
        try {
          const listJson = await fetchJson(listUrl, fresh, ctx, customFetch);
          if (Array.isArray(listJson)) {
            rawPlugins.push(...listJson);
            okCount++;
          } else {
            listErrors.push({ url: listUrl, error: "not_plugin_list" });
          }
        } catch (e) {
          const code = e instanceof CatalogError ? e.code : "fetch_failed";
          listErrors.push({ url: listUrl, error: code });
        }
      }
      if (okCount === 0) {
        throw new CatalogError(listErrors[0]?.error || "fetch_failed", 502);
      }
    } else if (Array.isArray(obj.plugins)) {
      rawPlugins = obj.plugins;
    } else {
      throw new CatalogError("not_a_repo", 422);
    }
  } else {
    throw new CatalogError("not_a_repo", 422);
  }

  const seen = new Set<string>();
  const plugins: PluginMeta[] = [];
  for (const item of rawPlugins) {
    const meta = normalizePluginMeta(item);
    if (!meta) continue;
    if (seen.has(meta.internalName)) continue;
    seen.add(meta.internalName);
    plugins.push(meta);
    if (plugins.length >= 1000) break;
  }

  plugins.sort((a, b) => a.name.localeCompare(b.name, "en", { sensitivity: "base" }));

  return {
    repo: { url, name: repoName, description: repoDesc, iconUrl: repoIcon },
    plugins,
    listErrors,
  };
}

export function isArabicLanguageCode(lang?: string | null): boolean {
  if (!lang) return false;
  const s = lang.trim().toLowerCase();
  return (
    s === "ar" ||
    s.startsWith("ar-") ||
    s.startsWith("ar_") ||
    s === "ara" ||
    s === "arabic" ||
    s.includes("العربية")
  );
}

export function langOf(s: { arabic?: boolean | null; language?: string | null }): {
  lang: "ar" | "other";
  arabic: boolean;
  langSource: "admin" | "plugin";
} {
  if (typeof s.arabic === "boolean") {
    return {
      lang: s.arabic ? "ar" : "other",
      arabic: s.arabic,
      langSource: "admin",
    };
  }
  const ar = isArabicLanguageCode(s.language);
  return {
    lang: ar ? "ar" : "other",
    arabic: ar,
    langSource: "plugin",
  };
}

export function typesOf(tvTypes?: string[] | null): string[] {
  if (!Array.isArray(tvTypes)) return [];
  const out: string[] = [];
  for (const raw of tvTypes) {
    if (typeof raw !== "string") continue;
    for (const part of raw.split(",")) {
      const t = part.trim().toLowerCase();
      if (t && !out.includes(t)) out.push(t);
    }
  }
  return out;
}

export function isLiveTypes(tvTypes?: string[] | null): boolean {
  const types = typesOf(tvTypes);
  if (!types.includes("live")) return false;
  for (const t of types) {
    if (LIVE_EXCLUDE.has(t)) return false;
  }
  return true;
}

export function isTool(s: {
  internalName?: string;
  name?: string;
  kind?: string | null;
  tvTypes?: string[] | null;
}): boolean {
  if (s.kind === "live") return false;
  const iname = (s.internalName || "").trim();
  const name = (s.name || "").trim();
  if (
    iname === "Rate" ||
    /^extension\s*rating$/i.test(name) ||
    name.includes("تقييم الإضافات")
  ) {
    return true;
  }
  const types = typesOf(s.tvTypes);
  return types.length > 0 && types.every((t) => t === "others");
}

export function categoryOf(s: {
  internalName?: string;
  name?: string;
  language?: string | null;
  kind?: string | null;
  tvTypes?: string[] | null;
}): string {
  const kind = s.kind === "live" || s.kind === "content" ? s.kind : "auto";
  const live = kind === "live" ? true : kind === "content" ? false : isLiveTypes(s.tvTypes);
  const tool = isTool({ ...s, kind });

  if (live) return "بث مباشر";
  if (tool) return "أدوات";

  const types = typesOf(s.tvTypes);
  const name = (s.name || s.internalName || "").toLowerCase();
  const lang = (s.language || "").trim().toLowerCase();

  if (types.includes("torrent")) return "تورنت";
  if (types.length === 0 || types.includes("all")) return "عام";

  if (/toon|cartoon/i.test(name) || (types.length > 0 && types.every((t) => t === "cartoon"))) {
    return "كرتون";
  }

  const hasMovie = types.includes("movie") || types.includes("movies");
  // القيمة الحرفية الشاذة "tvtypes" بجانب Movie تُحسب TvSeries
  const hasTv =
    types.includes("tvseries") ||
    types.includes("tv") ||
    (hasMovie && types.includes("tvtypes"));
  const hasAnime =
    types.includes("anime") || types.includes("animemovie") || types.includes("ova");

  if (/anim|donghua/i.test(name) || (hasAnime && !(hasMovie && hasTv))) {
    return "أنمي";
  }

  const hasAsian = types.includes("asiandrama") || types.includes("drama");
  if (
    hasAsian &&
    (!(hasMovie && hasTv) ||
      /asia|korea|drama|آسي|كوري|دراما/i.test(name) ||
      /^(ko|zh|ja|th)\b/.test(lang))
  ) {
    return "دراما آسيوية";
  }

  if ((types.includes("music") || types.includes("documentary")) && (hasMovie || hasTv)) {
    return "عام";
  }

  if (hasMovie && hasTv) return "أفلام ومسلسلات";
  if (hasMovie) return "أفلام";
  if (hasTv) return "مسلسلات";
  return "عام";
}

export function kindFields(s: {
  internalName?: string;
  name?: string;
  language?: string | null;
  kind?: "auto" | "live" | "content" | null;
  tvTypes?: string[] | null;
}) {
  const kind: "auto" | "live" | "content" =
    s.kind === "live" || s.kind === "content" ? s.kind : "auto";
  const live = kind === "live" ? true : kind === "content" ? false : isLiveTypes(s.tvTypes);
  const tool = isTool({ ...s, kind });
  const tvTypes = Array.isArray(s.tvTypes) ? s.tvTypes : [];
  const category = categoryOf({ ...s, kind, tvTypes });
  return { kind, live, tool, tvTypes, category };
}

export function pluginStatus(
  meta: PluginMeta | undefined | null,
  server: ServerEntry | undefined | null,
  report: PluginReport | undefined | null
): "available" | "disabled" | "update" | "failed" | "installed" {
  if (!server) return "available";
  if (!server.enabled) return "disabled";
  if (report && report.fail > 0 && (report.lastFail || 0) > (report.lastOk || 0)) {
    return "failed";
  }
  if (meta && server.version > 0 && meta.version > server.version) {
    return "update";
  }
  return "installed";
}

export function adminServerItems(
  servers: ServerEntry[],
  reports: Record<string, PluginReport> = {}
) {
  let contentCounter = 0;
  return servers.map((s, idx) => {
    const l = langOf(s);
    const kf = kindFields(s);
    let label: string;
    if (kf.tool) {
      label = "أداة";
    } else {
      contentCounter++;
      label = `سيرفر ${contentCounter}`;
    }
    return {
      repo: s.repo,
      internalName: s.internalName,
      name: s.name || s.internalName,
      version: s.version || 0,
      enabled: Boolean(s.enabled),
      addedAt: s.addedAt || 0,
      iconUrl: s.iconUrl || null,
      language: s.language || "",
      lang: l.lang,
      arabic: l.arabic,
      langSource: l.langSource,
      ...kf,
      position: idx + 1,
      label,
      report: reports[s.internalName] || null,
    };
  });
}
