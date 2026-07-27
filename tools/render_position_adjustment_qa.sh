#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${1:-$repo_root/target/graphics-position-qa}"

cd "$repo_root"
Rscript tools/r-parity/render_position_reference.R "$out_dir/ggplot2"
sbt --error "java2dJVM / Test / runMain intaglio.java2d.PositionVisualQa $out_dir"

printf 'visual QA: %s\n' "$out_dir/index.html"
