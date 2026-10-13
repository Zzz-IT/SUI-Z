#!/system/bin/sh

MODDIR=${0%/*}

LOG_FILE="$MODDIR/sui.log"
TAG="SuiDaemon"

# Watchdog cadence.
#
# The previous implementation probed with `pidof` every 5 seconds. That is a
# fork+exec of a toolbox applet roughly 17,000 times per day, and every probe
# drags the CPU out of deep idle -- the wakeups cost far more than the few
# milliseconds of work they actually perform.
#
# This version:
#   * discovers the daemon pid once, then caches it in a shell variable;
#   * verifies the cached pid each cycle with the `read` builtin against /proc,
#     which needs neither fork nor exec;
#   * only falls back to the forking pid scan when the cache is stale;
#   * widens the steady-state interval from 5s to 120s.
#
# Net effect: ~17,000 process spawns/day -> ~720 sleeps/day, with no process
# scanning at all in the steady state.

CHECK_INTERVAL=120
FIRST_CHECK_INTERVAL=10
SETTLE_INTERVAL=10

# Guard against a crash loop hammering the device.
BOUNCE_LIMIT=5
BOUNCE_WINDOW=300

LOCK_DIR="/data/adb/sui/watchdog.lock"
LOCK_PID="$LOCK_DIR/pid"

# Test hook: SUI_TEST_ROOT relocates every absolute path this script touches so
# the watchdog can be driven by scripts/test_watchdog.sh on a build host.
# Unset (the normal case) gives exactly the production paths.
if [ -n "${SUI_TEST_ROOT:-}" ]; then
    PROC_ROOT="$SUI_TEST_ROOT/proc"
    LOCK_DIR="$SUI_TEST_ROOT/adb/sui/watchdog.lock"
    LOCK_PID="$LOCK_DIR/pid"
else
    PROC_ROOT="/proc"
fi

# print_log forks (`date` and `log` are external binaries), so it is only used
# on state transitions -- never on a healthy check cycle.
print_log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
    log -p i -t "$TAG" "$1"
}

# Verify a pid without spawning any process.
# Distinguishes the root server (`sui`) from the shell server (`sui_shell`).
is_sui_pid() {
    check_pid="$1"
    [ -n "$check_pid" ] || return 1
    [ -d "$PROC_ROOT/$check_pid" ] || return 1

    proc_comm=""
    if [ -r "$PROC_ROOT/$check_pid/comm" ]; then
        read -r proc_comm < "$PROC_ROOT/$check_pid/comm" 2>/dev/null
    fi
    if [ "$proc_comm" = "sui" ]; then
        return 0
    fi

    # Fallback where /proc/pid/comm is unavailable. app_process rewrites argv[0]
    # to the --nice-name value, so the first NUL-terminated field is the name.
    #
    # `tr` forks, unlike the comm path above, but it only runs where comm is
    # missing -- every Android kernel has exported it since 2.6.33 -- so this is
    # a defensive branch rather than a hot one. Note that `read` cannot be used
    # here: it splits on newline, and cmdline fields are NUL separated, so
    # `read` would yield the whole "sui\0--files-path=..." and never match.
    proc_cmdline="$(tr '\0' '\n' < "$PROC_ROOT/$check_pid/cmdline" 2>/dev/null | head -n 1)"
    [ "$proc_cmdline" = "sui" ] && return 0

    return 1
}

# Slow path: scan for the pid. Only runs when the cache is empty or stale.
discover_sui_pid() {
    if command -v pidof >/dev/null 2>&1; then
        pidof sui 2>/dev/null | awk 'NR == 1 { print $1 }'
        return
    fi

    ps -A 2>/dev/null | awk '$NF == "sui" { print $2; exit }'
}

start_sui() {
    chmod 700 "$MODDIR/bin/sui" 2>/dev/null
    nohup "$MODDIR/bin/sui" "$MODDIR" 0 >> "$LOG_FILE" 2>&1 &
}

if [ -n "${SUI_TEST_ROOT:-}" ]; then
    mkdir -p "$SUI_TEST_ROOT/adb/sui" 2>/dev/null
else
    mkdir -p "/data/adb/sui" 2>/dev/null
fi

# --- single-instance lock -------------------------------------------------
# Acquired in the parent shell, not inside the backgrounded subshell: `$$` in a
# subshell expands to the PARENT's pid, so a lock written from inside would
# always record an already-dead process and never detect a live peer. We take
# the pid from `$!` instead, which is the actual backgrounded watchdog.
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    if [ -f "$LOCK_PID" ]; then
        read -r lock_owner < "$LOCK_PID" 2>/dev/null
        if [ -n "$lock_owner" ] && [ -d "$PROC_ROOT/$lock_owner" ]; then
            exit 0
        fi
    fi
    rm -rf "$LOCK_DIR" 2>/dev/null
    mkdir "$LOCK_DIR" 2>/dev/null || exit 0
fi

(
    # post-fs-data.sh starts the daemon. Give it a moment to appear before the
    # watchdog considers taking over, otherwise a slow boot lets both scripts
    # launch it and we end up with two competing root servers.
    sleep "$FIRST_CHECK_INTERVAL"

    sui_pid=""
    bounce_count=0
    bounce_window_start=0

    while true; do
        if [ -n "$sui_pid" ] && is_sui_pid "$sui_pid"; then
            # Healthy. No fork, no exec, no log -- just idle.
            sleep "$CHECK_INTERVAL"
            continue
        fi

        # Cache missed or stale. Re-scan (the only forking path).
        sui_pid="$(discover_sui_pid)"
        if [ -n "$sui_pid" ] && is_sui_pid "$sui_pid"; then
            sleep "$CHECK_INTERVAL"
            continue
        fi
        sui_pid=""

        # No live daemon. Restart, unless we are bouncing too fast.
        now="$(date +%s)"
        if [ "$bounce_window_start" -eq 0 ] || [ $((now - bounce_window_start)) -gt "$BOUNCE_WINDOW" ]; then
            bounce_window_start="$now"
            bounce_count=0
        fi

        if [ "$bounce_count" -ge "$BOUNCE_LIMIT" ]; then
            print_log "Sui daemon restarted ${bounce_count} times within ${BOUNCE_WINDOW}s; backing off"
            sleep "$BOUNCE_WINDOW"
            bounce_window_start=0
            bounce_count=0
            continue
        fi

        bounce_count=$((bounce_count + 1))
        print_log "Sui daemon is not running, restarting (attempt ${bounce_count}/${BOUNCE_LIMIT})"
        start_sui

        sleep "$SETTLE_INTERVAL"

        sui_pid="$(discover_sui_pid)"
        if [ -n "$sui_pid" ] && is_sui_pid "$sui_pid"; then
            print_log "Sui daemon is running (pid: $sui_pid)"
            bounce_count=0
        else
            print_log "Sui daemon still not running after restart"
            sui_pid=""
        fi

        sleep "$CHECK_INTERVAL"
    done
) >/dev/null 2>&1 &

echo "$!" > "$LOCK_PID" 2>/dev/null

exit 0
