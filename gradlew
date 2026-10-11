#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Always use the checked-in Gradle wrapper properties. Avoid local/global Gradle
# overrides so Android Studio and CI build with the same pinned distribution.
exec java -Dorg.gradle.appname=gradlew -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
