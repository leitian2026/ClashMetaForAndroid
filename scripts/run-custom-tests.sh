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

# 源文件列表和触发路径要保持一致
"$compiler" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/YamlLint.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/YamlRename.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/YamlPairs.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/editor/TextSearch.kt" \
  "$ROOT/app/src/main/java/com/github/kr328/clash/custom/RestoreCarrierSwitchPolicy.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchTogglePolicy.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchModel.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchRename.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/ConfigProxyCount.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/NetworkIdentitySnapshot.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierOperatorConsensus.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchTargetCheck.kt" \
  "$ROOT/design/src/main/java/com/github/kr328/clash/design/custom/SavedIpSortVisibility.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/CarrierSwitchMatcher.kt" \
  "$ROOT/common/src/main/java/com/github/kr328/clash/common/custom/UptimeFormat.kt" \
  "$ROOT/service/src/main/java/com/github/kr328/clash/service/custom/NotificationDisplayPolicy.kt" \
  "$ROOT/tests/custom/CarrierOperatorConsensusTests.kt" \
  "$ROOT/tests/custom/CarrierSwitchRenameTests.kt" \
  "$ROOT/tests/custom/ConfigProxyCountTests.kt" \
  "$ROOT/tests/custom/SavedIpSortVisibilityTests.kt" \
  "$ROOT/tests/custom/CarrierSwitchMatcherTests.kt" \
  "$ROOT/tests/custom/CarrierSwitchTargetCheckTests.kt" \
  "$ROOT/tests/custom/RestoreCarrierSwitchTests.kt" \
  "$ROOT/tests/custom/TestMain.kt" \
  "$ROOT/tests/custom/UptimeFormatTests.kt" \
  "$ROOT/tests/custom/NotificationDisplayPolicyTests.kt" \
  "$ROOT/tests/custom/TextSearchTests.kt" \
  "$ROOT/tests/custom/YamlLintTests.kt" \
  "$ROOT/tests/custom/YamlRenameTests.kt" \
  "$ROOT/tests/custom/YamlPairsTests.kt" \
  -include-runtime -d "$OUT_DIR/custom-logic-tests.jar"

java -jar "$OUT_DIR/custom-logic-tests.jar"
