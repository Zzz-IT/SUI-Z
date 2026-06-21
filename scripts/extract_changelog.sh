#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
CHANGELOG_FILE="${2:-CHANGELOG.md}"

if [ -z "$VERSION" ]; then
  echo "Usage: $0 v1.0.0 [CHANGELOG.md]" >&2
  exit 1
fi

if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Error: $CHANGELOG_FILE not found" >&2
  exit 1
fi

TMP_FILE="$(mktemp)"
trap 'rm -f "$TMP_FILE"' EXIT

awk -v version="$VERSION" '
BEGIN {
  found = 0
}
$0 ~ "^##[[:space:]]+(\\[" version "\\]|" version ")([[:space:]]|$)" {
  found = 1
  next
}
found && $0 ~ "^##[[:space:]]+" {
  exit
}
found {
  print
}
END {
  if (!found) {
    exit 2
  }
}
' "$CHANGELOG_FILE" > "$TMP_FILE"

if [ ! -s "$TMP_FILE" ]; then
  echo "Error: changelog section for $VERSION is empty" >&2
  exit 1
fi

cat "$TMP_FILE"
