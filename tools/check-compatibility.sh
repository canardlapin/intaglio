#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
baseline_config="$repo_root/compatibility/baseline.conf"
baseline_sha="$(sed -n 's/^sha=//p' "$baseline_config")"
baseline_version="$(sed -n 's/^version=//p' "$baseline_config")"

if [[ ! "$baseline_sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "invalid compatibility baseline SHA in $baseline_config" >&2
  exit 2
fi
if [[ ! "$baseline_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "invalid compatibility baseline version in $baseline_config" >&2
  exit 2
fi
git -C "$repo_root" cat-file -e "${baseline_sha}^{commit}"

# sbt-version-policy derives the version it must stay compatible with from the
# version under test, so the working tree has to be built as the patch release
# that follows the baseline. Deriving it here keeps the two in step when the
# baseline moves.
baseline_major="${baseline_version%%.*}"
baseline_rest="${baseline_version#*.}"
baseline_minor="${baseline_rest%%.*}"
baseline_patch="${baseline_rest##*.}"
candidate_version="$baseline_major.$baseline_minor.$((baseline_patch + 1))-SNAPSHOT"

compat_tmp="$(mktemp -d "${TMPDIR:-/tmp}/intaglio-compat.XXXXXX")"
cleanup() {
  rm -rf "$compat_tmp"
}
trap cleanup EXIT

baseline_root="$compat_tmp/baseline"
compat_home="$compat_tmp/home"
mkdir -p "$baseline_root" "$compat_home"
git -C "$repo_root" archive "$baseline_sha" | tar -x -C "$baseline_root"

# The baseline is an archive, not a clone, so it has no git history for
# sbt-dynver to read. Name its version at load time rather than leaving the
# build to guess one and then overriding it afterwards.
printf 'ThisBuild / version := "%s"\n' "$baseline_version" > "$baseline_root/version.sbt"

sbt_options=(
  -J-Xms1G
  -J-Xmx4G
  -J-Xss6M
  -Djava.awt.headless=true
  -Duser.home="$compat_home"
  -Dsbt.boot.directory="$compat_tmp/sbt-boot"
  -Dsbt.global.base="$compat_tmp/sbt-global"
  -Dsbt.ivy.home="$compat_home/.ivy2"
  -Dsbt.supershell=false
)

(
  cd "$baseline_root"
  COURSIER_CACHE="$compat_tmp/coursier" sbt "${sbt_options[@]}" \
    "set ThisBuild / version := \"$baseline_version\"" \
    "set ThisBuild / Compile / packageDoc / publishArtifact := false" \
    publishLocal
)

(
  cd "$repo_root"
  INTAGLIO_COMPAT_BASELINE_VERSION="$baseline_version" \
    COURSIER_CACHE="$compat_tmp/coursier" \
    sbt "${sbt_options[@]}" \
      "set ThisBuild / version := \"$candidate_version\"" compatibilityCheck
)
