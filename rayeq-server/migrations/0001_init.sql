CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  google_sub TEXT NOT NULL UNIQUE,
  email TEXT, name TEXT,
  status TEXT NOT NULL DEFAULT 'pending',   -- pending | approved | blocked
  locked INTEGER NOT NULL DEFAULT 0,        -- 0 = غير مقفل، 1 = مقفل حتى يُفتح، >1 = epoch ms ينتهي عنده القفل تلقائياً
  access_until INTEGER,                     -- epoch ms، NULL = بلا حد
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS users_email ON users(email);

CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  model TEXT, name TEXT, type TEXT,         -- phone | tablet | tv
  approved INTEGER NOT NULL DEFAULT 0,      -- كل تسجيل دخول جديد ينتظر المدير
  token_hash TEXT UNIQUE,                   -- SHA-256 لتوكن الجلسة؛ NULL = مطرود أو خرج
  first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id);

CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL);
INSERT OR IGNORE INTO config(key, value) VALUES ('sources', '{"repos":[],"plugins":[]}');

CREATE TABLE IF NOT EXISTS login_attempts (ip TEXT PRIMARY KEY, fails INTEGER NOT NULL DEFAULT 0, window_start INTEGER NOT NULL);
