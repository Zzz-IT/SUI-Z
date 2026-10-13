#!/usr/bin/env bash
# Functional harness for template/magisk_module/service.sh.
#
# Runs the real watchdog script -- unmodified -- against a fake filesystem and a
# stubbed PATH, and asserts on what it does. Written for Git Bash / any POSIX
# shell on the build host; it never touches a device.
#
#   bash scripts/test_watchdog.sh
#
# How it works
# ------------
# service.sh backgrounds a subshell that loops forever calling `sleep`. The
# harness puts a stub `sleep` on PATH that records each requested duration and
# returns immediately, then stops the watchdog once the recorded count reaches
# SLEEP_BUDGET. That lets a few seconds on the host stand in for days of
# idling. `date`, `log`, `pidof`, `chmod` and `nohup` are stubbed the same way.
#
# Paths are relocated with SUI_TEST_ROOT (see the hook in service.sh), so /proc,
# /data/adb/sui and the module tree all live under a temp dir.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE="$ROOT/template/magisk_module/service.sh"

pass=0
fail=0
ok()   { printf '  PASS  %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf '  FAIL  %s\n' "$1"; fail=$((fail + 1)); }

if [ ! -f "$SERVICE" ]; then
    echo "cannot find $SERVICE" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Sandbox
# ---------------------------------------------------------------------------
SB=""

setup() {
    SB="$(mktemp -d)"
    mkdir -p "$SB/mod/bin" "$SB/proc" "$SB/adb/sui" "$SB/bin" "$SB/out"

    # service.sh derives MODDIR from $0, so it must live at the fake module root.
    cp "$SERVICE" "$SB/mod/service.sh"
    # A Windows checkout can hand us CRLF; the device shell would choke on it.
    sed -i 's/\r$//' "$SB/mod/service.sh"
    chmod +x "$SB/mod/service.sh"

    # --- stub: log ---------------------------------------------------------
    cat > "$SB/bin/log" <<EOF
#!/usr/bin/env bash
shift 2
echo "\$*" >> "$SB/out/log.out"
EOF

    # --- stub: date --------------------------------------------------------
    # +%s is the only form service.sh uses; pin it so bounce windows are
    # deterministic instead of depending on how fast the host runs.
    cat > "$SB/bin/date" <<'EOF'
#!/usr/bin/env bash
if [ "${1:-}" = "+%s" ]; then
    echo "${FAKE_NOW:-1000}"
else
    echo "2026-01-01 00:00:00"
fi
EOF

    # --- stub: chmod -------------------------------------------------------
    cat > "$SB/bin/chmod" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

    # --- stub: nohup -------------------------------------------------------
    # Backgrounds the child AND detaches its fds. Without `>/dev/null 2>&1` the
    # launched daemon would inherit the harness's stdout pipe, and any command
    # substitution in `run` would then block until that daemon exited.
    cat > "$SB/bin/nohup" <<'EOF'
#!/usr/bin/env bash
"$@" >/dev/null 2>&1 &
EOF

    chmod +x "$SB/bin/log" "$SB/bin/date" "$SB/bin/chmod" "$SB/bin/nohup"

    # --- stub: pidof -------------------------------------------------------
    # Reports whatever the harness registered as the live daemon, and counts
    # how often it was asked -- the pid cache should keep that to once.
    cat > "$SB/bin/pidof" <<EOF
#!/usr/bin/env bash
echo "pidof" >> "$SB/out/pidof.count"
[ "\${1:-}" = sui ] || exit 1
if [ -f "$SB/out/daemon_pid" ]; then
    cat "$SB/out/daemon_pid"
    exit 0
fi
exit 1
EOF
    chmod +x "$SB/bin/pidof"

    # --- stub: sleep -------------------------------------------------------
    # Records the requested duration, returns at once, and terminates the
    # watchdog once SLEEP_BUDGET sleeps have been recorded. Without that kill
    # the loop spins forever, because `sleep` is the only thing pacing it.
    #
    # It kills $PPID rather than reading the lock pid file: `sleep` is invoked
    # directly by the watchdog subshell, so $PPID is always that subshell. The
    # lock file, by contrast, is written by service.sh only AFTER backgrounding,
    # so early sleeps would find it missing and let the loop escape.
    #
    # `2>/dev/null` precedes `<` because redirections apply left to right.
    cat > "$SB/bin/sleep" <<EOF
#!/usr/bin/env bash
echo "\${1:-?}" >> "$SB/out/sleep_values"
n=\$(wc -l 2>/dev/null < "$SB/out/sleep_values" || echo 0)
if [ "\$n" -ge "\${SLEEP_BUDGET:-6}" ]; then
    kill "\$PPID" 2>/dev/null
fi
exit 0
EOF
    chmod +x "$SB/bin/sleep"

    # --- stub: the sui daemon ---------------------------------------------
    # REGISTER_DAEMON=1 makes a launch create a live fake process, so recovery
    # can be exercised. Default 0 keeps the daemon "crashing" on every launch.
    cat > "$SB/mod/bin/sui" <<EOF
#!/usr/bin/env bash
echo "launch" >> "$SB/out/start_sui.count"
if [ "\${REGISTER_DAEMON:-0}" = 1 ] && [ -n "\${NEXT_PID:-}" ]; then
    mkdir -p "$SB/proc/\$NEXT_PID"
    printf 'sui\\n' > "$SB/proc/\$NEXT_PID/comm"
    printf 'sui\\0' > "$SB/proc/\$NEXT_PID/cmdline"
    echo "\$NEXT_PID" > "$SB/out/daemon_pid"
fi
EOF
    chmod +x "$SB/mod/bin/sui"

    export SB
}

# Register a live daemon at $1 with the given /proc/<pid>/comm value.
add_daemon() {
    pid="$1"; comm="${2:-sui}"
    mkdir -p "$SB/proc/$pid"
    printf '%s\n' "$comm" > "$SB/proc/$pid/comm"
    printf '%s\0' "$comm" > "$SB/proc/$pid/cmdline"
    echo "$pid" > "$SB/out/daemon_pid"
}

# Drive the watchdog and stop it once SLEEP_BUDGET sleeps have been recorded.
# Env knobs: SLEEP_BUDGET, FAKE_NOW, REGISTER_DAEMON, NEXT_PID.
run() {
    # Invoke through an explicit shell. service.sh carries a `#!/system/bin/sh`
    # shebang, which does not exist off-device, so exec'ing it directly fails
    # with 126 and the suite would silently assert against an empty run.
    #
    # We run it under bash (POSIX-clean here: no local/declare/[[, asserted
    # below) and do not rely on `timeout`, whose -k flag is not portable.
    SLEEP_BUDGET="${SLEEP_BUDGET:-6}" \
    FAKE_NOW="${FAKE_NOW:-1000}" \
    SUI_TEST_ROOT="$SB" \
    SB="$SB" \
    NEXT_PID="${NEXT_PID:-}" \
    REGISTER_DAEMON="${REGISTER_DAEMON:-0}" \
    PATH="$SB/bin:$PATH" \
    bash "$SB/mod/service.sh" >"$SB/out/run.out" 2>&1

    # Wait for the sleep stub to hit its budget and kill the watchdog. The
    # polling sleep here is the REAL one: this loop runs in the harness shell,
    # whose PATH was not prefixed with the stub dir.
    i=0
    while [ "$i" -lt 500 ]; do
        if [ "$(count "$SB/out/sleep_values")" -ge "${SLEEP_BUDGET:-6}" ]; then
            break
        fi
        sleep 0.02
        i=$((i + 1))
    done
    # Belt and braces: kill it ourselves if the stub's kill did not land.
    lock="$SB/adb/sui/watchdog.lock/pid"
    if [ -f "$lock" ]; then
        kill "$(cat "$lock")" 2>/dev/null
    fi
    # Give the kill a moment to land before assertions read the recorded files.
    sleep 0.1
}

count() { if [ -f "$1" ]; then wc -l < "$1" | tr -d ' '; else echo 0; fi; }
has()   { [ -f "$1" ] && grep -qF -- "$2" "$1"; }

echo "=== service.sh watchdog ==="

# ---------------------------------------------------------------------------
echo "-- healthy daemon"
setup; add_daemon 4242
SLEEP_BUDGET=10 run
launches="$(count "$SB/out/start_sui.count")"
if [ "$launches" -eq 0 ]; then
    ok "does not restart a live daemon"
else
    bad "restarted a live daemon $launches times"
fi
if grep -qx '120' "$SB/out/sleep_values" 2>/dev/null; then
    ok "steady-state interval is 120s"
else
    bad "steady-state interval is not 120s: [$(tr '\n' ' ' < "$SB/out/sleep_values" 2>/dev/null)]"
fi
if grep -qx '5' "$SB/out/sleep_values" 2>/dev/null; then
    bad "old 5s poll interval still present"
else
    ok "old 5s poll interval is gone"
fi
pidof_n="$(count "$SB/out/pidof.count")"
if [ "$pidof_n" -le 1 ]; then
    ok "pidof called at most once (n=$pidof_n) -- pid cache works"
else
    bad "pidof called $pidof_n times -- pid cache is not being used"
fi

# ---------------------------------------------------------------------------
echo "-- dead daemon"
setup
SLEEP_BUDGET=10 run
launches="$(count "$SB/out/start_sui.count")"
if [ "$launches" -ge 1 ]; then
    ok "restarts a dead daemon ($launches launch)"
else
    bad "did not restart a dead daemon"
fi
if has "$SB/out/log.out" 'Sui daemon is not running'; then
    ok "logs the restart"
else
    bad "restart was not logged"
fi

# ---------------------------------------------------------------------------
echo "-- recovery after launch"
setup
REGISTER_DAEMON=1 NEXT_PID=5150 SLEEP_BUDGET=10 run
if has "$SB/out/log.out" 'Sui daemon is running (pid: 5150)'; then
    ok "detects the daemon it launched"
else
    bad "did not detect the daemon it launched"
fi

# ---------------------------------------------------------------------------
echo "-- shell server is not the root daemon"
setup; add_daemon 999 'sui_shell'
SLEEP_BUDGET=10 run
if [ "$(count "$SB/out/start_sui.count")" -ge 1 ]; then
    ok "restarts when only sui_shell is alive"
else
    bad "mistook sui_shell for the root daemon sui"
fi

# ---------------------------------------------------------------------------
echo "-- cmdline fallback (no /proc/pid/comm)"
setup
mkdir -p "$SB/proc/777"
printf 'sui\0--files-path=/x\0' > "$SB/proc/777/cmdline"
echo 777 > "$SB/out/daemon_pid"
SLEEP_BUDGET=10 run
if [ "$(count "$SB/out/start_sui.count")" -eq 0 ]; then
    ok "recognises pid 777 via cmdline"
else
    bad "cmdline fallback failed to recognise pid 777"
fi

# ---------------------------------------------------------------------------
echo "-- crash-loop backoff"
setup
SLEEP_BUDGET=30 run
launches="$(count "$SB/out/start_sui.count")"
sleeps="$(count "$SB/out/sleep_values")"
if has "$SB/out/log.out" 'backing off'; then
    ok "logs the backoff"
else
    bad "backoff was not logged (launches=$launches sleeps=$sleeps)"
fi
if grep -qx '300' "$SB/out/sleep_values" 2>/dev/null; then
    ok "backoff sleeps 300s"
else
    bad "backoff did not sleep 300s"
fi
# Without the cap, every loop iteration would launch again: launches ~= sleeps.
# With it, restarts are gated to BOUNCE_LIMIT per window.
if [ "$sleeps" -gt 0 ] && [ "$launches" -lt "$sleeps" ]; then
    ok "restarts are gated, not 1:1 with cycles (launches=$launches < sleeps=$sleeps)"
else
    bad "restarts are unbounded (launches=$launches, sleeps=$sleeps)"
fi

# ---------------------------------------------------------------------------
echo "-- lock file records the watchdog pid"
setup; add_daemon 4242
SLEEP_BUDGET=6 run
lock="$SB/adb/sui/watchdog.lock/pid"
if [ -f "$lock" ] && [ -s "$lock" ]; then
    ok "lock pid file written ($(cat "$lock"))"
else
    bad "lock pid file missing or empty"
fi

# ---------------------------------------------------------------------------
echo "-- production paths and shell portability"
if grep -qE 'LOCK_DIR="/data/adb/sui/watchdog.lock"' "$SERVICE" \
   && grep -qE 'PROC_ROOT="/proc"' "$SERVICE"; then
    ok "defaults point at /data/adb/sui and /proc"
else
    bad "production default paths changed"
fi
if grep -qE '\blocal\b|\bdeclare\b|\[\[|function [a-z]' "$SERVICE"; then
    bad "uses bash-only syntax; /system/bin/sh is mksh, not bash"
else
    ok "POSIX-only syntax (no local/declare/[[)"
fi

# ---------------------------------------------------------------------------
echo "-- line endings"
crlf=0
for f in "$SERVICE" "$ROOT/template/magisk_module/post-fs-data.sh" "$ROOT/scripts/test_watchdog.sh"; do
    if grep -qU $'\r' "$f" 2>/dev/null; then
        crlf=$((crlf + 1))
        bad "CRLF found in $(basename "$f")"
    fi
done
[ "$crlf" -eq 0 ] && ok "no CRLF in the edited shell scripts"

echo
echo "=== $pass passed, $fail failed ==="
[ "$fail" -eq 0 ]
