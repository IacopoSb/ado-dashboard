#!/usr/bin/env bash
# Release notes from the Conventional Commits since the previous release:
#   .github/release-notes.sh release/0.2.0
# The app shows them in its settings, so only what a user notices goes in
# (feat, fix, perf, and breaking changes of any type); docs, chore, ci, test,
# build, refactor and style are left out.
set -euo pipefail
tag="${1:?usage: release-notes.sh release/X.Y.Z}"
case "$tag" in
  release/[0-9]*.[0-9]*.[0-9]*) ;;
  *) echo "tag must be release/X.Y.Z" >&2; exit 1 ;;
esac
git rev-parse -q --verify "refs/tags/$tag" >/dev/null || { echo "unknown tag $tag" >&2; exit 1; }
prev=$(git describe --tags --abbrev=0 --match "release/*" "$tag^" 2>/dev/null || true)
range="${prev:+$prev..}$tag"
subjects=$(git log --no-merges --format=%s "$range")

# $2: types, $3: "!" for breaking changes only, "" for the others
section() {
  local title="$1" types="$2" bang="$3" lines
  lines=$(grep -E "^($types)(\([^)]*\))?$bang: " <<<"$subjects" || true)
  [ -z "$lines" ] && return
  echo "### $title"
  echo "$lines" | while IFS= read -r subject; do
    text=$(sed -E 's/^[a-z]+(\([^)]*\))?!?: //' <<<"$subject")
    echo "- ${text^}"
  done
  echo
}

notes=$(
  section "Breaking changes" "[a-z]+" "!"
  section "New" "feat" ""
  section "Fixes" "fix" ""
  section "Performance" "perf" ""
)
[ -n "$notes" ] || notes="No changes you would notice in the app."
echo "$notes"
