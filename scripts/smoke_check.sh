#!/usr/bin/env bash
set -euo pipefail

ZIP_FILE="${1:-}"
EXPECTED_TAG="${2:-}"

if [ -z "$ZIP_FILE" ] || [ ! -f "$ZIP_FILE" ]; then
  echo "Usage: $0 path/to/module.zip [expected-tag]"
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -q "$ZIP_FILE" -d "$TMP_DIR"

test -f "$TMP_DIR/module.prop" || { echo "Error: missing module.prop"; exit 1; }
test -f "$TMP_DIR/sui.dex" || { echo "Error: missing sui.dex"; exit 1; }
test -f "$TMP_DIR/sui.apk" || { echo "Error: missing sui.apk"; exit 1; }

test -s "$TMP_DIR/sui.dex" || { echo "Error: empty sui.dex"; exit 1; }
test -s "$TMP_DIR/sui.apk" || { echo "Error: empty sui.apk"; exit 1; }

grep -q '^id=zygisk-suiz$' "$TMP_DIR/module.prop" || {
  echo "Error: module.prop id mismatch"
  grep '^id=' "$TMP_DIR/module.prop" || true
  exit 1
}

grep -q '^name=Zygisk - SUI Z$' "$TMP_DIR/module.prop" || {
  echo "Error: module.prop name mismatch"
  grep '^name=' "$TMP_DIR/module.prop" || true
  exit 1
}

if grep -q 'xiaotong6666.github.io/Sui' "$TMP_DIR/module.prop"; then
  echo "Error: old updateJson detected"
  exit 1
fi

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  test -f "$TMP_DIR/lib/$abi/libsui.so" || { echo "Error: missing lib/$abi/libsui.so"; exit 1; }
  test -f "$TMP_DIR/lib/$abi/libmain.so" || { echo "Error: missing lib/$abi/libmain.so"; exit 1; }
  test -f "$TMP_DIR/lib/$abi/libadbd_wrapper.so" || { echo "Error: missing lib/$abi/libadbd_wrapper.so"; exit 1; }
  test -f "$TMP_DIR/lib/$abi/libadbd_preload.so" || { echo "Error: missing lib/$abi/libadbd_preload.so"; exit 1; }
  test -f "$TMP_DIR/lib/$abi/libsepolicy_checker.so" || { echo "Error: missing lib/$abi/libsepolicy_checker.so"; exit 1; }
done

if [ -n "$EXPECTED_TAG" ]; then
  if ! grep -q "^version=.*${EXPECTED_TAG#v}" "$TMP_DIR/module.prop"; then
    echo "Error: module.prop version does not contain ${EXPECTED_TAG#v}"
    grep '^version=' "$TMP_DIR/module.prop" || true
    exit 1
  fi
fi

echo "smoke check passed: $ZIP_FILE"
