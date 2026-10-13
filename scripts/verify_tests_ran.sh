#!/usr/bin/env bash
# Fail if a unit test task executed zero test cases.
#
# Gradle reports "No tests found for given includes" as BUILD SUCCESSFUL. That
# is exactly the failure mode this project hit: .github/workflows/unit-tests.yml
# ran `:module:testDebugUnitTest` for a long time with no test sources at all,
# so the job was green on every commit while verifying nothing.
#
# This script reads the JUnit XML the test task emitted and counts the `tests`
# attribute. Zero means the run was vacuous, and CI should fail.
#
#   bash scripts/verify_tests_ran.sh <module-dir> <task-name>
#   bash scripts/verify_tests_ran.sh module testDebugUnitTest

set -uo pipefail

MODULE="${1:-}"
TASK="${2:-}"

if [ -z "$MODULE" ] || [ -z "$TASK" ]; then
    echo "Usage: $0 <module-dir> <task-name>" >&2
    echo "  e.g. $0 module testDebugUnitTest" >&2
    exit 1
fi

if [ ! -d "$MODULE" ]; then
    echo "Error: module directory not found: $MODULE" >&2
    exit 1
fi

# AGP writes results to build/test-results/<task>; Gradle's own layout is
# build/outputs/unitTest/<task>.bin/results. Look in both so we do not depend on
# which one the installed AGP version happens to pick.
CANDIDATES=(
    "$MODULE/build/test-results/$TASK"
    "$MODULE/build/outputs/unitTest/$TASK"
)

RESULT_DIR=""
for d in "${CANDIDATES[@]}"; do
    if [ -d "$d" ]; then
        RESULT_DIR="$d"
        break
    fi
done

if [ -z "$RESULT_DIR" ]; then
    echo "Error: no test result directory found for $TASK." >&2
    echo "  Looked for:" >&2
    printf '    %s\n' "${CANDIDATES[@]}" >&2
    echo "  Did the test task actually run?" >&2
    exit 1
fi

shopt -s nullglob
xml_files=("$RESULT_DIR"/*.xml)
shopt -u nullglob

if [ "${#xml_files[@]}" -eq 0 ]; then
    echo "Error: $TASK produced no JUnit XML in $RESULT_DIR." >&2
    echo "  A test run with no results is not a passing test run." >&2
    exit 1
fi

# Sum the tests="N" attribute across every suite file.
total=0
for f in "${xml_files[@]}"; do
    n="$(sed -n 's/.*<testsuite[^>]*tests="\([0-9][0-9]*\)".*/\1/p' "$f" | head -n 1)"
    case "$n" in
        ''|*[!0-9]*) n=0 ;;
    esac
    total=$(( total + n ))
done

echo "$TASK: ${#xml_files[@]} suite file(s), $total test(s) executed"

if [ "$total" -eq 0 ]; then
    echo "" >&2
    echo "Error: $TASK executed zero tests." >&2
    echo "" >&2
    echo "  Either no test sources were discovered, or they were not on the" >&2
    echo "  test source set. Gradle treats 'no tests found' as success, so" >&2
    echo "  without this check CI stays green while verifying nothing." >&2
    exit 1
fi

exit 0
