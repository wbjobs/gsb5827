#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

BUILD_DIR=build
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

SOURCES=$(find src test -name '*.java' | sort)

# Enforce JDK 8 language level and API when supported (javac >= 9).
if javac --release 8 -d /tmp/gsb-release-check src/com/gsb/resilience/Clock.java >/dev/null 2>&1; then
    rm -rf /tmp/gsb-release-check
    javac --release 8 -encoding UTF-8 -d "$BUILD_DIR" $SOURCES
else
    javac -source 8 -target 8 -encoding UTF-8 -d "$BUILD_DIR" $SOURCES
fi

java -cp "$BUILD_DIR" com.gsb.resilience.ExecutorTest
