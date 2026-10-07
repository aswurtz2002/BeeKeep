#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
GRADLE_VERSION='9.6.0'
GRADLE_HOME="$ROOT_DIR/.tools/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
ZIP="$ROOT_DIR/.tools/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
EXPECTED_SHA256='bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01'

mkdir -p "$ROOT_DIR/.tools"

if [ ! -x "$GRADLE_BIN" ]; then
  command -v java >/dev/null 2>&1 || { echo 'BeeKeep requires Java 17.' >&2; exit 1; }
  echo "BeeKeep: bootstrapping Gradle $GRADLE_VERSION..."
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 3 --retry-all-errors -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo 'BeeKeep needs curl or wget to bootstrap Gradle.' >&2
      exit 1
    fi
  fi
  actual=$(sha256sum "$ZIP" | awk '{print $1}')
  [ "$actual" = "$EXPECTED_SHA256" ] || { rm -f "$ZIP"; echo "Gradle checksum mismatch." >&2; exit 1; }
  tmp="$ROOT_DIR/.tools/gradle-extract"
  rm -rf "$tmp"
  mkdir -p "$tmp"
  unzip -q "$ZIP" -d "$tmp"
  [ -d "$tmp/gradle-$GRADLE_VERSION" ] || { echo 'Unexpected Gradle archive layout.' >&2; exit 1; }
  rm -rf "$GRADLE_HOME"
  mv "$tmp/gradle-$GRADLE_VERSION" "$GRADLE_HOME"
  rm -rf "$tmp"
fi

exec "$GRADLE_BIN" "$@"
