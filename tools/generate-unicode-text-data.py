#!/usr/bin/env python3
"""Generate the pinned Unicode 17 grapheme tables used by TextClusters.

The input directory must contain the five files named in SOURCES.  ``--download``
obtains them from unicode.org; their SHA-256 digests are checked before any
generated source is written.  Keeping this generator dependency-free makes the
Unicode update deliberately reviewable.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import sys
import urllib.request
from pathlib import Path

VERSION = "17.0.0"
BASE = f"https://www.unicode.org/Public/{VERSION}/ucd"
SOURCES = {
    "GraphemeBreakProperty.txt": (f"{BASE}/auxiliary/GraphemeBreakProperty.txt", "d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89"),
    "emoji-data.txt": (f"{BASE}/emoji/emoji-data.txt", "2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b"),
    "DerivedCoreProperties.txt": (f"{BASE}/DerivedCoreProperties.txt", "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08"),
    "Scripts.txt": (f"{BASE}/Scripts.txt", "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf"),
    "GraphemeBreakTest.txt": (f"{BASE}/auxiliary/GraphemeBreakTest.txt", "e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec"),
}
LINE = re.compile(r"^([0-9A-Fa-f]+)(?:\.\.([0-9A-Fa-f]+))?\s*;\s*([^\s#;]+)")
GCB = {"CR": 1, "LF": 2, "Control": 3, "Extend": 4, "ZWJ": 5,
       "Regional_Indicator": 6, "Prepend": 7, "SpacingMark": 8,
       "L": 9, "V": 10, "T": 11, "LV": 12, "LVT": 13}
INCB = {"Consonant": 1, "Linker": 2, "Extend": 3}
ALLOWED_SCRIPTS = {"Latin", "Greek", "Cyrillic", "Hebrew", "Han", "Hangul",
                   "Hiragana", "Katakana", "Bopomofo", "Common", "Inherited"}


def checked(path: Path) -> None:
    expected = SOURCES[path.name][1]
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"{path}: SHA-256 {actual}, expected {expected}")


def records(path: Path, wanted=None):
    out = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        value = m.group(3)
        if wanted is not None and value not in wanted:
            continue
        out.append((int(m.group(1), 16), int(m.group(2) or m.group(1), 16), value))
    return out


def coalesce(rows):
    rows = sorted(rows)
    out = []
    for start, end, value in rows:
        if out and value == out[-1][2] and start <= out[-1][1] + 1:
            out[-1] = (out[-1][0], max(end, out[-1][1]), value)
        else:
            out.append((start, end, value))
    return out


def scala_ranges(name, rows, mapping=None):
    converted = [(a, b, mapping[v] if mapping else 1) for a, b, v in rows]
    converted = coalesce(converted)
    lines = ",\n".join(f"    Range(0x{a:X}, 0x{b:X}, {v})" for a, b, v in converted)
    return f"  private val {name}: Array[Range] = Array(\n{lines}\n  )"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, default=Path(".unicode-17"))
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("modules/core/shared/src/main/scala/intaglio/UnicodeTextData.scala"))
    parser.add_argument("--fixture", type=Path, default=Path("modules/core/shared/src/test/resources/unicode/GraphemeBreakTest-17.0.0.txt"))
    parser.add_argument("--suite", type=Path, default=Path("modules/core/shared/src/test/scala/intaglio/TextClustersSuite.scala"))
    args = parser.parse_args()
    args.source_dir.mkdir(parents=True, exist_ok=True)
    for name, (url, _) in SOURCES.items():
        path = args.source_dir / name
        if args.download and not path.exists():
            print(f"downloading {url}", file=sys.stderr)
            urllib.request.urlretrieve(url, path)
        if not path.exists():
            raise SystemExit(f"missing {path}; use --download")
        checked(path)

    gcb = records(args.source_dir / "GraphemeBreakProperty.txt", GCB)
    ep = [r for r in records(args.source_dir / "emoji-data.txt") if r[2] == "Extended_Pictographic"]
    incb = [r for r in records(args.source_dir / "DerivedCoreProperties.txt", {"InCB;Consonant", "InCB;Linker", "InCB;Extend"})]
    # DerivedCoreProperties spells the value as InCB;Value only in comments in
    # older UCDs; Unicode 17 has property aliases parsed below instead.
    incb = []
    for raw in (args.source_dir / "DerivedCoreProperties.txt").read_text(encoding="utf-8").splitlines():
        m = LINE.match(raw)
        if m and m.group(3) == "InCB":
            value = raw.split("#", 1)[0].split(";")[2].strip() if raw.count(";") >= 2 else ""
            if value in INCB:
                incb.append((int(m.group(1), 16), int(m.group(2) or m.group(1), 16), value))
    scripts = [r for r in records(args.source_dir / "Scripts.txt") if r[2] in ALLOWED_SCRIPTS]
    header = """// This file is generated by tools/generate-unicode-text-data.py; do not edit.\n// Unicode 17.0.0 sources, SHA-256: GraphemeBreakProperty d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89; emoji-data 2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b; DerivedCoreProperties 24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08; Scripts 9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf.\npackage intaglio\n\nprivate[intaglio] object UnicodeTextData:\n  private final case class Range(first: Int, last: Int, value: Int)\n\n  private def lookup(ranges: Array[Range], codePoint: Int): Int =\n    var low = 0\n    var high = ranges.length - 1\n    while low <= high do\n      val middle = (low + high) >>> 1\n      val range = ranges(middle)\n      if codePoint < range.first then high = middle - 1\n      else if codePoint > range.last then low = middle + 1\n      else return range.value\n    0\n\n"""
    body = "\n\n".join((scala_ranges("graphemeBreak", gcb, GCB), scala_ranges("extendedPictographic", ep), scala_ranges("indicConjunctBreak", incb, INCB), scala_ranges("unprotectedScripts", scripts)))
    tail = """\n\n  def graphemeBreakClass(codePoint: Int): Int = lookup(graphemeBreak, codePoint)\n  def isExtendedPictographic(codePoint: Int): Boolean = lookup(extendedPictographic, codePoint) != 0\n  def indicConjunctBreakClass(codePoint: Int): Int = lookup(indicConjunctBreak, codePoint)\n  def isUnprotectedScript(codePoint: Int): Boolean = lookup(unprotectedScripts, codePoint) != 0\n"""
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(header + body + tail, encoding="utf-8")
    args.fixture.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.source_dir / "GraphemeBreakTest.txt", args.fixture)
    # Scala.js has no portable ClassLoader resource stream.  Keep the original
    # UCD fixture above for provenance, and embed that exact text in the shared
    # suite so the JVM and Scala.js courts execute identical cases.
    fixture_text = (args.source_dir / "GraphemeBreakTest.txt").read_text(encoding="utf-8")
    suite_prefix = '''package intaglio

class TextClustersSuite extends munit.FunSuite:
  test("Unicode 17 GraphemeBreakTest fixture") {
    graphemeBreakFixture.linesIterator.zipWithIndex.foreach { case (line, lineNumber) =>
      parseCase(line).foreach { case (text, expected) =>
        assertEquals(TextClusters.boundaries(text), expected, s"fixture line ${lineNumber + 1}: $line")
      }
    }
  }

  test("targeted UAX 29 boundaries and UTF-16 offsets") {
    assertEquals(TextClusters.boundaries("a\\u0308"), Vector(0, 2))
    assertEquals(TextClusters.boundaries("\\r\\n"), Vector(0, 2))
    assertEquals(TextClusters.boundaries("\\u1100\\u1161\\u11A8"), Vector(0, 3))
    assertEquals(TextClusters.boundaries("\\uD83C\\uDDFA\\uD83C\\uDDF8\\uD83C\\uDDE8"), Vector(0, 4, 6))
    assertEquals(TextClusters.boundaries("\\uD83D\\uDC69\\u200D\\uD83D\\uDCBB"), Vector(0, 5))
    assertEquals(TextClusters.boundaries("\\u0915\\u094D\\u0915"), Vector(0, 3))
  }

  test("malformed UTF-16 is rejected while a surrogate pair is accepted") {
    interceptMessage[IllegalArgumentException]("malformed UTF-16: high surrogate at index 0 is not paired") {
      TextClusters.boundaries("\\uD800")
    }
    interceptMessage[IllegalArgumentException]("malformed UTF-16: low surrogate at index 0 has no leading high surrogate") {
      TextClusters.boundaries("\\uDC00")
    }
    assertEquals(TextClusters.boundaries("\\uD83D\\uDE00"), Vector(0, 2))
  }

  test("contextually shaped and unknown scripts are protected") {
    assert(!TextClusters.protectedScript('a'))
    assert(!TextClusters.protectedScript(0x4E00))
    assert(TextClusters.protectedScript(0x0627)) // Arabic
    assert(TextClusters.protectedScript(0x10FFFF)) // Unknown
  }

  private def parseCase(line: String): Option[(String, Vector[Int])] =
    val data = line.takeWhile(_ != '#').trim
    if data.isEmpty then None
    else
      val tokens = data.split("\\\\s+")
      val text = new java.lang.StringBuilder
      val boundaries = Vector.newBuilder[Int]
      var utf16Offset = 0
      tokens.foreach {
        case "÷" => boundaries += utf16Offset
        case "×" => ()
        case scalar =>
          val codePoint = Integer.parseInt(scalar, 16)
          text.appendCodePoint(codePoint)
          utf16Offset += Character.charCount(codePoint)
      }
      Some((text.toString, boundaries.result()))

  private val graphemeBreakFixture = Vector(
'''
    suite_suffix = ''').mkString
'''
    args.suite.parent.mkdir(parents=True, exist_ok=True)
    # A classfile UTF-8 constant may not exceed 65,535 bytes.  Preserve every
    # newline while keeping each literal comfortably below that limit.
    chunks = ["".join(fixture_text.splitlines(keepends=True)[index:index + 100])
              for index in range(0, len(fixture_text.splitlines(keepends=True)), 100)]
    literals = ",\n".join(f'    """{chunk}"""' for chunk in chunks)
    args.suite.write_text(suite_prefix + literals + suite_suffix, encoding="utf-8")


if __name__ == "__main__":
    main()
