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
# 4. Size limits (CUSTOM.md #157): CUSTOM.md at most 64 KB, every file under docs/custom/ at most 40 KB.
# 5. Every `CUSTOM.md #N` / `CUSTOM #N` in the code has a detail file docs/custom/<N padded to 3>.md
#    (or is an entry of CUSTOM_ARCHIVE.md).
# 6. Every file under docs/custom/ is linked from CUSTOM.md, and every docs/custom link in CUSTOM.md exists.
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
  | grep -vE '^(core/src/foss/|\.idea/|scripts/|docs/custom/|\.github/[^/]+\.md$|(CUSTOM|AGENTS|CLAUDE|GEMINI)\.md$|\.cursorrules$)' \
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
  elif ! grep -rqF -- "$base" "$DOC" docs/custom 2>/dev/null; then
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

# 4. size limits (CUSTOM.md #157)
INDEX_MAX=65536
DETAIL_MAX=40960
toobig=""
sz=$(wc -c < "$DOC" | tr -d ' ')
[ "$sz" -gt "$INDEX_MAX" ] && toobig="$toobig$DOC: $sz bytes (limit $INDEX_MAX)"$'\n'
if [ -d docs/custom ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    sz=$(wc -c < "$f" | tr -d ' ')
    [ "$sz" -gt "$DETAIL_MAX" ] && toobig="$toobig$f: $sz bytes (limit $DETAIL_MAX)"$'\n'
  done < <(find docs/custom -type f -name '*.md' | sort)
fi
if [ -n "$toobig" ]; then
  echo "== file over its size limit: split it in the same commit (AGENTS.md rule 9) =="
  printf '%s' "$toobig"
  status=1
fi

# docs/v2 limits and cross-reference check
v2_status=0
for f in docs/v2/upstream.md docs/v2/rules.md docs/v2/changes.md; do
  if [ ! -f "$f" ]; then
    echo "== required document missing: $f =="
    v2_status=1
  fi
done
if [ -f docs/v2/upstream.md ] && [ -f docs/v2/rules.md ] && [ -f docs/v2/changes.md ]; then
  check_v2_size() {
    local f="$1" limit="$2" size
    size=$(wc -c < "$f" | tr -d '[:space:]')
    if [ "$size" -gt "$limit" ]; then
      echo "== docs/v2 file over target size: $f: $size bytes (limit $limit) =="
      v2_status=1
    fi
  }
  check_v2_size docs/v2/upstream.md 15360
  check_v2_size docs/v2/rules.md 8192
  check_v2_size docs/v2/changes.md 20480
  missing_v2=""
  while IFS= read -r n; do
    [ -n "$n" ] || continue
    if ! grep -q "^#$n｜" docs/v2/changes.md; then
      missing_v2="$missing_v2#$n (missing from docs/v2/changes.md); "
    fi
  done < <(grep -oE '#[0-9]+[a-z]?' docs/v2/upstream.md | sed 's/^#//' | sort -u)
  if [ -n "$missing_v2" ]; then
    echo "== upstream feature references missing from changes.md =="
    printf '%s\n' "$missing_v2"
    v2_status=1
  fi
fi
if [ "$v2_status" -ne 0 ]; then status=1; fi

# 5. every CUSTOM.md #N reference in the code has a detail file
nocode=""
refs=$(printf '%s\n' "$files" \
  | grep -vE '^(core/src/foss/|\.idea/|scripts/|docs/custom/|\.github/[^/]+\.md$|(CUSTOM|CUSTOM_ARCHIVE|AGENTS|CLAUDE|GEMINI|README|CONTRIBUTING|PRIVACY_POLICY)\.md$|\.cursorrules$)' \
  | xargs -r grep -IhoE 'CUSTOM(\.md)? #[0-9]+[a-z]?([^A-Za-z0-9]|$)' 2>/dev/null \
  | sed -E 's/^CUSTOM(\.md)? #//; s/[^0-9a-z]*$//' | sort -u)
while IFS= read -r n; do
  [ -z "$n" ] && continue
  num=${n%%[a-z]*}
  suffix=${n#"$num"}
  name=$(printf '%03d%s' "$((10#$num))" "$suffix")
  if [ ! -f "docs/custom/$name.md" ] && ! grep -qE "^## $n\. " CUSTOM_ARCHIVE.md 2>/dev/null; then
    nocode="$nocode#$n (no docs/custom/$name.md)"$'\n'
  fi
done <<< "$refs"
if [ -n "$nocode" ]; then
  echo "== code mentions an entry number that has no detail file =="
  printf '%s' "$nocode"
  status=1
fi

# 6. docs/custom and the index agree
noindex=""
if [ -d docs/custom ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    grep -qF "($f)" "$DOC" || noindex="$noindex$f (not linked from $DOC)"$'\n'
  done < <(find docs/custom -type f -name '*.md' | sort)
fi
while IFS= read -r l; do
  [ -z "$l" ] && continue
  [ -f "$l" ] || noindex="$noindex$l (linked from $DOC, but the file is gone)"$'\n'
done < <(grep -oE '\(docs/custom/[^)]+\)' "$DOC" | tr -d '()' | sort -u)
if [ -n "$noindex" ]; then
  echo "== docs/custom and the index of $DOC disagree =="
  printf '%s' "$noindex"
  status=1
fi

# 7. Warn about very large Kotlin files; this is informational and never changes status.
big_limit=${BIG_FILE_LINES:-2500}
case "$big_limit" in
  ''|*[!0-9]*) big_limit=2500 ;;
esac
big_files=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  [[ "$f" == *.kt ]] || continue
  [[ "$f" == core/src/foss/* ]] && continue
  [ -f "$f" ] || continue
  line_count=$(wc -l < "$f" | tr -d '[:space:]')
  if [ "$line_count" -gt "$big_limit" ]; then
    printf -v big_files '%s%s: %s\n' "$big_files" "$f" "$line_count"
  fi
done <<< "$files"
if [ -n "$big_files" ]; then
  echo "== warning: Kotlin file over $big_limit lines (not an error) =="
  printf '%s' "$big_files"
  echo "要改这些文件时，先沿现有分界拆出一块再加功能（AGENTS.md）。"
fi

if [ $status -eq 0 ]; then
  mode="no '$UPSTREAM_REF' ref: loose check"
  $have_upstream && mode="against $UPSTREAM_REF"
  echo "OK ($mode): $(printf '%s\n' "$listed" | grep -c .) upstream files in the table, all hooks present, sizes and references fine."
fi

exit $status
