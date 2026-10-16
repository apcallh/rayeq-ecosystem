# رايق (Rayeq) — viewer notes

- **applicationId**: `app.rayeq.watch` · **namespace**: `app.rayeq` · **minSdk**: 26 · **targetSdk**: 36 · **compileSdk**: 37
- **الإصدار**: `v1.0.0` (`versionCode = 1`)
- **Signing**: `<KEYSTORE_PATH>` alias `<KEY_ALIAS>` (passwords outside the repo via `local.properties` / env vars) · SHA-1 `<fingerprint>`
- **Minify / R8**: معطّل صراحة (`isMinifyEnabled = false`, `isShrinkResources = false`) لحماية تحميل إضافات `.cs3` وقت التشغيل.

---

## Layout (هيكل الملفات ومسؤولياتها)
- `library/`: وحدة مكتبة CloudStream 4.8.0 (`com.lagradost.cloudstream3.*`: `MainAPI`, `APIHolder`, `SearchResponse`, `LoadResponse`, `Episode`, `TvType`, `ExtractorApi`, `ExtractorLink`, `Qualities.Unknown = 400`, `SubtitleFile`, `BasePlugin`).
- `app/src/main/java/com/lagradost/cloudstream3/`: طبقة التوافق الكاملة (`CloudStreamApp`, `AcraApplication`, `MainActivity`, `CommonActivity`, `Plugin`, `PluginManager`, `RepositoryManager`, `RepositoryData`, `DataStore`, `Event`, `CloudflareKiller`, `SyncAPI` stubs).
- `app/src/main/java/app/rayeq/Rayeq.kt`: تهيئة جميع الأنظمة عند بدء التشغيل.
- `app/src/main/java/app/rayeq/auth/Auth.kt`: بوابة الدخول بحساب Google عبر Credential Manager، آلة حالات البوابة (`SIGNED_OUT`, `PENDING`, `APPROVED`, `BLOCKED`, `LOCKED`, `EXPIRED`, `KICKED`)، ونبضات `heartbeat` كل 3 دقائق.
- `app/src/main/java/app/rayeq/data/`:
  - `ServerQuality.kt`: `ServerLang`, `LiveDetect` (مع قاعدة `EXCLUDE` واستبعاد الأدوات)، `LinkQuality` (تجاهل `Qualities.Unknown = 400`، قراءة الأرقام والكلمات العربية، وفحص HLS master)، `RealQuality` (كشف الـupscale بالبت-ريت الفعلي)، و`AutoStart`.
  - `PlaybackMemory.kt`: مفاتيح الترتيب الصارمة (المفضّل ← العربي ← الجودة ← التذكر التلقائي ← ترتيب المدير ← الحداثة، والمجهول آخراً) ونسيان السيرفر بعد فشلين (`MAX_FAILS = 2`).
  - `StickyServer.kt`: حفظ واستعادة السيرفر المختار يدوياً عبر الحلقات مع `normName` و`labelQuality` ونافذة التحويل المتأخر.
  - `ListMemory.kt`: حفظ واستعادة الموسم والنطاق والتبويب وموضع التمرير (`PAGE_SIZE = 40`, `SAVE_DEBOUNCE_MS = 700`).
  - `EpisodeMatch.kt`: مطابقة الحلقات للمسلسلات متعددة المواسم والأنمي المتصل بالترقيم المطلق.
  - `LinkFeed.kt`: مشاركة الروابط الحية وتخزينها (`LINK_TTL_MS = 15m`, `EMPTY_TTL_MS = 90s`, `PAGE_TTL_MS = 45m`).
  - `Engine.kt`: المحرك المخفي بمجمّعين منفصلين (العربي 16 والأجنبي 6)، مهلات `12s/18s/45s`، تقييم `scoreTitle` بعتبتي `88/92` وحماية الأنمي من النسخ الواقعية، والجلب المسبق قبل النهاية بـ3 دقائق.
  - `SourceConfig.kt`: مزامنة المصادر مع `quickApply` الفوري خارج القفل، وحذف الإضافات المسحوبة قبل قراءة المستودعات، وإرسال تقارير التثبيت.
  - `RevWatch.kt`, `AppInfo.kt`, `Tmdb.kt`, `CloudSync.kt`, `Presence.kt`, `Skip.kt`, `SubSearch.kt`, `Live.kt`, `Downloads.kt`, `Notify.kt`, `Library.kt`, `Prefs.kt`, `Net.kt`.
- `app/src/main/java/app/rayeq/player/`: `PlayerMath.kt`, `PlayerChrome.kt` (واجهة Compose نقية بدون أنواع Android)، `PlayerVM.kt`, `PlayerActivity.kt`, `PlaybackService.kt`.
- `app/src/main/java/app/rayeq/ui/`: `Theme.kt` (6 قوالب + ارتفاعات أسطر عربية صريحة مع `Trim.None`)، `Ambient.kt`, `Kit.kt`, `Welcome.kt`, `Gate.kt`, `Home.kt`, `Details.kt`, `Explore.kt`, `Search.kt`, `People.kt`, `LibraryUi.kt`, `LiveScreen.kt`, `Settings.kt`, `SubStyle.kt`.

