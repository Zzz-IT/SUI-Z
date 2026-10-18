#!/usr/bin/env bash
set -euo pipefail

MODE="extract"
case "${1:-}" in
  --check) MODE="check"; shift ;;
  --notes) MODE="notes"; shift ;;
esac

VERSION="${1:-}"
CHANGELOG_FILE="${2:-CHANGELOG.md}"

if [ -z "$VERSION" ]; then
  echo "Usage: $0 [--check|--notes] v1.0.0 [CHANGELOG.md]" >&2
  exit 1
fi

if [ ! -f "$CHANGELOG_FILE" ]; then
  echo "Error: $CHANGELOG_FILE not found" >&2
  exit 1
fi

# Prints the body of the `## <version>` section.
# Exit status 2 means no such section; 0 means it exists (it may be empty).
section_body() {
  local version="$1"
  awk -v version="$version" '
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
  ' "$CHANGELOG_FILE"
}

# Prints the short summary that the KernelSU update dialog shows: the
# `> ...` block placed directly under the `## <version>` heading, i.e.
# before the first `###`. Keep that block to one or two lines -- it is
# rendered as plain text in a narrow dialog, so a URL or a full changelog
# turns into an unreadable wall of text.
notes_body() {
  awk -v version="$1" '
    $0 ~ "^##[[:space:]]+(\\[" version "\\]|" version ")([[:space:]]|$)" {
      found = 1
      next
    }
    found && $0 ~ "^#{2,3}[[:space:]]+" {
      exit
    }
    found && /^[[:space:]]*>/ {
      line = $0
      sub(/^[[:space:]]*>+[[:space:]]*/, "", line)
      if (line != "") {
        print line
      }
    }
  ' "$CHANGELOG_FILE"
}

# A prerelease tag (v1.2.3-rc.4) carries the notes of its base version
# (## v1.2.3). Without this fallback every rc bump forces an edit to
# CHANGELOG.md, and forgetting it fails the release only after Gradle setup.
BASE_VERSION=""
case "$VERSION" in
  *-*) BASE_VERSION="${VERSION%-*}" ;;
esac

TMP_FILE="$(mktemp)"
trap 'rm -f "$TMP_FILE"' EXIT

SECTION=""
if section_body "$VERSION" > "$TMP_FILE"; then
  SECTION="$VERSION"
elif [ -n "$BASE_VERSION" ] && section_body "$BASE_VERSION" > "$TMP_FILE"; then
  SECTION="$BASE_VERSION"
fi

if [ -z "$SECTION" ]; then
  if [ "$MODE" = "check" ]; then
    echo "Error: CHANGELOG.md has no section heading for tag ${VERSION}" >&2
    if [ -n "$BASE_VERSION" ]; then
      echo "  Tried '## ${VERSION}' and the base version '## ${BASE_VERSION}'." >&2
    else
      echo "  Expected a line: ## ${VERSION}" >&2
    fi
    echo "  Top-most section heading:" >&2
    grep -m1 -E '^##[[:space:]]+' "$CHANGELOG_FILE" | sed 's/^/    /' >&2 || true
    exit 2
  fi
  echo "Error: changelog section for ${VERSION} not found" >&2
  exit 2
fi

if [ ! -s "$TMP_FILE" ]; then
  echo "Error: changelog section for ${VERSION} is empty" >&2
  exit 1
fi

if [ "$MODE" = "check" ]; then
  if [ "$SECTION" != "$VERSION" ]; then
    echo "Note: no '## ${VERSION}' section; using base version '## ${SECTION}' notes." >&2
  fi
  exit 0
fi

if [ "$MODE" = "notes" ]; then
  # Used for the KernelSU update metadata: a missing summary is not fatal,
  # the release workflow falls back to a generic sentence.
  notes_body "$SECTION"
  exit 0
fi

cat "$TMP_FILE"
