#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

sbt --error -Djava.awt.headless=true -Dsbt.supershell=false \
  "java2dJVM / Test / runMain intaglio.java2d.GoldenUpdate --accept"
