#!/usr/bin/env bash
# SUI Z power / memory baseline sampler.
#
# Samples the module's on-device cost over a window and writes a CSV that can be
# diffed between builds. Every figure is a DELTA over the sampling window;
# absolute RSS/PSS numbers are meaningless on their own.
#
#   adb shell < scripts/baseline.sh              # quick 60s check
#   adb shell "DURATION=1800" < scripts/baseline.sh
#
# Output columns
#   target, window_s, pss_kb, cpu_jiffies, cpu_pct, minor_faults,
#   cpu_runtime_ms, vol_ctxsw, proc_spawns
#
# Why these metrics
#   pss_kb      proportional memory; the closest thing to "what this module
#               actually costs the system"
#   cpu_jiffies raw utime+stime delta, independent of sampling error
#   minor_faults page faults; a rising count at idle means the process is
#               touching memory it has no reason to touch
#   vol_ctxsw   voluntary context switches. A correctly sleeping process
#               accumulates these; a busy-polling one does not. This is what
#               distinguishes "asleep" from "spinning" in a single number.
#   proc_spawns forks on the whole system during the window -- a proxy for the
#               watchdog's spawn cost, which scripts/test_watchdog.sh guards.

set -u

DURATION="${DURATION:-60}"
INTERVAL="${INTERVAL:-5}"
OUT="${OUT:-/data/local/tmp/sui_baseline.csv}"

TARGETS="sui sui_shell"

# /proc/<pid>/stat looks like:  pid (comm) state ppid ...
# comm may contain spaces and parentheses, so everything up to the LAST ") "
# must be discarded before the remaining fields can be counted by position.
# After that strip, $1 is `state`, which is field 3 of the original, so
# original field N maps to ${N-2}.
stat_fields() {
    local line
    line="$(cat "/proc/$1/stat" 2>/dev/null)" || return 1
    printf '%s' "${line#*) }"
}

stat_cpu_jiffies() {
    # fields 14 (utime) + 15 (stime) -> $12 + $13
    set -- $(stat_fields "$1") || return 1
    echo $(( ${12} + ${13} ))
}

stat_minflt() {
    # field 10 -> $8
    set -- $(stat_fields "$1") || return 1
    echo "${8}"
}

stat_nvcsw() {
    # field 40 (nvcsw) -> $38
    set -- $(stat_fields "$1") || return 1
    echo "${38}"
}

read_pss_kb() {
    # smaps_rollup is far cheaper than smaps when present.
    if [ -r "/proc/$1/smaps_rollup" ]; then
        awk '/^Pss:/ { s += $2 } END { printf "%d\n", s + 0 }' \
            "/proc/$1/smaps_rollup" 2>/dev/null
    else
        awk '/^Pss:/ { s += $2 } END { printf "%d\n", s + 0 }' \
            "/proc/$1/smaps" 2>/dev/null
    fi
}

find_pid() {
    local want="$1" pid
    for pid in /proc/[0-9]*; do
        pid="${pid#/proc/}"
        [ -r "/proc/$pid/comm" ] || continue
        if [ "$(cat "/proc/$pid/comm" 2>/dev/null)" = "$want" ]; then
            echo "$pid"
            return 0
        fi
    done
    return 1
}

read_proc_spawns() {
    awk '/^processes / { print $2 }' /proc/stat 2>/dev/null
}

if ! : > "$OUT" 2>/dev/null; then
    echo "cannot write $OUT" >&2
    exit 1
fi
echo "target,window_s,pss_kb,cpu_jiffies,cpu_pct,minor_faults,cpu_runtime_ms,vol_ctxsw,proc_spawns" > "$OUT"

echo "Sampling ${TARGETS} for ${DURATION}s every ${INTERVAL}s -> $OUT" >&2

hz="$(getconf CLK_TCK 2>/dev/null)"
case "$hz" in ''|*[!0-9]*) hz=100 ;; esac

for target in $TARGETS; do
    if ! pid="$(find_pid "$target")"; then
        echo "  $target: not running, skipped" >&2
        continue
    fi

    t0="$(date +%s)"
    j0="$(stat_cpu_jiffies "$pid")"
    f0="$(stat_minflt "$pid")"
    c0="$(stat_nvcsw "$pid")"
    n0="$(read_proc_spawns)"

    elapsed=0
    while [ "$elapsed" -lt "$DURATION" ]; do
        sleep "$INTERVAL"
        elapsed=$(( elapsed + INTERVAL ))
    done

    t1="$(date +%s)"
    j1="$(stat_cpu_jiffies "$pid")"
    f1="$(stat_minflt "$pid")"
    c1="$(stat_nvcsw "$pid")"
    n1="$(read_proc_spawns)"

    window=$(( t1 - t0 ))
    [ "$window" -gt 0 ] || window=1

    delta() { d=$(( $1 - $2 )); [ "$d" -ge 0 ] && echo "$d" || echo 0; }
    dj="$(delta "${j1:-0}" "${j0:-0}")"
    df="$(delta "${f1:-0}" "${f0:-0}")"
    dc="$(delta "${c1:-0}" "${c0:-0}")"
    dn="$(delta "${n1:-0}" "${n0:-0}")"

    cpu_ms=$(( dj * 1000 / hz ))
    cpu_pct="$(awk -v j="$dj" -v h="$hz" -v w="$window" \
        'BEGIN { printf "%.2f", (j / h) / w * 100 }')"

    pss="$(read_pss_kb "$pid")"

    echo "$target,$window,$pss,$dj,$cpu_pct,$df,$cpu_ms,$dc,$dn" >> "$OUT"
    echo "  $target: pid=$pid pss=${pss}KB cpu=${cpu_pct}% (${cpu_ms}ms) faults=$df ctxsw=$dc sys_spawns=$dn" >&2
done

echo "" >&2
echo "Done.  adb pull $OUT" >&2
cat "$OUT" >&2
