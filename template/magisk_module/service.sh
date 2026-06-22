#!/system/bin/sh

MODDIR=${0%/*}
LOG_DIR="/data/adb/sui"
LOG_FILE="$LOG_DIR/module_env.log"

mkdir -p "$LOG_DIR"
chmod 700 "$LOG_DIR"

{
  echo "time=$(date 2>/dev/null || true)"
  echo "MODDIR=$MODDIR"
  echo "KSU=${KSU:-false}"
  echo "KSU_VER=${KSU_VER:-}"
  echo "KSU_VER_CODE=${KSU_VER_CODE:-}"
  echo "KSU_KERNEL_VER_CODE=${KSU_KERNEL_VER_CODE:-}"
  echo "APATCH=${APATCH:-false}"
  echo "MAGISK_VER=${MAGISK_VER:-}"
  echo "MAGISK_VER_CODE=${MAGISK_VER_CODE:-}"
  echo "API=$(getprop ro.build.version.sdk)"
  echo "ABI=$(getprop ro.product.cpu.abi)"
} > "$LOG_FILE"

chmod 600 "$LOG_FILE"
