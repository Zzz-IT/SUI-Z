#!/system/bin/sh
MODDIR=${0%/*}
MODULE_ID=$(basename "$MODDIR")

SUI_DIR="/data/system/sui"
API_LEVEL=$(getprop ro.build.version.sdk)

if [ "$API_LEVEL" -lt 26 ] && [ -d "$SUI_DIR" ]; then
    rm -rf "$SUI_DIR"
fi

if [ "$API_LEVEL" -le 27 ]; then
    mkdir -p "$SUI_DIR/oat"
    chown -R 1000:1000 "$SUI_DIR"
    chmod 771 "$SUI_DIR"
    chmod 777 "$SUI_DIR/oat"
fi

if [ "$API_LEVEL" -lt 26 ]; then
    rm -f "$SUI_DIR/sui.dex"
    cp "$MODDIR/sui.apk" "$SUI_DIR/sui.apk"
    chmod 644 "$SUI_DIR/sui.apk"

    if [ "$API_LEVEL" -le 23 ]; then
        # On API 23 and below, ART regenerates files under oat/ with a restrictive
        # mode, and the injected dex cannot be loaded until they are readable.
        #
        # The original code polled this at 10 Hz forever. That is 864,000
        # `chmod -R` traversals per day, on every device running this module,
        # long after oat/ has settled.
        #
        # Now: fix it once, then re-apply on a 2s cadence for a bounded boot
        # window and exit. Worst case is 300 traversals instead of unbounded.
        (
            OAT_POLL_INTERVAL=2
            OAT_POLL_MAX=300
            oat_polls=0

            if [ -d "$SUI_DIR/oat" ]; then
                chmod -R a+rX "$SUI_DIR/oat" 2>/dev/null
            fi

            while [ "$oat_polls" -lt "$OAT_POLL_MAX" ]; do
                sleep "$OAT_POLL_INTERVAL"
                if [ -d "$SUI_DIR/oat" ]; then
                    chmod -R a+rX "$SUI_DIR/oat" 2>/dev/null
                fi
                oat_polls=$((oat_polls + 1))
            done

            log -p i -t "Sui" "oat permission watchdog finished after ${oat_polls} polls"
        ) &
    fi

    restorecon -R "$SUI_DIR"
    chcon -R u:object_r:system_data_file:s0 "$SUI_DIR"

elif [ "$API_LEVEL" -le 27 ]; then
    cp "$MODDIR/sui.dex" "$SUI_DIR/sui.dex"
    chmod 644 "$SUI_DIR/sui.dex"
    cp "$MODDIR/sui.apk" "$SUI_DIR/sui.apk"
    chmod 644 "$SUI_DIR/sui.apk"
    chcon -R u:object_r:system_file:s0 "$SUI_DIR"
fi

if [ "$ZYGISK_ENABLED" = false ]; then
  log -p w -t "Sui" "Zygisk is disabled"
  exit 1
fi

if [ "$KSU" = true ]; then
  log -p i -t "Sui" "KernelSU ksud version $KSU_VER ($KSU_VER_CODE)"
  log -p i -t "Sui" "KernelSU kernel version $KSU_KERNEL_VER_CODE"
  apply_sepolicy() {
    ksud sepolicy apply "$1"
  }
elif [ "$KERNELPATCH" = true ]; then
  # ${var:0:1} is a bash/mksh extension, not POSIX: /system/bin/sh is mksh today, but anywhere
  # else the slicing silently produced empty strings and printf then got no argument.
  kp_version="$KERNELPATCH_VERSION"
  kp_major_char="$(printf '%s' "$kp_version" | cut -c1)"
  kp_minor_patch="$(printf '%s' "$kp_version" | cut -c2-)"

  log -p i -t "Sui" "APatch version $APATCH_VER ($APATCH_VER_CODE)"
  if [ -n "$kp_major_char" ] && [ -n "$kp_minor_patch" ]; then
    kp_major=$(( $(printf '%d' "'$kp_major_char") - 97 ))
    kp_minor=$(( 10 + $(printf '%s' "$kp_minor_patch" | cut -c1) ))
    kp_patch="$(printf '%s' "$kp_minor_patch" | cut -c2-)"
    log -p i -t "Sui" "KernelPatch version $kp_major.$kp_minor.$kp_patch"
  else
    log -p w -t "Sui" "KernelPatch version is empty or malformed: [$kp_version]"
  fi
  apply_sepolicy() {
    apd sepolicy apply "$1"
  }
else
  MAGISK_VER_CODE=$(magisk -V 2>/dev/null)

  log -p i -t "Sui" "Magisk version $MAGISK_VER_CODE"
  apply_sepolicy() {
    magiskpolicy --live --apply "$1" 2>/dev/null
  }
fi

log -p i -t "Sui" "Module path $MODDIR"

enable_once="/data/adb/sui/enable_adb_root_once"
enable_forever="/data/adb/sui/enable_adb_root"
adb_root_exit=0

# Quoted: an unquoted expansion word-splits and glob-expands, so a path containing a space
# (or a stray "*" in the directory) made the test and the rm act on the wrong file.
if [ -f "$enable_once" ]; then
  log -p i -t "Sui" "adb root support is enabled for this time of boot"
  rm -f "$enable_once"
  enable_adb_root=true
fi

if [ -f "$enable_forever" ]; then
  log -p i -t "Sui" "adb root support is enabled forever"
  enable_adb_root=true
fi

if [ "$enable_adb_root" = true ]; then
  log -p i -t "Sui" "Setup adb root support"

  # Make sure sepolicy.rule be loaded
  chmod 755 "$MODDIR/sepolicy_checker"
  if ! "$MODDIR/sepolicy_checker"; then
    log -p e -t "Sui" "RootImpl does not load sepolicy.rule..."
    log -p e -t "Sui" "Try to load it..."
    apply_sepolicy "$MODDIR"/sepolicy.rule
    log -p i -t "Sui" "Apply finished"
  else
    log -p i -t "Sui" "RootImpl should have loaded sepolicy.rule correctly"
  fi

  # Setup adb root support
  rm "$MODDIR/bin/adb_root"
  ln -s "$MODDIR/bin/sui" "$MODDIR/bin/adb_root"
  chmod 700 "$MODDIR/bin/adb_root"
  "$MODDIR/bin/adb_root" "$MODDIR"
  adb_root_exit=$?
  log -p i -t "Sui" "Exited with $adb_root_exit"
else
  log -p i -t "Sui" "adb root support is disabled"
fi

# Setup uninstaller
rm "$MODDIR/bin/uninstall"
ln -s "$MODDIR/bin/sui" "$MODDIR/bin/uninstall"

# Run Sui server
chmod 700 "$MODDIR"/bin/sui

# define print_log
print_log() {
    echo "[$(date)] $1" >> "$MODDIR/sui.log"
    log -p i -t "SuiDaemon" "$1"
}


print_log "Starting Sui native daemon..."

# strat the sui daemon
nohup "$MODDIR"/bin/sui "$MODDIR" "$adb_root_exit" >> "$MODDIR/sui.log" 2>&1 &

print_log "Sui daemon launched with PID $!"

exit 0