---

## BuildConfig (العناصر النائبة)
- `SERVER_URL`: `<YOUR_WORKER_URL>`
- `AUTH_ENABLED`: `true`
- `GOOGLE_WEB_CLIENT_ID`: `<GOOGLE_WEB_CLIENT_ID>`

---

## Constants (الثوابت الزمنية والعتبات وأسبابها)
| الثابت | القيمة | السبب |
|---|---|---|
| `Qualities.Unknown` | `400` (يُعامل كـ`0` مجهول) | كثير من الإضافات تضع 360 أو 400 افتراضياً لروابط 1080p |
| `AutoStart.COALESCE_MS` | `1000 ms` | تجميع الروابط التي تصل معاً للمقارنة بالجودة قبل البدء |
| `AutoStart.NON_ARABIC_WAIT_MS` | `12000 ms` | انتظار انتهاء المصادر العربية قبل السماح ببدء سيرفر أجنبي |
| `AutoStart.UPGRADE_WINDOW_MS` | `20000 ms` (`MAX_UPGRADES = 2`) | نافذة الترقية التلقائية لجودة أعلى دون إزعاج المشاهد |
| `AutoStart.ARABIC_STEAL_WINDOW` | `45000 ms` | التحويل التلقائي لسيرفر عربي إذا بدأ أجنبي أولاً |
| `StickyServer.EXACT_GRACE_MS` | `1200 ms` | انتظار المطابقة التامة (مستوى 4) قبل تشغيل الجزئية (مستوى 2/3) |
| `StickyServer.WAIT_MS` | `8000 ms` | حجز السيرفرات الأخرى انتظاراً لمزوّد السيرفر المفضّل |
| `StickyServer.LATE_SWITCH_MS` | `30000 ms` | نافذة الانتقال للسيرفر المفضّل إذا وصل متأخراً |
| `Engine.SEARCH/LOAD/LINKS_TIMEOUT` | `12s / 18s / 45s` | منع أي إضافة بطيئة من تعطيل البحث |
| `Engine.PREFETCH_BEFORE_END_MS` | `180000 ms` (3 دقائق) | جلب روابط الحلقة التالية مسبقاً في `LinkFeed` |
| `LinkFeed.LINK / EMPTY / PAGE_TTL` | `15m / 90s / 45m` | موازنة صلاحية توكنات الروابط مع سرعة التنقل |
| `ListMemory.SAVE_DEBOUNCE_MS` | `700 ms` (`PAGE_SIZE = 40`) | حفظ موضع قائمة الحلقات بهدوء بعد استقرار التمرير |
| `Library.WATCHED_THRESHOLD` | `0.92` (92%) | اعتبار الحلقة مشاهَدة وتفعيل الاستئناف الذكي للحلقة التالية |

---

## v1.0.0 (2026-10-16)
- **ما بُني**: المنظومة الكاملة للمراحل M0–M15 شاملاً الخادم `rayeq-api` وتطبيق الإدارة `rayeq-admin` وتطبيق المشاهدة `rayeq` وطبقة توافق CloudStream 4.8.0 واختبارات الوحدة.
- **الاختبارات**:
  - اختبارات الخادم المحلية (M0–M5 ضد قاعدة SQLite/D1 حقيقية): `2/2 suites passed` (تغطي المصادقة، آلة حالات الموافقة، القفل المؤقت، `parseBool`، الكتالوج، الفئات العربية، البث المباشر بقاعدة `EXCLUDE`، المصادر لكل مستخدم، الـlong-poll `/v1/rev` في أقل من ثانية، المزامنة `gzip-b64` و`409` و`413`، مواضع المشاهدة، الحضور، البحث الذكي، ومقاطع التخطي).
  - اختبارات المنطق النقي للعارض: `5/5 JUnit test classes` + التحقق المحلي (`verify-pure-logic.mjs` ✔).
  - **مبني ومختبر باختبارات الوحدة فقط — لم يُختبر على جهاز**.

---

## Not done / limits (الحدود المعروفة)
- **`RealQuality`**: لا يمكنه كشف تضخيم (upscale) عالي البت-ريت لأن ذلك يتطلب تحليل الصورة نفسها، وقد يقلل تقدير المشاهد الساكنة جداً، وفي HLS ABR يعرض أفضل variant شوهد.
- **تأخير الصوت**: غير متاح لأن ExoPlayer لا يدعم تأخير المسار الصوتي بشكل أصلي.
- **تأخير الترجمة السالب**: ينطبق فقط على الأسطر التي تصل مبكراً إلى الذاكرة، أما الموجب فيعيد العرض من سجل آخر 400 سطر.
- **libass**: غير مدمج لتفادي تعارض `libc++_shared.so` مع `nextlib-media3ext` ولأن ExoPlayer لدينا ينشئ `DataSource.Factory` مخصصاً لكل رابط.
