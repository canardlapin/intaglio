#!/usr/bin/env bash
# Verify every link in the repository's Markdown.
#
# Relative links and in-document anchors are checked offline and are always
# enforced: a broken path is a defect in this repository. External URLs are
# checked only with --external, because reaching a third-party host makes the
# result depend on someone else's uptime.
#
# Written for bash 3.2 so it runs on a stock macOS as well as on CI.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
check_external=0
if [[ "${1:-}" == "--external" ]]; then
  check_external=1
fi

cd "$repo_root"

failures=0
report() {
  echo "broken link: $1" >&2
  failures=$((failures + 1))
}

# The anchor slug GitHub derives from a heading: lowercase, punctuation
# dropped, whitespace to hyphens.
slugs_of() {
  # BSD sed has no `\+`, so the portable repetition is `x x*`.
  sed -n 's/^##*[[:space:]][[:space:]]*//p' "$1" |
    tr '[:upper:]' '[:lower:]' |
    sed -e 's/`//g' -e 's/[^a-z0-9 _-]//g' -e 's/[[:space:]][[:space:]]*/-/g'
}

resolve_external() {
  local url="$1"
  local document="$2"
  local status
  status="$(curl -sS -o /dev/null -w '%{http_code}' -L --max-time 25 \
    -A 'intaglio-link-check' "$url" 2>/dev/null || echo 000)"
  if [[ ! "$status" =~ ^[23] ]]; then
    report "$document -> $url (HTTP $status)"
  fi
}

check_target() {
  local document="$1"
  local target="$2"
  local directory
  directory="$(dirname "$document")"
  case "$target" in
    mailto:* | tel:*)
      return
      ;;
    http://* | https://*)
      if [[ $check_external -eq 1 ]]; then
        resolve_external "$target" "$document"
      fi
      return
      ;;
    \#*)
      if ! slugs_of "$document" | grep -Fxq "${target#\#}"; then
        report "$document -> $target (no such heading)"
      fi
      return
      ;;
  esac

  local path="${target%%#*}"
  local anchor=""
  if [[ "$target" == *#* ]]; then
    anchor="${target#*#}"
  fi
  local resolved="$directory/$path"
  if [[ ! -e "$resolved" ]]; then
    report "$document -> $target"
  elif [[ -n "$anchor" && -f "$resolved" && "$resolved" == *.md ]]; then
    if ! slugs_of "$resolved" | grep -Fxq "$anchor"; then
      report "$document -> $target (no such heading)"
    fi
  fi
}

while IFS= read -r document; do
  case "$document" in
    .worktrees/*) continue ;;
  esac
  while IFS= read -r target; do
    [[ -z "$target" ]] && continue
    check_target "$document" "$target"
  done < <(
    # Fenced blocks and inline code spans hold Scala, and `xs[Row](_.a, _.b)`
    # looks exactly like a Markdown link. Drop both before scanning.
    awk '/^[[:space:]]*```/ { fenced = !fenced; next } !fenced' "$document" |
      sed -e 's/`[^`]*`//g' |
      grep -oE '\]\([^)]+\)' |
      sed -e 's/^](//' -e 's/)$//' -e 's/[[:space:]][[:space:]]*".*"$//' || true
  )
done < <(git ls-files '*.md')

if [[ $failures -gt 0 ]]; then
  echo "$failures broken link(s)" >&2
  exit 1
fi

if [[ $check_external -eq 1 ]]; then
  echo "every relative and external link resolves"
else
  echo "every relative link resolves (external URLs skipped; pass --external)"
fi
