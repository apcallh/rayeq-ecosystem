#!/usr/bin/env bash
set -euo pipefail
URL="${1:-<YOUR_WORKER_URL>}"
VER="${2:-1.0.0}"
CODE="${3:-1}"

./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g \
  -PRAYEQ_SERVER_URL="$URL" \
  -PRAYEQ_VERSION_NAME="$VER" \
  -PRAYEQ_VERSION_CODE="$CODE" \
  :app:assembleRelease

mkdir -p ../releases
OUT_APK="../releases/Rayeq-Admin-v${VER}.apk"
cp app/build/outputs/apk/release/*.apk "$OUT_APK" 2>/dev/null || true
rm -f ../releases/*.idsig
echo "Built $OUT_APK with SERVER_URL=$URL"
