#!/usr/bin/env bash
# Rehearse a release without publishing anything.
#
# The rehearsal answers one question that a normal build cannot: would the
# artifacts this repository is about to send to Maven Central be complete and
# resolvable for someone who has never seen this machine? It therefore works on
# a clean clone in an isolated home, with its own ivy, coursier and sbt caches,
# so a stale local artifact cannot stand in for a missing dependency.
#
# It publishes to a throwaway local repository -- `publishM2` into a temporary
# directory -- and then reads back every generated POM, checking that:
#   * every module the build aggregates and does not skip actually produced a
#     POM, a jar, and a sources jar;
#   * each POM carries the metadata the Central Portal requires: name,
#     description, url, licence, developer, and SCM;
#   * no dependency in the closure is a SNAPSHOT, which Central refuses;
#   * every non-Intaglio dependency in the closure resolves from Maven Central.
#
# Nothing is signed and nothing leaves the machine. Written for bash 3.2 so it
# runs on a stock macOS as well as on CI.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
rehearsal_version="${INTAGLIO_REHEARSAL_VERSION:-0.0.0-REHEARSAL}"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/intaglio-rehearsal.XXXXXX")"
cleanup() {
  rm -rf "$tmp"
}
trap cleanup EXIT

clone="$tmp/clone"
home="$tmp/home"
m2="$home/.m2/repository"
mkdir -p "$clone" "$home" "$m2"

echo "==> cloning $repo_root at HEAD into a scratch directory"
git clone --quiet --no-hardlinks "$repo_root" "$clone"
(cd "$clone" && git --no-pager log --oneline -1)

sbt_options=(
  -J-Xms1G
  -J-Xmx4G
  -J-Xss6M
  -Djava.awt.headless=true
  -Duser.home="$home"
  -Dsbt.boot.directory=$tmp/sbt-boot
  -Dsbt.global.base=$tmp/sbt-global
  -Dsbt.ivy.home=$home/.ivy2
  -Dsbt.supershell=false
  -Dmaven.repo.local="$m2"
)

echo "==> publishing every module to a throwaway local repository"
(
  cd "$clone"
  COURSIER_CACHE="$tmp/coursier" sbt "${sbt_options[@]}" \
    "set ThisBuild / version := \"$rehearsal_version\"" \
    publishM2
)

echo "==> checking the generated POM closure"
group_path="io/github/canardlapin"
expected_modules="
intaglio-core_3
intaglio-core_sjs1_3
intaglio-laws_3
intaglio-laws_sjs1_3
intaglio-svg_3
intaglio-svg_sjs1_3
intaglio-canvas_sjs1_3
intaglio-java2d_3
intaglio-javafx_3
intaglio-pdf_3
intaglio-notebook_3
"

failures=0
report() {
  echo "rehearsal: $1" >&2
  failures=$((failures + 1))
}

for module in $expected_modules; do
  base="$m2/$group_path/$module/$rehearsal_version"
  pom="$base/$module-$rehearsal_version.pom"
  if [[ ! -f "$pom" ]]; then
    report "$module produced no POM"
    continue
  fi
  for artifact in ".jar" "-sources.jar"; do
    if [[ ! -f "$base/$module-$rehearsal_version$artifact" ]]; then
      report "$module produced no $artifact"
    fi
  done
  for element in "<name>" "<description>" "<url>" "<licenses>" "<developers>" "<scm>"; do
    if ! grep -q -- "$element" "$pom"; then
      report "$module POM has no $element"
    fi
  done
  if grep -q -- "-SNAPSHOT</version>" "$pom"; then
    report "$module POM depends on a SNAPSHOT"
  fi
done

# A module that must never be published. `performance` and the docs project are
# build-internal; the aggregate root has nothing to publish either.
for module in intaglio-performance-gates_3 intaglio-docs_3 intaglio_3; do
  if [[ -d "$m2/$group_path/$module" ]]; then
    report "$module was published but is marked publish/skip"
  fi
done

echo "==> resolving every third-party dependency in the closure from Central"
third_party="$(
  grep -h -A 3 "<dependency>" "$m2/$group_path"/*/"$rehearsal_version"/*.pom |
    tr -d ' \t' |
    awk -F'[<>]' '
      /^<groupId>/    { group = $3 }
      /^<artifactId>/ { artifact = $3 }
      /^<version>/    { if (group != "io.github.canardlapin") print group ":" artifact ":" $3 }
    ' |
    sort -u
)"
if [[ -z "$third_party" ]]; then
  report "the closure lists no third-party dependency, which cannot be right"
fi
for coordinate in $third_party; do
  group="${coordinate%%:*}"
  rest="${coordinate#*:}"
  artifact="${rest%%:*}"
  version="${rest##*:}"
  url="https://repo1.maven.org/maven2/$(echo "$group" | tr '.' '/')/$artifact/$version/$artifact-$version.pom"
  status="$(curl -sS -o /dev/null -w '%{http_code}' -L --max-time 25 "$url" 2>/dev/null || echo 000)"
  if [[ ! "$status" =~ ^[23] ]]; then
    report "$coordinate does not resolve from Maven Central (HTTP $status)"
  else
    echo "    $coordinate"
  fi
done

if [[ $failures -gt 0 ]]; then
  echo "$failures rehearsal failure(s)" >&2
  exit 1
fi

echo "==> rehearsal clean: every module published locally with a complete POM"
echo "    nothing was signed and nothing was uploaded"
