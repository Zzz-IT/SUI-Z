#!/usr/bin/env bash
set -euo pipefail

BRIDGE="module/src/main/kotlin/rikka/sui/systemserver/BridgeService.kt"
CONFIG="module/src/main/kotlin/rikka/sui/server/SuiConfigManager.kt"
USER_SERVICE="module/src/main/kotlin/rikka/sui/server/SuiUserServiceManager.kt"
CMAKE="module/src/main/cpp/CMakeLists.txt"
SUI_MAIN="module/src/main/cpp/main/sui_main.hpp"
RUST_FFI="module/src/main/rust/suiz-ffi/src/lib.rs"

for file in "$BRIDGE" "$CONFIG" "$USER_SERVICE" "$CMAKE" "$SUI_MAIN" "$RUST_FFI"; do
  if [ ! -f "$file" ]; then
    echo "Missing required file: $file"
    exit 1
  fi
done

grep -q 'RETRY_MAX' "$BRIDGE" || { echo "BridgeService.kt missing RETRY_MAX"; exit 1; }
grep -q 'RETRY_DELAY_MS' "$BRIDGE" || { echo "BridgeService.kt missing RETRY_DELAY_MS"; exit 1; }
grep -q 'Keep upstream-compatible behavior' "$BRIDGE" || { echo "BridgeService.kt missing getBinder compatibility comment"; exit 1; }
grep -q 'requestedBinder = rootServiceBinder' "$BRIDGE" || { echo "BridgeService.kt missing root binder fallback"; exit 1; }

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

if grep -q 'service\.clientManager' "$CONFIG"; then
  echo "SuiConfigManager accesses private Java field service.clientManager; use service.getClientManager()"
  exit 1
fi

grep -q 'service.getClientManager()' "$CONFIG" || {
  echo "SuiConfigManager should use service.getClientManager() when refreshing shell clients"
  exit 1
}

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

grep -q 'pub unsafe extern "C" fn suiz_validate_shell_dir_name' "$RUST_FFI" || {
  echo "Rust FFI export suiz_validate_shell_dir_name should be unsafe extern C"
  exit 1
}

grep -q '# Safety' "$RUST_FFI" || {
  echo "Rust FFI unsafe exports should document # Safety"
  exit 1
}

echo "static guards passed"
