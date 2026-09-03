#!/usr/bin/env bash
# The documentation court.
#
# 1. Every fenced block marked `mdoc` under docs/ is compiled against the real
#    modules, so a guide cannot claim an API that does not exist.
# 2. The gallery re-renders its plates. A plate is an SVG file checked into
#    docs/gallery, so an unintended rendering change shows up as a diff here
#    rather than as a stale image in the published guide.
# 3. Every relative Markdown link and heading anchor resolves.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

sbt -Djava.awt.headless=true -Dsbt.supershell=false docsCheck

if ! git diff --quiet -- docs/gallery; then
  echo "the gallery plates in docs/gallery are stale:" >&2
  git --no-pager diff --stat -- docs/gallery >&2
  echo "re-run tools/check-docs.sh, review each plate, and commit the result" >&2
  exit 1
fi

untracked="$(git ls-files --others --exclude-standard -- docs/gallery)"
if [[ -n "$untracked" ]]; then
  echo "the gallery produced plates that are not committed:" >&2
  echo "$untracked" >&2
  exit 1
fi

tools/check-links.sh
