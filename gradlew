#!/bin/sh
set -eu

APP_HOME=$(cd "${0%/*}" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar is not included in this archive."
  echo "Run: gradle wrapper --gradle-version 8.13"
  exit 1
fi

exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
