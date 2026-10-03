#!/usr/bin/env python3
"""Run every browser widget suite on both paint backends against one exact commit.

Usage: tools/check-browser-suites.py <output dir> <font.ttf>

The fixture and browser checks run from a temporary archive of HEAD, so uncommitted files and
concurrent working-tree edits cannot enter the evidence. The font is any TrueType face; the export suites
embed it (a DejaVu Sans file is what earlier receipts used). Needs Node with Playwright on
NODE_PATH and its Chromium installed. Audit browser ownership before and after (see AGENTS.md).

Jobs: paired SVG/Canvas baseline, parity and navigation; widget, linked, export and standalone on
each backend; the established SVG navigation suite; aggregate membership on both backends; named
selections, history, snapshots and filters on each backend. Widget, linked and history traces must
be identical across backends. Exits nonzero unless every job passes.
"""

import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from pathlib import Path


@contextmanager
def commit_source(repository: Path, commit: str):
    """Yield only the named commit's bytes; delete this run's build output on exit."""
    archive = subprocess.check_output(["git", "archive", commit], cwd=repository)
    with tempfile.TemporaryDirectory(prefix="intaglio-browser-gate-") as directory:
        source = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as contents:
            contents.extractall(source, filter="data")
        yield source


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    out = Path(sys.argv[1]).resolve()
    font = Path(sys.argv[2]).resolve()
    if not font.is_file():
        print(f"no font at {font}", file=sys.stderr)
        return 2
    root = Path(
        subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], text=True
        ).strip()
    )
    commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True
    ).strip()
    runner = subprocess.check_output(
        ["git", "show", f"{commit}:tools/check-browser-suites.py"], cwd=root
    )
    if Path(__file__).read_bytes() != runner:
        print("commit this runner before using it to certify HEAD", file=sys.stderr)
        return 1
    if out.exists():
        print(f"{out} exists; use a fresh evidence directory", file=sys.stderr)
        return 1
    out.mkdir(parents=True)
    # Keep the font bytes fixed too; callers may replace the original during a run.
    retained_font = out / "font.ttf"
    shutil.copyfile(font, retained_font)
    with commit_source(root, commit) as source:
        return run_suites(source, commit, out, retained_font)


def run_suites(root: Path, commit: str, out: Path, font: Path) -> int:

    with (out / "link.log").open("w") as log:
        linked = subprocess.run(
            ["sbt", "-batch", "-Dsbt.supershell=false", "browserFixture/fastLinkJS"],
            cwd=root,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    if linked.returncode != 0:
        print(f"linking the fixture failed; see {out / 'link.log'}", file=sys.stderr)
        return 1
    bundles = sorted(
        root.glob(
            "modules/browser-fixture/target/scala-*/browserfixture-fastopt/main.js"
        )
    )
    if len(bundles) != 1:
        print(f"expected one linked fixture, found {bundles}", file=sys.stderr)
        return 1
    # Browser pages remain replayable after the temporary source/build tree is removed.
    bundle = out / "fixture.js"
    shutil.copyfile(bundles[0], bundle)

    jobs = [
        ("baseline", "canvas-baseline", "svg"),
        ("parity", "canvas-parity", "svg"),
        ("navigation-pair", "canvas-navigation", "svg"),
    ]
    for renderer in ["svg", "canvas"]:
        for suite in ["widget", "linked", "export", "standalone"]:
            script = (
                f"canvas-{suite}"
                if renderer == "canvas" and suite in ["export", "standalone"]
                else suite
            )
            jobs.append((f"{suite}-{renderer}", script, renderer))
    jobs.append(("navigation-svg", "navigation", "svg"))
    # Aggregate membership runs both backends itself.
    jobs.append(("membership", "membership", "svg"))
    for renderer in ["svg", "canvas"]:
        jobs.append((f"history-{renderer}", "history", renderer))

    def run(job):
        name, script, renderer = job
        args = [
            "node",
            str(root / f"tools/check-{script}-browser.cjs"),
            str(bundle),
            str(out / name),
        ]
        if script in ["export", "canvas-export"]:
            args.append(str(font))
        with (out / f"{name}.log").open("w") as log:
            result = subprocess.run(
                args,
                cwd=root,
                env=dict(os.environ, INTAGLIO_TEST_RENDERER=renderer),
                stdout=log,
                stderr=subprocess.STDOUT,
            )
        return {
            "name": name,
            "command": args[1:],
            "renderer": renderer,
            "exitCode": result.returncode,
        }

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(run, jobs))

    traces = {}
    for suite in ["widget", "linked", "history"]:
        reports = [out / f"{suite}-{r}" / "report.json" for r in ["svg", "canvas"]]
        if all(p.is_file() for p in reports):
            a, b = (json.loads(p.read_text()) for p in reports)
            traces[suite] = bool(a.get("trace")) and a.get("trace") == b.get("trace")
        else:
            traces[suite] = False

    report = {
        "commit": commit,
        "source": "git archive of the recorded commit",
        "bundleSha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
        "fontSha256": hashlib.sha256(font.read_bytes()).hexdigest(),
        "jobs": results,
        "pairedTracesMatch": traces,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    for r in results:
        print(f"{r['name']:>18}  exit {r['exitCode']}")
    print("paired traces match:", traces)
    ok = all(r["exitCode"] == 0 for r in results) and all(traces.values())
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
