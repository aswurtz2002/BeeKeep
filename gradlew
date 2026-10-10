#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION="9.6.0"
LOCAL="$APP_HOME/.tools/gradle-$VERSION/bin/gradle"
if [ -x "$LOCAL" ]; then exec "$LOCAL" "$@"; fi
if [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then exec "$GRADLE_HOME/bin/gradle" "$@"; fi
for arg in "$@"; do
  if [ "$arg" = "--offline" ]; then
    echo "BeeKeep: Gradle $VERSION is not installed locally and --offline was requested." >&2
    echo "Install Gradle $VERSION or run without --offline on a network-enabled build host." >&2
    exit 2
  fi
done
exec java -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
