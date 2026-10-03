#!/usr/bin/env bash
# External-consumer gate for intaglio-browser: build the exact artifact a commit produces and use it
# the way an application outside this repository would.
#
# Usage: tools/check-browser-consumer.sh [commit] <output dir>
#
# 1. Check out <commit> (default HEAD) into a clean temporary worktree, so uncommitted changes in
#    this checkout can never leak into the artifact.
# 2. publishLocal the Scala.js artifacts there under a version unique to that commit
#    (0.0.0-consumer-<sha12>), which no remote repository can supply.
# 3. Copy tools/browser-consumer outside the repository and build it against that version by
#    coordinate only; fail unless every intaglio jar on its classpath is that published version.
# 4. Link it and run tools/browser-consumer/smoke.cjs in Playwright's own Chromium.
#
# Writes report.json (commit, version, artifact SHA-256s, classpath, browser results) to the output
# directory. Audit browser ownership before and after running it (see AGENTS.md).
set -euo pipefail

if [[ $# -eq 1 ]]; then
  commit_arg=HEAD
  out=$1
elif [[ $# -eq 2 ]]; then
  commit_arg=$1
  out=$2
else
  echo "usage: tools/check-browser-consumer.sh [commit] <output dir>" >&2
  exit 2
fi

root=$(git rev-parse --show-toplevel)
cd "$root"
commit=$(git rev-parse --verify "$commit_arg^{commit}")
version="0.0.0-consumer-${commit:0:12}"
mkdir -p "$out"
out=$(cd "$out" && pwd)

# The smoke script and consumer sources that run must be the commit's own.
if ! git diff --quiet "$commit" -- tools/browser-consumer tools/check-browser-consumer.sh; then
  echo "tools/browser-consumer here differs from $commit; check out $commit or commit the change" >&2
  exit 1
fi

# Build trees stay under the output directory for inspection; only the git worktree is unregistered.
work="$out/work"
if [[ -e $work ]]; then
  echo "$work exists; pass a fresh output directory" >&2
  exit 1
fi
mkdir -p "$work"
cleanup() {
  git -C "$root" worktree remove --force "$work/source" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== publishing $commit as $version"
git worktree add --quiet --detach "$work/source" "$commit"
(
  cd "$work/source"
  sbt -batch \
    "set ThisBuild / version := \"$version\"" \
    coreJS/publishLocal interactionJS/publishLocal svgJS/publishLocal browserJS/publishLocal
) >"$out/publish.log" 2>&1 || { tail -40 "$out/publish.log" >&2; exit 1; }

ivy="${HOME}/.ivy2/local/io.github.canardlapin"
artifacts=()
for module in intaglio-core intaglio-interaction intaglio-svg intaglio-browser; do
  jar="$ivy/${module}_sjs1_3/$version/jars/${module}_sjs1_3.jar"
  [[ -f $jar ]] || { echo "missing published artifact $jar" >&2; exit 1; }
  artifacts+=("$jar")
done

echo "== building the consumer against $version"
cp -R "$root/tools/browser-consumer" "$work/consumer"
(
  cd "$work/consumer"
  sbt -batch "-Dintaglio.version=$version" \
    "export Compile / fullClasspath" fastLinkJS
) >"$out/consumer.log" 2>&1 || { tail -40 "$out/consumer.log" >&2; exit 1; }

classpath=$(grep -E '\.jar' "$out/consumer.log" | tail -1 | tr ':' '\n' | grep 'canardlapin' || true)
[[ -n $classpath ]] || { echo "no intaglio jars on the consumer classpath" >&2; exit 1; }
while IFS= read -r entry; do
  case $entry in
    "$ivy"/*/"$version"/jars/*) ;;
    *) echo "consumer resolved intaglio from somewhere else: $entry" >&2; exit 1 ;;
  esac
done <<<"$classpath"

main_js=$(find "$work/consumer/target" -path '*consumer-fastopt/main.js' | head -1)
[[ -f $main_js ]] || { echo "the consumer did not link" >&2; exit 1; }

echo "== running the consumer in Chromium"
node "$root/tools/browser-consumer/smoke.cjs" "$main_js" "$out/browser" >"$out/smoke.log" 2>&1 ||
  { tail -40 "$out/smoke.log" >&2; exit 1; }

python3 - "$out" "$commit" "$version" "$classpath" "${artifacts[@]}" <<'PY'
import hashlib, json, sys
out, commit, version, classpath, *jars = sys.argv[1:]
browser = json.load(open(f"{out}/browser/report.json"))
report = {
    "commit": commit,
    "version": version,
    "artifacts": {j.rsplit("/", 1)[1]: hashlib.sha256(open(j, "rb").read()).hexdigest() for j in jars},
    "consumerClasspath": classpath.splitlines(),
    "browser": browser,
}
json.dump(report, open(f"{out}/report.json", "w"), indent=2)
checks = browser["checks"]
print(f"{commit[:12]} {version}: {sum(c['ok'] for c in checks)}/{len(checks)} consumer checks in Chromium {browser['browser']}")
PY
