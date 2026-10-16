# Rayeq Server Contract (`rayeq-api`)

- **Base URL**: `<YOUR_WORKER_URL>`
- **Viewer Headers**:
  - `Authorization: Bearer <session_token>`
  - `X-Rayeq-Client: android/<versionName>`
- **Admin Headers**:
  - `Authorization: Bearer <admin_hmac_token>`
- **Standard Error Shape**: `{ "error": "<code>" }` with `Cache-Control: no-store` and CORS headers.

---

## 1. مسارات تطبيق المشاهدة (`/v1/*` و `/health`)

### `POST /v1/auth/google`
- **Auth**: لا يتطلب جلسة.
- **Request JSON**: `{ "idToken": "<Google JWT>", "deviceModel": "Pixel 8", "deviceName": "My Phone", "deviceType": "phone|tablet|tv" }`
- **Response JSON (200)**: `{ "status": "pending|approved|blocked|locked|expired", "token": "<opaque_session_token>", "accessUntil": 1767225600000, "lockedUntil": 1767000000000 }`
- **Error Codes**: `400 idToken required`, `400 bad_json`, `401 invalid_google_token`.
- **Client Behaviour**: يحفظ `token` والحالة في `rayeq_auth`، وكل تسجيل دخول جديد ينشئ جهازاً بـ`approved=0` ينتظر موافقة المدير.
- **Since**: `v1.0.0`

### `POST /v1/heartbeat`
- **Auth**: `Bearer <session_token>`
- **Request JSON**: `{ "deviceType": "phone|tablet|tv" }`
- **Response JSON (200)**: `{ "status": "pending|approved|blocked|locked|expired", "accessUntil": null, "lockedUntil"?: number }`
- **Error Codes**: `401 kicked` (عند حذف الجلسة أو طرد الجهاز).
- **Client Behaviour**: يُستدعى كل 3 دقائق وعند بدء التشغيل والعودة للواجهة؛ عند `401` يحذف `token` وينتقل إلى `Gate.KICKED`.
- **Since**: `v1.0.0`

### `GET /v1/rev?since=<token>&wait=0..20`
- **Auth**: `Bearer <session_token>` (يعمل في جميع الحالات بما فيها `pending` و`locked`).
- **Response JSON (200)**: `{ "rev": "<n>.<status>.<accessUntil|0>.<lockedUntil|0>", "n": 12, "status": "approved", "accessUntil": null, "lockedUntil"?: number, "poll": 3000 }` مع الترويسة `ETag: "<rev>"`.
- **Response (304)**: عند `wait=0` وتطابق `If-None-Match`.
- **Error Codes**: `401 unauthorized` (عند الطرد).
- **Client Behaviour**: حلقة long-poll في `RevWatch` أثناء وجود Activity في الواجهة فقط؛ عند تغير `rev` يحدّث البوابة فوراً ويستدعي `SourceConfig.syncNow()` و`AppInfo.refresh()`.
- **Since**: `v1.0.0`

### `GET /v1/tmdb/<path>?<query>`
- **Auth**: يتطلب جلسة `approved` عندما يكون `AUTH_ENABLED=true`.
- **Response**: يمرر رد TMDB v3 كما هو بعد حذف أي `api_key` من العميل وحقن `TMDB_KEY` على الخادم، مع تخزين مؤقت (6 ساعات للقوائم و24 ساعة للتفاصيل).
- **Error Codes**: `401 unauthorized`, `401 not_approved`, `404 path not allowed`, `503 tmdb_not_configured`.
- **Since**: `v1.0.0`

### `GET /v1/sources`
- **Auth**: يتطلب جلسة `approved` دائماً (مهما كانت قيمة `AUTH_ENABLED`).
- **Response JSON (200)**:
  ```json
  {
    "repos": ["<repo_url>"],
    "plugins": ["InternalNameA", "ToolX"],
    "servers": [
      {
        "n": 1,
        "priority": 1,
        "repo": "<repo_url>",
        "internalName": "InternalNameA",
        "version": 7,
        "lang": "ar",
        "langSource": "plugin",
        "arabic": true,
        "kind": "auto",
        "live": false,
        "tool": false,
        "tvTypes": ["Movie", "TvSeries"],
        "category": "أفلام ومسلسلات"
      }
    ],
    "tools": [
      {
        "repo": "<repo_url>",
        "internalName": "ToolX",
        "version": 1,
        "lang": "other",
        "arabic": false,
        "kind": "auto",
        "live": false,
        "tool": true,
        "tvTypes": ["Others"],
        "category": "أدوات"
      }
    ]
  }
  ```
- **Error Codes**: `401 unauthorized`, `401 not_approved`.
- **Client Behaviour**: ينفذ `quickApply` خارج القفل فوراً، ثم يحذف الإضافات المسحوبة قبل قراءة أي مستودع، وينزّل الإضافات المسموحة حتى الإصدار المعتمد `server.version`.
- **Since**: `v1.0.0`

### `POST /v1/sources/report`
- **Auth**: `approved` دائماً.
- **Request JSON**: `{ "plugins": [{ "internalName": "X", "version": 7, "ok": true, "error": null }] }` (بحد أقصى 300 عنصر).
- **Response JSON (200)**: `{ "ok": true }`
- **Error Codes**: `400 bad_json`, `400 bad_plugins`, `401 unauthorized`.
- **Since**: `v1.0.0`

### `GET /v1/app`
- **Auth**: عام (لا يتطلب مصادقة).
- **Response JSON (200)**: `{ "latestVersion": "1.0.0", "latestCode": 1, "downloadUrl": null, "notes": "", "minCode": null, "announcement": "", "announcementId": 0, "maintenance": false, "maintenanceMessage": "", "supportContact": "" }`
- **Client Behaviour**: `maintenance` مضمونة كقيمة `boolean` حقيقية دائماً.
- **Since**: `v1.0.0`

