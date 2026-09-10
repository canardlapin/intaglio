#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${1:-$repo_root/target/feature-visual-qa}"

cd "$repo_root"
Rscript tools/r-parity/render_feature_reference.R "$out_dir/reference"
sbt --error "java2dJVM / Test / runMain intaglio.java2d.FeatureVisualQa $out_dir"

printf 'recent-feature visual QA: %s\n' "$out_dir/index.html"
