#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

BUILD_DIR=build
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/main" "$BUILD_DIR/test"

echo "== Checking forbidden APIs in src/ =="
if grep -rn "Thread\.sleep" src/; then
    echo "ERROR: Thread.sleep is not allowed in src/" >&2
    exit 1
fi

echo "== Compiling main sources (JDK 8 syntax) =="
find src -name '*.java' | sort > "$BUILD_DIR/main-sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -d "$BUILD_DIR/main" @"$BUILD_DIR/main-sources.txt"

echo "== Compiling tests =="
find test -name '*.java' | sort > "$BUILD_DIR/test-sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -cp "$BUILD_DIR/main" -d "$BUILD_DIR/test" @"$BUILD_DIR/test-sources.txt"

echo "== Running tests =="
java -ea -cp "$BUILD_DIR/main:$BUILD_DIR/test" com.gsb.resilience.ResilienceTestRunner