### `GET /v1/sync` و `PUT /v1/sync`
- **Auth**: `approved` دائماً.
- **GET Response (200)**: `{ "data": { ... } | null, "updatedAt": 1767000000000 | null, "rev": 3 }`
- **PUT Request**: `{ "data": { ... }, "baseRev": 3 }`
- **PUT Response (200)**: `{ "ok": true, "rev": 4, "updatedAt": 1767000005000 }`
- **Error Codes**: `400 bad_data|bad_baseRev|bad_json`, `409 conflict` (يعيد `{error:"conflict", data, updatedAt, rev}`), `413 too_large` (حد 2 MiB خام أو 1,900,000 حرف مضغوط بـ`gzip-b64`).
- **Since**: `v1.0.0`

### `GET /v1/sync/progress?since=<ms>` و `PUT /v1/sync/progress`
- **Auth**: `approved` دائماً.
- **PUT Request**: `{ "items": [{ "key": "tmdb:tv:1399:s1e1", "posMs": 120000, "durMs": 3000000, "updatedAt": 1767000000000, "title": "Breaking Bad" }] }` (1..200 عنصر).
- **PUT Response (200)**: `{ "ok": true, "received": 1, "skipped": 0, "serverTime": 1767000001000 }`
- **GET Response (200)**: `{ "items": [...], "serverTime": 1767000002000, "more": false, "next": null }`
- **Error Codes**: `400 bad_items|bad_json`, `413 too_many_items`.
- **Since**: `v1.0.0`

### `POST /v1/presence`
- **Auth**: `Bearer <session_token>`
- **Request JSON**: `{ "state": "browsing|watching|background", "seconds_watched_delta": 60, "screens_delta": 1, "app_version": "1.0.0" }`
- **Response JSON (200)**: `{ "ok": true, "status": "approved", "serverTime": 1767000000000 }`
- **Privacy Guarantee**: لا يرسل ولا يخزّن أسماء الأعمال أو الحلقات أبداً.
- **Since**: `v1.0.0`

### `GET /v1/smart-search?q=<query>&type=tv|movie|multi`
- **Auth**: `approved` (أو ترويسة `X-Smart-Test` مطابقة للسر `SMART_TEST`).
- **Response JSON (200)**: `{ "query": "...", "ai": "used|skipped|failed", "candidates": [...], "results": [...] }`
- **Error Codes**: `400 query_too_short` (أقصر من حرفين), `503 tmdb_not_configured`.
- **Since**: `v1.0.0`

### `GET /v1/subs/config`, `/v1/subs/os/*`, `/v1/subs/free`, `/v1/tvdb/*`, `/v1/skip`
- **Auth**: `approved` دائماً.
- **Error Codes**: `503 os_not_configured` (عند عدم ضبط مفتاح OpenSubtitles)، `503 tvdb_not_configured` (عند عدم ضبط `TVDB_KEY`)، `404 path not allowed`.
- **Skip Segments (`/v1/skip`)**: يرجع المقاطع الحقيقية فقط `{ "segments": [{ "type": "intro|recap|outro|preview", "start_ms": 15000, "end_ms": 90000, "source": "aniskip|theintrodb|introdb" }], "sources": {...} }` بدون أي تخمين زمني.
- **Since**: `v1.0.0`

---

## 2. مسارات تطبيق الإدارة (`/admin/*`)

جميع مسارات `/admin/*` (ما عدا `/admin/login`) تتطلب `Authorization: Bearer <admin_token>`، وكل عملية كتابة ناجحة (`status < 400` وغير `GET`) ترفع `config.rev` تلقائياً بمقدار `1`:
- `POST /admin/login`: `{username, password}` ← `{token, expiresAt}` (`401 invalid_credentials`, `429 too_many_attempts` مع `Retry-After` بعد 5 محاولات فاشلة خلال 15 دقيقة).
- `GET /admin/me`: ← `{ok: true}`.
- `GET /admin/stats`: ← إحصاءات المستخدمين والأجهزة والمصادر والمتصلين الآن وساعات اليوم.
- `GET /admin/users?sort=online|last_seen|watch`: ← `{now, users: [UserItem]}`.
- `GET /admin/users/{id}` و `DELETE /admin/users/{id}`.
- `POST /admin/users/{id}/{approve|access|block|unblock|lock|unlock|kick}` و `POST /admin/users/bulk`.
- `POST /admin/devices/{id}/{approve|kick}` و `POST /admin/devices/kick-all`.
- `GET|PUT|DELETE /admin/users/{id}/sources`: قواعد وتخصيص السيرفرات لكل مستخدم (`400 bad_rules|bad_servers|unknown_server`).
- `POST /admin/repos/inspect`, `POST /admin/repos` (`409 repo_exists`, `400 bad_url`), `POST /admin/repos/remove`, `POST /admin/repos/refresh`, `GET /admin/catalog[?fresh=1]`.
- `POST /admin/plugins`, `POST /admin/plugins/update`, `GET|PUT /admin/servers`, `GET|PUT /admin/sources`.
- `GET|PUT /admin/app` (`400 bad maintenance`), `GET|PUT /admin/settings/subs`, `GET /admin/integrations`, `POST /admin/password`, `GET /admin/log`.

---

## Changelog
- **2026-10-16 (`v1.0.0`)**: الإصدار الأول الكامل لجميع مسارات المشاهدة والإدارة والمزامنة والكتالوج والـlong-poll (`/v1/rev`).
