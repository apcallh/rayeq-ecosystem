# دليل نشر خادم `rayeq-api` (Cloudflare Worker + D1)

## 1. هيكل الملفات
- `wrangler.toml`: إعدادات Worker وقاعدة بيانات D1 (`rayeq-db`) وWorkers AI (`AI`).
- `schema.sql` و `migrations/0001_init.sql`: المخطط الأساسي للجداول (`users`, `devices`, `config`, `login_attempts`). بقية الجداول (`user_usage`, `user_usage_daily`, `sync_data`, `sync_progress`, `user_sources`, `user_source_rules`) ينشئها الـWorker تلقائياً عند أول طلب أو عبر `/health`.
- `src/index.ts`: نقطة الدخول، المصادقة، آلة حالات الموافقة، الـlong-poll (`/v1/rev`)، وكيل TMDB، وإدارة المستخدمين والأجهزة.
- `src/catalog.ts`: قراءة المستودعات (`repo.json` / `plugins.json`) وتصنيف السيرفرات (اللغة، البث المباشر بقاعدة `EXCLUDE`، الأدوات، والفئات العربية).
- `src/usersources.ts`: قواعد وتخصيص السيرفرات لكل مستخدم (`/v1/sources` و `/admin/users/{id}/sources`).
- `src/sync.ts`: المزامنة السحابية المضغوطة بـ`gzip-b64` (`/v1/sync`) ومواضع المشاهدة (`/v1/sync/progress`).
- `src/v05.ts`: وكلاء OpenSubtitles (`/v1/subs/os/*`) وTheTVDB (`/v1/tvdb/*`) ومقاطع التخطي الحقيقية (`/v1/skip`).
- `src/smart.ts`: البحث الذكي مع التطبيع العربي وWorkers AI (`/v1/smart-search`).
- `tools/hash-pass.mjs`: توليد بصمة PBKDF2-SHA256 لكلمة سر المدير.

## 2. الأسرار المطلوبة (بأسمائها فقط — لا تُحفظ في الكود أبداً)
| اسم السر (Secret) | الإلزامية | الغرض |
|---|---|---|
| `TMDB_KEY` | إلزامي | مفتاح TMDB v3 لوكيل البيانات الوصفية والبحث |
| `GOOGLE_WEB_CLIENT_ID` | إلزامي | معرّف عميل OAuth من نوع Web للتحقق من `idToken` (يقبل عدة قيم مفصولة بفواصل) |
| `SESSION_SECRET` | إلزامي | سر عشوائي (48 بايت أو أكثر) لتوقيع جلسات المدير بـHMAC-SHA256 |
| `ADMIN_PASS_HASH` | إلزامي | بصمة كلمة سر المدير الناتجة من `node tools/hash-pass.mjs` |
| `TVDB_KEY` | اختياري | مفتاح TheTVDB v4 لترتيب المواسم والحلقات البديلة |
| `OS_API_KEY` | اختياري | مفتاح تطبيق OpenSubtitles (يمكن إدخاله أيضاً من تطبيق الإدارة) |
| `SMART_TEST` | اختياري | سر اختياري لتشغيل أداة `tools/smart-eval.mjs` بدون جلسة |

## 3. خطوات النشر
```bash
cd rayeq-server
npm install

# 1. إنشاء قاعدة بيانات D1 ووضع معرفها في wrangler.toml بدل <D1_DATABASE_ID>
npx wrangler d1 create rayeq-db
npx wrangler d1 migrations apply rayeq-db --remote

# 2. ضبط الأسرار
node tools/hash-pass.mjs '<ADMIN_PASSWORD>' | npx wrangler secret put ADMIN_PASS_HASH
openssl rand -base64 48 | npx wrangler secret put SESSION_SECRET
npx wrangler secret put TMDB_KEY
npx wrangler secret put GOOGLE_WEB_CLIENT_ID

# 3. التحقق من الأنواع والبناء الجاف ثم النشر
npx tsc --noEmit
npx wrangler deploy --dry-run --outdir dist
npx wrangler deploy
```

> **ملاحظة مهمة بعد النشر**: انتظر نحو دقيقة واحدة قبل تشغيل اختبارات الدخان حتى تنتشر النسخة الجديدة على جميع عقد Cloudflare Edge.

## 4. اختبارات الدخان (Smoke Tests)
```bash
WORKER="<YOUR_WORKER_URL>"
curl -s "$WORKER/health"                           # يتوقع 200 {"ok":true,...}
curl -i -s "$WORKER/v1/tmdb/trending/all/week"     # يتوقع 401 {"error":"unauthorized"}
curl -i -s "$WORKER/v1/sources"                    # يتوقع 401 {"error":"unauthorized"}
curl -i -s "$WORKER/v1/rev"                        # يتوقع 401 {"error":"unauthorized"}
curl -s "$WORKER/v1/app"                           # يتوقع 200 {"maintenance":false,...}
curl -i -s -X POST "$WORKER/admin/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong"}'     # يتوقع 401 {"error":"invalid_credentials"}
```

## 5. سجل النشرات
- لم يُنشر بعد على حساب Cloudflare خارجي (بيئة البناء معزولة بدون اتصال خارجي — جاهز للنشر الفوري عبر `npx wrangler deploy`).
