#!/usr/bin/env bash
# Link the reduction three ways and run each bundle under a 10 s timeout:
#   fullLinkJS, fastLinkJS, and fastLinkJS with the optimizer disabled (emitter only).
# Every configuration drops the side-effecting call (calls=0) and the iterator loop never ends
# (exit 124), so the defect is in the emitter, not the optimizer. Scala.js 1.22.0 per
# project/plugins.sbt; pass `2.13` to build with Scala 2.13.18 instead of 3.3.8.
set -uo pipefail

cd "$(dirname "$0")"
switch=()
target=scala-3.3.8
if [[ ${1:-} == 2.13 ]]; then
  switch=("++2.13.18")
  target=scala-2.13
fi

run() {
  local label=$1 bundle=$2
  echo "== $label"
  node - "$bundle" <<'EOF'
const result = require('child_process').spawnSync(process.execPath, [process.argv[2]], {
  stdio: 'inherit',
  timeout: 10000,
  killSignal: 'SIGKILL',
});
const timedOut = result.error && result.error.code === 'ETIMEDOUT';
console.log(timedOut ? 'exit 124: timed out (hang)' : `exit ${result.status}`);
EOF
}

sbt -Dsbt.supershell=false ${switch[@]+"${switch[@]}"} fullLinkJS fastLinkJS || exit 1
run "fullLinkJS" "target/$target/scalajs-instanceof-repro-opt/main.js"
run "fastLinkJS" "target/$target/scalajs-instanceof-repro-fastopt/main.js"
sbt -Dsbt.supershell=false ${switch[@]+"${switch[@]}"} \
  'set scalaJSLinkerConfig ~= (_.withOptimizer(false))' fastLinkJS || exit 1
run "fastLinkJS, optimizer disabled" "target/$target/scalajs-instanceof-repro-fastopt/main.js"
