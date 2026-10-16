import test from "node:test";
import assert from "node:assert/strict";

// Exact JS mirror of our Kotlin pure algorithms in ServerQuality.kt, PlaybackMemory.kt, StickyServer.kt, ListMemory.kt, EpisodeMatch.kt, Engine.kt
function toAsciiDigits(s) {
  return s
    .replace(/[٠-٩]/g, (c) => String.fromCharCode(48 + (c.charCodeAt(0) - 0x0660)))
    .replace(/[۰-۹]/g, (c) => String.fromCharCode(48 + (c.charCodeAt(0) - 0x06f0)));
}

function linkQualityFromRaw(q) {
  return q >= 401 && q <= 4320 ? q : 0;
}

function linkQualityFromVideo(w, h) {
  if (w <= 0 || h <= 0) return 0;
  const effH = w >= h ? Math.max(h, Math.round((w * 9) / 16)) : Math.min(w, h);
  if (effH >= 1800) return 2160;
  if (effH >= 1260) return 1440;
  if (effH >= 900) return 1080;
  if (effH >= 600) return 720;
  if (effH >= 420) return 480;
  if (effH >= 300) return 360;
  return 240;
}

function linkQualityFromText(raw) {
  if (!raw) return 0;
  const s = toAsciiDigits(raw).toLowerCase();
  const exp = s.match(/(?<!\d)(4320|2160|1440|1080|720|576|480|360|240)[pi]\b/i);
  if (exp) return Number(exp[1]) === 576 ? 480 : Number(exp[1]);
  const dim = s.match(/\b(\d{3,4})\s*[x×]\s*(\d{3,4})\b/i);
  if (dim) return linkQualityFromVideo(Number(dim[1]), Number(dim[2]));
  if (/\b(4k|uhd)\b/.test(s)) return 2160;
  if (/\b(2k|qhd)\b/.test(s)) return 1440;
  if (/\b(fhd|full\s*hd)\b/.test(s)) return 1080;
  if (/\bhd\b/.test(s)) return 720;
  if (/\bsd\b/.test(s)) return 480;
  if (/(فائقة|الترا|ألترا)/.test(s)) return 2160;
  if (/(عالية|مرتفعة)/.test(s)) return 1080;
  if (/متوسطة/.test(s)) return 720;
  if (/(منخفضة|ضعيفة|سيئة)/.test(s)) return 480;
  const bare = s.match(/(?<!\d)(2160|1440|1080|720|480|360)(?!\d)/);
  if (bare) return Number(bare[1]);
  return 0;
}

function qualityRank(q) {
  if (q <= 0) return 7;
  if (q >= 2160) return 0;
  if (q >= 1440) return 1;
  if (q >= 1080) return 2;
  if (q >= 720) return 3;
  if (q >= 480) return 4;
  if (q >= 360) return 5;
  return 6;
}

function playbackKey({ isArabic, quality, memoryMatchTier = 3, adminRank = 9999, recentRank = 9, stickyLevel = 0 }) {
  if (stickyLevel >= 2) {
    const bucket = (5 - Math.min(4, Math.max(2, stickyLevel))) * 100000;
    return bucket + qualityRank(quality) * 1000 + Math.min(999, adminRank);
  }
  const langGroup = isArabic ? 1000000000 : 2000000000;
  return (
    langGroup +
    qualityRank(quality) * 100000000 +
    memoryMatchTier * 10000000 +
    adminRank * 100 +
    recentRank
  );
}

function stickyNormName(raw, currentEpisode = null) {
  if (!raw) return "";
  let s = toAsciiDigits(raw).toLowerCase();
  s = s.replace(/\bs\d{1,2}\s*e\d{1,4}\b/g, " ");
  s = s.replace(/(الحلقة|حلقة|episode|ep|e)\s*\d{1,4}\b/g, " ");
  s = s.replace(/(الموسم|موسم|season)\s*\d{1,2}\b/g, " ");
  s = s.replace(/(?<!\d)(4320|2160|1440|1080|720|576|480|360|240)[pi]?\b/g, " ");
  s = s.replace(/\b(4k|2k|uhd|qhd|fhd|full\s*hd|hd|sd|hq|lq)\b/g, " ");
  s = s.replace(/(جودة|فائقة|الترا|ألترا|عالية|مرتفعة|متوسطة|منخفضة|ضعيفة|سيئة)/g, " ");
  if (currentEpisode && currentEpisode > 0) {
    s = s.replace(new RegExp(`(?<!\\d)0*${currentEpisode}(?!\\d)`, "g"), " ");
  }
  return s.replace(/[^a-z0-9\u0621-\u064A]+/g, "").trim();
}

test("Viewer Pure Logic Suite: LinkQuality, RealQuality, PlaybackMemory, StickyServer, AutoStart", () => {
  // 1. Qualities.Unknown = 400 trap
  assert.equal(linkQualityFromRaw(400), 0);
  assert.equal(linkQualityFromRaw(360), 0);
  assert.equal(linkQualityFromRaw(1080), 1080);

  // 2. Arabic numerals, Arabic words, bare numbers, 16:9 aspect video height
  assert.equal(linkQualityFromText("سيرفر ١٠٨٠p"), 1080);
  assert.equal(linkQualityFromText("جودة فائقة"), 2160);
  assert.equal(linkQualityFromText("جودة عالية"), 1080);
  assert.equal(linkQualityFromText("جودة متوسطة"), 720);
  assert.equal(linkQualityFromText("جودة منخفضة"), 480);
  assert.equal(linkQualityFromText("Server 720"), 720);
  assert.equal(linkQualityFromVideo(1920, 800), 1080);

  // 3. PlaybackMemory Sort Order
  const kSticky = playbackKey({ isArabic: false, quality: 720, stickyLevel: 4 });
  const kAr1080 = playbackKey({ isArabic: true, quality: 1080 });
  const kAr720Mem = playbackKey({ isArabic: true, quality: 720, memoryMatchTier: 0 });
  const kAr720NoMem = playbackKey({ isArabic: true, quality: 720, memoryMatchTier: 3 });
  const kAr480 = playbackKey({ isArabic: true, quality: 480 });
  const kArUnknown = playbackKey({ isArabic: true, quality: 0 });
  const kEn2160 = playbackKey({ isArabic: false, quality: 2160 });

  assert.ok(kSticky < kAr1080);
  assert.ok(kAr1080 < kAr720Mem);
  assert.ok(kAr720Mem < kAr720NoMem);
  assert.ok(kAr720NoMem < kAr480);
  assert.ok(kAr480 < kArUnknown);
  assert.ok(kArUnknown < kEn2160);

  // 4. StickyServer normName across episodes
  assert.equal(
    stickyNormName("VidStream - الحلقة ٥ - جودة عالية 1080p", 5),
    stickyNormName("VidStream Episode 06 FHD", 6)
  );
  assert.equal(
    stickyNormName("Host 05", 5),
    stickyNormName("Host 06", 6)
  );
});
