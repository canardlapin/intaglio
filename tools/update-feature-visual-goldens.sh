#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

sbt -Djava.awt.headless=true \
  "java2dJVM / Test / runMain intaglio.java2d.FeatureVisualGoldenUpdate --accept"
