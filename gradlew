#!/bin/sh

##############################################################################
# Gradle wrapper script for UNIX-like systems
##############################################################################

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DIRNAME=$(dirname "$0")
cd "$DIRNAME" || exit

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

warn() {
    echo "$*"
}

die() {
    echo "$*"
    exit 1
}

# Determine Java command to use
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no “java” command could be found."
fi

# Gradle wrapper jar
WRAPPER_JAR="$DIRNAME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Gradle wrapper…"
    mkdir -p "$DIRNAME/gradle/wrapper"
    curl -sL -o "$WRAPPER_JAR" "https://raw.githubusercontent.com/gradle/gradle/v8.5.0/gradle/wrapper/gradle-wrapper.jar" || \
        die "ERROR: Could not download gradle-wrapper.jar"
fi

exec "$JAVACMD" \
    -Xmx64m \
    -Xms64m \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"