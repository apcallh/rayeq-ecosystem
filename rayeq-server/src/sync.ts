let syncReady = false;

export async function ensureSyncSchema(db: D1Database): Promise<void> {
  if (syncReady) return;
  await db
    .prepare(
      `CREATE TABLE IF NOT EXISTS sync_data (
        user_id INTEGER PRIMARY KEY,
        data TEXT NOT NULL,
        enc TEXT NOT NULL DEFAULT 'gzip-b64',
        bytes INTEGER NOT NULL DEFAULT 0,
        rev INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL
      )`
    )
    .run();
  await db
    .prepare(
      `CREATE TABLE IF NOT EXISTS sync_progress (
        user_id INTEGER NOT NULL,
        key TEXT NOT NULL,
        pos_ms INTEGER NOT NULL DEFAULT 0,
        dur_ms INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL,
        title TEXT,
        srv_at INTEGER NOT NULL,
        PRIMARY KEY (user_id, key)
      )`
    )
    .run();
  await db
    .prepare(`CREATE INDEX IF NOT EXISTS sync_progress_srv ON sync_progress(user_id, srv_at)`)
    .run();
  await db
    .prepare(`CREATE INDEX IF NOT EXISTS sync_progress_upd ON sync_progress(user_id, updated_at)`)
    .run();
  syncReady = true;
}

function uint8ToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function base64ToUint8(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export async function compressGzipB64(text: string): Promise<string> {
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"));
  const buf = await new Response(stream).arrayBuffer();
  return uint8ToBase64(new Uint8Array(buf));
}

export async function decompressGzipB64(b64: string, enc = "gzip-b64"): Promise<string> {
  if (enc === "plain") return b64;
  const bytes = base64ToUint8(b64);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  return await new Response(stream).text();
}

export const PROGRESS_KEY_RE = /^(tmdb|abs|imdb|tvdb|mal):(tv|movie):[A-Za-z0-9:._-]{1,100}$/;
export const MAX_SYNC_RAW_BYTES = 2 * 1024 * 1024; // 2 MiB
export const MAX_SYNC_B64_CHARS = 1900000;
