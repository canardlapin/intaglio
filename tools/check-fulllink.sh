#!/usr/bin/env bash
# The optimized-bundle court.
#
# Every other Scala.js suite and the browser consumer gate link with fastLinkJS. A production
# bundle is linked with fullLinkJS: unchecked semantics (casts, array bounds, null checks vanish),
# a different optimizer outcome, then the Google Closure Compiler. Code that is correct under
# fastLinkJS can therefore fail only in a production bundle; bd-01M41R5BTCMQ5R0NPGAZ0S504K made
# every plot compile hang there.
#
# 1. Link modules/fulllink-smoke with fullLinkJS. It compiles one plot per representative layer
#    family and paints each through the Canvas renderer onto a stub context.
# 2. Run the bundle in Node under a wall-clock timeout (default 120 s; the program itself takes
#    well under a second). A hang exits 124, as coreutils `timeout` does.
#
# Usage: tools/check-fulllink.sh [timeout seconds]
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

timeout_seconds="${1:-${FULLLINK_TIMEOUT_SECONDS:-120}}"

sbt_output="$(mktemp)"
trap 'rm -f "$sbt_output"' EXIT
sbt -Djava.awt.headless=true -Dsbt.supershell=false \
  fullLinkSmoke/fullLinkJS "print fullLinkSmoke/fullLinkJS/scalaJSLinkerOutputDirectory" |
  tee "$sbt_output"

bundle_dir="$(grep -E '^[^[].*fulllinksmoke-opt$' "$sbt_output" | tail -n 1)"
bundle="$bundle_dir/main.js"
if [[ ! -f $bundle ]]; then
  echo "no fullLinkJS bundle found (looked for $bundle)" >&2
  exit 1
fi

echo "== running $bundle (timeout ${timeout_seconds}s)"
node - "$bundle" "$timeout_seconds" <<'EOF'
const [bundle, seconds] = process.argv.slice(2);
const result = require('child_process').spawnSync(process.execPath, [bundle], {
  stdio: 'inherit',
  timeout: Number(seconds) * 1000,
  killSignal: 'SIGKILL',
});
if (result.error && result.error.code === 'ETIMEDOUT') {
  console.error(`fullLinkJS bundle did not finish within ${seconds}s: it hangs`);
  process.exit(124);
}
if (result.error) throw result.error;
if (result.status !== 0) {
  console.error(`fullLinkJS bundle failed (status ${result.status}, signal ${result.signal})`);
  process.exit(result.status === null ? 1 : result.status);
}
EOF
echo "== fullLinkJS court passed"
