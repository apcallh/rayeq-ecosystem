export function normalizeArabic(text: string): string {
  return text
    .replace(/[\u064B-\u065F\u0670\u0640]/g, "") // حذف التشكيل والتطويل
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ى/g, "ي")
    .replace(/ة/g, "ه")
    .replace(/ؤ/g, "و")
    .replace(/ئ/g, "ي")
    .replace(/\s+/g, " ")
    .trim();
}

export function arabicSpellingVariants(q: string): string[] {
  const norm = normalizeArabic(q);
  const out = new Set<string>([q.trim(), norm]);
  if (norm.endsWith("ه")) {
    out.add(norm.slice(0, -1) + "ة");
  }
  if (norm.endsWith("ي")) {
    out.add(norm.slice(0, -1) + "ى");
  }
  return Array.from(out).filter((s) => s.length >= 2);
}

export interface SmartSearchResultItem {
  media_type: "tv" | "movie";
  id: number;
  title: string;
  original_title: string;
  overview: string;
  poster_path: string | null;
  backdrop_path: string | null;
  year: number | null;
  vote_average: number;
  popularity: number;
  score: number;
  via: "text" | "person" | "ai";
}

export async function runSmartSearch(
  rawQuery: string,
  typeFilter: "tv" | "movie" | "multi",
  tmdbKey: string,
  aiBinding?: { run: (model: string, input: unknown) => Promise<unknown> },
  customFetch?: typeof fetch
): Promise<{
  query: string;
  ai: "used" | "skipped" | "failed";
  candidates: string[];
  results: SmartSearchResultItem[];
}> {
  const q = rawQuery.trim();
  const variants = arabicSpellingVariants(q);
  const f = customFetch || fetch;

  const tmdbSearch = async (path: string, queryText: string, lang: string) => {
    const u = `https://api.themoviedb.org/3/${path}?api_key=${encodeURIComponent(
      tmdbKey
    )}&query=${encodeURIComponent(queryText)}&language=${lang}&include_adult=false`;
    try {
      const r = await f(u, { headers: { "Accept": "application/json" } });
      if (!r.ok) return [];
      const j = (await r.json()) as { results?: Record<string, unknown>[] };
      return Array.isArray(j.results) ? j.results : [];
    } catch {
      return [];
    }
  };

  const byKey = new Map<string, SmartSearchResultItem>();
  const normQ = normalizeArabic(q).toLowerCase();

  const ingestItem = (raw: Record<string, unknown>, via: "text" | "person" | "ai") => {
    const mt =
      raw.media_type === "tv" || raw.media_type === "movie"
        ? (raw.media_type as "tv" | "movie")
        : typeFilter === "tv" || typeFilter === "movie"
        ? typeFilter
        : raw.first_air_date !== undefined || raw.name !== undefined
        ? "tv"
        : "movie";
    if (typeFilter !== "multi" && mt !== typeFilter) return;
    const id = typeof raw.id === "number" ? raw.id : 0;
    if (!id) return;

    const title = String(raw.title || raw.name || "");
    const originalTitle = String(raw.original_title || raw.original_name || title);
    const overview = String(raw.overview || "");
    const dateStr = String(raw.release_date || raw.first_air_date || "");
    const year = /^\d{4}/.test(dateStr) ? Number(dateStr.slice(0, 4)) : null;
    const voteAverage = typeof raw.vote_average === "number" ? raw.vote_average : 0;
    const popularity = typeof raw.popularity === "number" ? raw.popularity : 0;

    const normTitle = normalizeArabic(title).toLowerCase();
    const normOrig = normalizeArabic(originalTitle).toLowerCase();
    let score = via === "text" ? 100 : via === "person" ? 80 : 65;
    if (normTitle === normQ || normOrig === normQ) score += 50;
    else if (normTitle.includes(normQ) || normOrig.includes(normQ)) score += 25;
    score += Math.min(20, popularity / 10) + voteAverage * 1.5;

    const k = `${mt}:${id}`;
    const existing = byKey.get(k);
    if (!existing || score > existing.score) {
      byKey.set(k, {
        media_type: mt,
        id,
        title: title || originalTitle,
        original_title: originalTitle,
        overview,
        poster_path: typeof raw.poster_path === "string" ? raw.poster_path : null,
        backdrop_path: typeof raw.backdrop_path === "string" ? raw.backdrop_path : null,
        year,
        vote_average: voteAverage,
        popularity,
        score: Math.round(score * 100) / 100,
        via: existing?.via === "text" ? "text" : via,
      });
    }
  };

  const searchPath =
    typeFilter === "tv" ? "search/tv" : typeFilter === "movie" ? "search/movie" : "search/multi";

  // 1 & 2. Direct TMDB search in en-US and ar across normalized variants
  for (const v of variants.slice(0, 2)) {
    const [enRes, arRes] = await Promise.all([
      tmdbSearch(searchPath, v, "en-US"),
      tmdbSearch(searchPath, v, "ar"),
    ]);
    for (const item of enRes) ingestItem(item, "text");
    for (const item of arRes) ingestItem(item, "text");
  }

  // 3. Actor / Person search -> known_for
  const peopleRes = await tmdbSearch("search/person", q, "ar");
  for (const p of peopleRes.slice(0, 3)) {
    if (Array.isArray(p.known_for)) {
      for (const kf of p.known_for as Record<string, unknown>[]) {
        ingestItem(kf, "person");
      }
    }
  }

  // 4. Workers AI candidate generation (verified against TMDB)
  let aiStatus: "used" | "skipped" | "failed" = "skipped";
  const candidates: string[] = [];
  if (aiBinding) {
    const prompt = [
      {
        role: "system",
        content:
          "Return ONLY a JSON array of up to 4 official English movie or TV show titles matching the user description or Arabic query. No markdown, no explanation.",
      },
      { role: "user", content: q },
    ];
    let aiResp: unknown = null;
    try {
      aiResp = await aiBinding.run("@cf/meta/llama-3.3-70b-instruct-fp8-fast", {
        messages: prompt,
        max_tokens: 120,
      });
    } catch {
      try {
        aiResp = await aiBinding.run("@cf/meta/llama-3.1-8b-instruct", {
          messages: prompt,
          max_tokens: 120,
        });
      } catch {
        aiStatus = "failed";
      }
    }
    if (aiResp && typeof aiResp === "object") {
      const txt = String((aiResp as { response?: string }).response || "");
      const m = txt.match(/\[[\s\S]*\]/);
      if (m) {
        try {
          const arr = JSON.parse(m[0]);
          if (Array.isArray(arr)) {
            for (const c of arr) {
              if (typeof c === "string" && c.trim().length >= 2 && candidates.length < 4) {
                candidates.push(c.trim());
              }
            }
          }
        } catch {
          // ignore malformed AI output
        }
      }
      if (candidates.length > 0) {
        aiStatus = "used";
        for (const cand of candidates) {
          const verified = await tmdbSearch(searchPath, cand, "en-US");
          for (const item of verified.slice(0, 3)) {
            ingestItem(item, "ai");
          }
        }
      }
    }
  }

  const results = Array.from(byKey.values()).sort((a, b) => b.score - a.score);
  return {
    query: q,
    ai: aiStatus,
    candidates,
    results,
  };
}
