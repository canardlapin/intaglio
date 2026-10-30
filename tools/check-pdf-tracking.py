#!/usr/bin/env python3
"""Verify Poppler's documented directional-control projection of the PDF fixture."""

from pathlib import Path
import shutil
import subprocess
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-pdf-tracking.py <fixture.pdf>", file=sys.stderr)
        return 64
    command = shutil.which("pdftotext")
    if command is None:
        print("pdftotext is required on PATH; install Poppler and retry", file=sys.stderr)
        return 127
    fixture = Path(sys.argv[1])
    if not fixture.is_file():
        print(f"fixture does not exist: {fixture}", file=sys.stderr)
        return 66
    result = subprocess.run(
        [command, "-enc", "UTF-8", str(fixture), "-"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode:
        sys.stderr.write(result.stderr)
        return result.returncode
    # Poppler terminates this one-page document with LF/LF/FF, outside its text content.
    actual = result.stdout.rstrip("\n\f")
    expected = "A Δ\u202bא\u202ce\u0301"
    if actual != expected:
        print(f"unexpected Poppler code points: {[f'U+{ord(c):04X}' for c in actual]}", file=sys.stderr)
        return 1
    print("Poppler extracted the expected directional-control projection.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
