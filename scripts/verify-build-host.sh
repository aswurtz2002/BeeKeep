#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
errors=0
need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "MISSING: $1"; errors=$((errors+1)); }; }
need_path() { [ -e "$1" ] || { echo "MISSING: $2 ($1)"; errors=$((errors+1)); }; }

echo "BeeKeep Android build host check"
echo "Root: $ROOT"
echo
if command -v java >/dev/null 2>&1; then java -version 2>&1 | head -n 2; else need_cmd java; fi
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"; else echo "MISSING: ANDROID_SDK_ROOT"; errors=$((errors+1)); fi
if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  need_path "$ANDROID_SDK_ROOT/platforms/android-37/android.jar" "Android API 37 platform"
  need_path "$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt2" "Android Build Tools 36.0.0 aapt2"
  need_path "$ANDROID_SDK_ROOT/build-tools/36.0.0/d8" "Android Build Tools 36.0.0 d8"
  need_path "$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" "Android Build Tools 36.0.0 apksigner"
  need_path "$ANDROID_SDK_ROOT/platform-tools/adb" "Android platform-tools adb"
fi
if [ -x "$ROOT/.tools/gradle-9.6.0/bin/gradle" ]; then
  echo "OK: bundled Gradle 9.6.0"
elif [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "OK: GRADLE_HOME"
elif command -v gradle >/dev/null 2>&1; then
  echo "OK: system Gradle ($(gradle --version | awk '/Gradle /{print $2; exit}') )"
else
  echo "MISSING: Gradle 9.6.0"
  errors=$((errors+1))
fi

echo
if [ "$errors" -eq 0 ]; then
  echo "BUILD HOST READY"
  exit 0
fi
echo "BUILD HOST NOT READY ($errors missing prerequisite(s))"
exit 1
