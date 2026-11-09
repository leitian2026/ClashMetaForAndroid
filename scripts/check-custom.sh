#!/usr/bin/env bash
# CUSTOM (CUSTOM.md #19): check that CUSTOM.md and the code agree. Run it after every change and
# after syncing upstream:   bash scripts/check-custom.sh
#
# 1. A file with a `CUSTOM` marker that also exists upstream must be in the "上游文件总表" of CUSTOM.md.
#    "Exists upstream" = it is in the git ref UPSTREAM_REF (default: main). Without that ref (for
#    example in an unpacked zip) the check is looser: a marked file passes when CUSTOM.md mentions
#    its path anywhere or its file name in the "新增文件" lists.
# 2. A file with a `CUSTOM` marker that is new (not upstream) must be mentioned in CUSTOM.md.
# 3. Every file in the table must still exist and still contain a `CUSTOM` marker (the hook was not
#    lost in a merge).
# Exit code 0 = consistent, 1 = something is missing (listed), 2 = cannot run.
set -u

cd "$(dirname "$0")/.." || exit 2

DOC=CUSTOM.md
UPSTREAM_REF=${UPSTREAM_REF:-main}

[ -f "$DOC" ] || { echo "CUSTOM.md not found"; exit 2; }

# paths in the table: first backticked item of every row that starts with "| `"
listed=$(grep -E '^\| `' "$DOC" | sed -E 's/^\| `([^`]+)`.*/\1/' | sort -u)

in_git=false
git rev-parse --is-inside-work-tree >/dev/null 2>&1 && in_git=true

if $in_git; then
  files=$(git ls-files)
else
  files=$(find . -type f -not -path './.git/*' -not -path '*/build/*' -not -path './core/src/foss/*' | sed 's|^\./||')
fi

have_upstream=false
if $in_git && git rev-parse --verify -q "$UPSTREAM_REF" >/dev/null 2>&1; then
  have_upstream=true
  upstream_files=$(git ls-tree -r --name-only "$UPSTREAM_REF" | sort -u)
fi

marked=$(printf '%s\n' "$files" \
  | grep -vE '^(core/src/foss/|\.idea/|scripts/|\.github/[^/]+\.md$|(CUSTOM|AGENTS|CLAUDE|GEMINI)\.md$|\.cursorrules$)' \
  | xargs -r grep -lw 'CUSTOM' 2>/dev/null | sort -u)

status=0
unlisted=""
unregistered=""

while IFS= read -r f; do
  [ -z "$f" ] && continue

  if printf '%s\n' "$listed" | grep -qxF "$f"; then
    continue
  fi

  base=$(basename "$f")

  if $have_upstream && printf '%s\n' "$upstream_files" | grep -qxF "$f"; then
    unlisted="$unlisted$f"$'\n'
  elif ! grep -qF "$base" "$DOC"; then
    unregistered="$unregistered$f"$'\n'
  fi
done <<< "$marked"

if [ -n "$unlisted" ]; then
  echo "== upstream file with a CUSTOM marker, but not in the table of $DOC =="
  printf '%s' "$unlisted"
  status=1
fi

if [ -n "$unregistered" ]; then
  echo "== file with a CUSTOM marker, but $DOC never mentions it =="
  printf '%s' "$unregistered"
  status=1
fi

broken=""
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if [ ! -f "$f" ]; then
    broken="$broken$f (file is gone)"$'\n'
  elif ! grep -qw 'CUSTOM' "$f"; then
    broken="$broken$f (no CUSTOM marker any more: the hook was lost)"$'\n'
  fi
done <<< "$listed"

if [ -n "$broken" ]; then
  echo "== listed in the table of $DOC but broken =="
  printf '%s' "$broken"
  status=1
fi

if [ $status -eq 0 ]; then
  mode="no '$UPSTREAM_REF' ref: loose check"
  $have_upstream && mode="against $UPSTREAM_REF"
  echo "OK ($mode): $(printf '%s\n' "$listed" | grep -c .) upstream files in the table, all hooks present."
fi

exit $status
