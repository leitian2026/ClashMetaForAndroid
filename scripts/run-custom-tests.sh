#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP_ROOT="${RUNNER_TEMP:-/tmp}"
CACHE_DIR="$TEMP_ROOT/kotlin-compiler-2.1.0"
ARCHIVE="$TEMP_ROOT/kotlin-compiler-2.1.0.zip"
OUT_DIR="$TEMP_ROOT/cmfa-custom-tests"
mkdir -p "$OUT_DIR"

if [ -n "${KOTLINC:-}" ]; then
  compiler="$KOTLINC"
else
  compiler="$CACHE_DIR/kotlinc/bin/kotlinc"
  if [ ! -x "$compiler" ]; then
    mkdir -p "$CACHE_DIR"
    curl -fL --retry 5 -o "$ARCHIVE" "https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip"
    unzip -q -o "$ARCHIVE" -d "$CACHE_DIR"
  fi
fi

"$compiler" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/YamlLint.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/YamlPairs.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/TextSearch.kt" \
  "$ROOT/app/src/main/java/com/github/kr328/clash/custom/RestoreCarrierSwitchPolicy.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchTogglePolicy.kt" \
  "$ROOT"/tests/custom/*.kt \
  -include-runtime -d "$OUT_DIR/custom-logic-tests.jar"

java -jar "$OUT_DIR/custom-logic-tests.jar"
