#!/usr/bin/env sh
# Gradle wrapper bootstrap — downloads wrapper JAR if missing, then delegates
set -e
APP_HOME=`dirname "$0"`
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  mkdir -p "$APP_HOME/gradle/wrapper"
  curl -sSL "https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR" 2>/dev/null || \
  wget -q "https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar" -O "$WRAPPER_JAR" 2>/dev/null || true
fi
exec java -jar "$WRAPPER_JAR" "$@" 2>/dev/null || gradle "$@"