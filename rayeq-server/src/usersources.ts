import { CatalogData, ServerEntry, kindFields, langOf } from "./catalog";

let userSourcesReady = false;

export async function ensureUserSourcesSchema(db: D1Database): Promise<void> {
  if (userSourcesReady) return;
  await db
    .prepare(
      `CREATE TABLE IF NOT EXISTS user_sources (
        user_id INTEGER NOT NULL,
        internal_name TEXT NOT NULL,
        enabled INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (user_id, internal_name)
      )`
    )
    .run();
  await db
    .prepare(
      `CREATE TABLE IF NOT EXISTS user_source_rules (
        user_id INTEGER PRIMARY KEY,
        hide_non_arabic INTEGER NOT NULL DEFAULT 0,
        hide_live INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL DEFAULT 0
      )`
    )
    .run();
  userSourcesReady = true;
}

export interface UserSourceRules {
  hideNonArabic: boolean;
  hideLive: boolean;
}

export function computeEffectiveServer(
  server: ServerEntry,
  userOverride: boolean | null | undefined,
  rules: UserSourceRules
): {
  arabic: boolean;
  lang: "ar" | "other";
  langSource: "admin" | "plugin";
  kind: "auto" | "live" | "content";
  live: boolean;
  tool: boolean;
  tvTypes: string[];
  category: string;
  hiddenByRule: boolean;
  effective: boolean;
} {
  const l = langOf(server);
  const kf = kindFields(server);
  // القاعدة «إخفاء غير العربية» لا تخفي البث المباشر الأجنبي
  const hiddenByRule =
    (rules.hideNonArabic && !l.arabic && !kf.live) || (rules.hideLive && kf.live);
  const allowedForUser =
    userOverride !== null && userOverride !== undefined ? userOverride : !hiddenByRule;
  const effective = Boolean(server.enabled) && allowedForUser;
  return {
    arabic: l.arabic,
    lang: l.lang,
    langSource: l.langSource,
    ...kf,
    hiddenByRule,
    effective,
  };
}

export async function getUserSourceState(
  db: D1Database,
  userId: number
): Promise<{
  rules: UserSourceRules;
  overrides: Map<string, boolean>;
}> {
  await ensureUserSourcesSchema(db);
  const ruleRow = await db
    .prepare(`SELECT hide_non_arabic, hide_live FROM user_source_rules WHERE user_id = ?`)
    .bind(userId)
    .first<{ hide_non_arabic: number; hide_live: number }>();
  const rules: UserSourceRules = {
    hideNonArabic: Boolean(ruleRow?.hide_non_arabic),
    hideLive: Boolean(ruleRow?.hide_live),
  };
  const rows = await db
    .prepare(`SELECT internal_name, enabled FROM user_sources WHERE user_id = ?`)
    .bind(userId)
    .all<{ internal_name: string; enabled: number }>();
  const overrides = new Map<string, boolean>();
  for (const r of rows.results || []) {
    overrides.set(r.internal_name, Boolean(r.enabled));
  }
  return { rules, overrides };
}

export async function buildViewerSources(
  db: D1Database,
  userId: number,
  catalog: CatalogData
) {
  const { rules, overrides } = await getUserSourceState(db, userId);
  const servers: Record<string, unknown>[] = [];
  const tools: Record<string, unknown>[] = [];
  const plugins: string[] = [];
  const repoSet = new Set<string>();

  let n = 0;
  for (const srv of catalog.servers || []) {
    const userOverride = overrides.has(srv.internalName)
      ? overrides.get(srv.internalName)!
      : null;
    const calc = computeEffectiveServer(srv, userOverride, rules);
    if (!calc.effective) continue;

    plugins.push(srv.internalName);
    if (srv.repo) repoSet.add(srv.repo);

    if (calc.tool) {
      tools.push({
        repo: srv.repo,
        internalName: srv.internalName,
        version: srv.version || 0,
        lang: calc.lang,
        langSource: calc.langSource,
        arabic: calc.arabic,
        kind: calc.kind,
        live: calc.live,
        tool: true,
        tvTypes: calc.tvTypes,
        category: calc.category,
      });
    } else {
      n++;
      servers.push({
        n,
        priority: n,
        repo: srv.repo,
        internalName: srv.internalName,
        version: srv.version || 0,
        lang: calc.lang,
        langSource: calc.langSource,
        arabic: calc.arabic,
        kind: calc.kind,
        live: calc.live,
        tool: false,
        tvTypes: calc.tvTypes,
        category: calc.category,
      });
    }
  }

  return {
    repos: Array.from(repoSet),
    plugins,
    servers,
    tools,
  };
}

export async function buildAdminUserSources(
  db: D1Database,
  userId: number,
  catalog: CatalogData
) {
  const { rules, overrides } = await getUserSourceState(db, userId);
  const serverList = (catalog.servers || []).map((srv, idx) => {
    let userEnabled: boolean | null = overrides.has(srv.internalName)
      ? overrides.get(srv.internalName)!
      : null;
    const calc = computeEffectiveServer(srv, userEnabled, rules);
    return {
      internalName: srv.internalName,
      name: srv.name || srv.internalName,
      repo: srv.repo,
      iconUrl: srv.iconUrl || null,
      position: idx + 1,
      lang: calc.lang,
      arabic: calc.arabic,
      live: calc.live,
      kind: calc.kind,
      tool: calc.tool,
      tvTypes: calc.tvTypes,
      category: calc.category,
      globalEnabled: Boolean(srv.enabled),
      userEnabled,
      hiddenByRule: calc.hiddenByRule,
      effective: calc.effective,
    };
  });

  const customCount =
    overrides.size + (rules.hideNonArabic ? 1 : 0) + (rules.hideLive ? 1 : 0);

  return {
    rules,
    servers: serverList,
    custom: customCount,
  };
}
