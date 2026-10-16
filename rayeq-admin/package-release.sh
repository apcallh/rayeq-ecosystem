#!/usr/bin/env bash
set -euo pipefail
VER="${1:-1.0.0}"
mkdir -p ../releases
OUT_APK="../releases/Rayeq-Admin-v${VER}.apk"
if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
  cp "app/build/outputs/apk/release/app-release.apk" "$OUT_APK"
fi
rm -f ../releases/*.idsig
echo "Packaged $OUT_APK"
