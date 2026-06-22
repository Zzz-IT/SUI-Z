#!/usr/bin/env bash
set -euo pipefail

BRIDGE="module/src/main/kotlin/rikka/sui/systemserver/BridgeService.kt"
CONFIG="module/src/main/kotlin/rikka/sui/server/SuiConfigManager.kt"
USER_SERVICE="module/src/main/kotlin/rikka/sui/server/SuiUserServiceManager.kt"
CMAKE="module/src/main/cpp/CMakeLists.txt"
SUI_MAIN="module/src/main/cpp/main/sui_main.hpp"

for file in "$BRIDGE" "$CONFIG" "$USER_SERVICE" "$CMAKE" "$SUI_MAIN"; do
  if [ ! -f "$file" ]; then
    echo "Missing required file: $file"
    exit 1
  fi
done

grep -q 'RETRY_MAX' "$BRIDGE"
grep -q 'RETRY_DELAY_MS' "$BRIDGE"
grep -q 'Keep upstream-compatible behavior' "$BRIDGE"
grep -q 'requestedBinder = rootServiceBinder' "$BRIDGE"

if grep -q 'permissionFlags &' "$BRIDGE"; then
  echo "BridgeService.kt uses Java-style bitwise &: use Kotlin 'and'"
  exit 1
fi

if grep -q 'append("\\\\n")' "$CONFIG"; then
  echo "SuiConfigManager writes literal \\n; use append('\\n')"
  exit 1
fi

if grep -q '\\$SHELL_CONFIG_FILENAME.tmp' "$CONFIG"; then
  echo "SuiConfigManager writes literal temp filename"
  exit 1
fi

if grep -q '\\$USER_SERVICE_CMD_DEBUG' "$USER_SERVICE"; then
  echo "SuiUserServiceManager has escaped USER_SERVICE_CMD_DEBUG"
  exit 1
fi

if grep -q '\\$processName' "$USER_SERVICE"; then
  echo "SuiUserServiceManager has escaped processName"
  exit 1
fi

if grep -q 'suiz_rust' "$CMAKE"; then
  echo "CMake links suiz_rust before Android Rust targets are ready"
  exit 1
fi

if grep -q 'suiz_validate_shell_dir_name' "$SUI_MAIN"; then
  echo "sui_main.hpp calls Rust FFI before Android Rust targets are ready"
  exit 1
fi

echo "static guards passed"
