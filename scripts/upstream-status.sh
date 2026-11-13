#!/usr/bin/env bash
# CUSTOM (CUSTOM.md #41): how far the custom branch is behind the upstream project, and which of the new
# upstream changes touch files that CUSTOM.md lists (those are where a merge can conflict).
#
#   bash scripts/upstream-status.sh
#
# Where "upstream" is:  UPSTREAM_REF (a git ref that is already here), or else UPSTREAM_URL + UPSTREAM_BRANCH
# (default: https://github.com/MetaCubeX/ClashMetaForAndroid.git, main), fetched here. It only reads: nothing is
# merged, rebased or pushed. The report goes to the screen and, in GitHub Actions, to the job summary.
# Exit code 0 = report written, 2 = cannot run.
set -u

cd "$(dirname "$0")/.." || exit 2

DOC=CUSTOM.md
UPSTREAM_URL=${UPSTREAM_URL:-https://github.com/MetaCubeX/ClashMetaForAndroid.git}
UPSTREAM_BRANCH=${UPSTREAM_BRANCH:-main}

[ -f "$DOC" ] || { echo "CUSTOM.md not found"; exit 2; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not a git checkout"; exit 2; }

if [ -n "${UPSTREAM_REF:-}" ]; then
  git rev-parse --verify -q "$UPSTREAM_REF" >/dev/null || { echo "ref $UPSTREAM_REF not found"; exit 2; }
  target=$(git rev-parse "$UPSTREAM_REF")
  label=$UPSTREAM_REF
else
  git fetch -q "$UPSTREAM_URL" "$UPSTREAM_BRANCH" || { echo "cannot fetch $UPSTREAM_URL $UPSTREAM_BRANCH"; exit 2; }
  target=$(git rev-parse FETCH_HEAD)
  label="$UPSTREAM_URL ($UPSTREAM_BRANCH)"
fi

base=$(git merge-base HEAD "$target" 2>/dev/null) || { echo "no common history with $label (fetch the full history)"; exit 2; }

behind=$(git rev-list --count "$base..$target")
baseline=$(grep -m1 -oE '[0-9a-f]{40}' "$DOC")

# files of the table of CUSTOM.md (first backticked item of every row that starts with "| `")
listed=$(grep -E '^\| `' "$DOC" | sed -E 's/^\| `([^`]+)`.*/\1/' | sort -u)
changed=$(git diff --name-only "$base" "$target" | sort -u)
touched=$(comm -12 <(printf '%s\n' "$listed") <(printf '%s\n' "$changed"))

# the core (a submodule) and the patches for it
core_moved=$(git diff --name-only "$base" "$target" -- core/src/foss/golang/clash | head -1)

report() {
  echo "## Upstream status"
  echo
  echo "- upstream: $label, at \`${target:0:12}\`"
  echo "- last common commit: \`${base:0:12}\`"
  [ -n "$baseline" ] && echo "- baseline written in CUSTOM.md: \`${baseline:0:12}\`"
  echo "- upstream commits not in this branch yet: **$behind**"

  if [ "$behind" -eq 0 ]; then
    echo
    echo "Nothing new upstream."

    return
  fi

  echo "- files they change: $(printf '%s\n' "$changed" | grep -c .)"
  echo "- of those, files listed in the table of CUSTOM.md: **$(printf '%s\n' "$touched" | grep -c .)**"

  if [ -n "$core_moved" ]; then
    echo "- the mihomo core (submodule) moves: run \"Check Custom\" after the merge, the patches in patches/mihomo may need a rebase"
  fi

  if [ -n "$touched" ]; then
    echo
    echo "### Merge conflicts can come from these files"
    printf '%s\n' "$touched" | sed 's/^/- `/; s/$/`/'
  else
    echo
    echo "None of the changed files is in the table: a merge should be clean."
  fi

  echo
  echo "### Newest upstream commits"
  git log --no-merges --format='- %h %s' -n 15 "$base..$target"
}

out=$(report)

printf '%s\n' "$out"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '%s\n' "$out" >> "$GITHUB_STEP_SUMMARY"
fi
