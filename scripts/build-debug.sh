#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./scripts/verify-build-host.sh
./gradlew :app:assembleDebug --stacktrace "$@"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ]
echo "APK: $APK"
if [ -x "${ANDROID_SDK_ROOT:-}/build-tools/36.0.0/apksigner" ]; then
  "${ANDROID_SDK_ROOT}/build-tools/36.0.0/apksigner" verify --verbose "$APK"
fi
sha256sum "$APK"
