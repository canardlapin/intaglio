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

compat_tmp="$(mktemp -d "${TMPDIR:-/tmp}/intaglio-compat.XXXXXX")"
cleanup() {
  rm -rf "$compat_tmp"
}
trap cleanup EXIT

baseline_root="$compat_tmp/baseline"
compat_home="$compat_tmp/home"
mkdir -p "$baseline_root" "$compat_home"
git -C "$repo_root" archive "$baseline_sha" | tar -x -C "$baseline_root"

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
      "set ThisBuild / version := \"0.1.1-SNAPSHOT\"" compatibilityCheck
)
