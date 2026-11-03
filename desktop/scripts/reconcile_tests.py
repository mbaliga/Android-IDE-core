#!/usr/bin/env python3
"""Test-count reconciliation for the desktop host lane (porting plan LX-1, "Done when").

The plan's done-when says "the host JDK ... runs the mapped domain tests, with the count reconciled
against the 1549 baseline". 1549 is the WP-11 gate's whole-module figure (every test under
core-engine/src/test, one flavor; docs/WP11_GATE_REPORT.md, 2026-08-07, quoted by the plan on
2026-10-06 without a re-run) against an older branch state, so it cannot be the pass bar for a lane
that maps only the domain/ tests of today's tree. The honest invariant is:

    the desktop lane executes EXACTLY the tests that exist in the mapped test sources.

This script derives the left-hand side from the sources (it counts `@Test` annotations in the files
mapping.txt maps, after masking comments/strings/backticked names) and compares it with the
right-hand side, the number Gradle's JUnit XML reports actually record. It also prints the
whole-module source-derived figure so the delta against the plan's 1549 is explained from data.

Usage
  reconcile_tests.py                         # sources only: print the expected counts
  reconcile_tests.py --results DIR           # also compare with Gradle's test-results XML; exit 1 on mismatch
  reconcile_tests.py --results DIR --summary FILE   # also append a markdown summary (e.g. $GITHUB_STEP_SUMMARY)

DIR is normally desktop/core-host/build/test-results/test
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lanelib as L  # noqa: E402

PLAN_BASELINE = 1549  # docs/WP11_GATE_REPORT.md (WP-11 gate, 2026-08-07); quoted by docs/PORTING_PLAN.md on 2026-10-06 without a re-run
TEST_RE = re.compile(r"@(?:org\.junit\.)?Test\b")
IGNORE_RE = re.compile(r"@(?:org\.junit\.)?Ignore\b")


def count_tests(files: List[Path]) -> Tuple[int, int, Dict[str, int]]:
    """(@Test occurrences, @Ignore occurrences, per-file @Test counts for files with >0)."""
    total = ignored = 0
    per_file: Dict[str, int] = {}
    for f in files:
        masked = L.mask_kotlin(L.read_text(f))
        t = len(TEST_RE.findall(masked))
        ignored += len(IGNORE_RE.findall(masked))
        if t:
            per_file[L.rel(f)] = t
        total += t
    return total, ignored, per_file


def read_results(results_dir: Path) -> Tuple[int, int, int, int, int]:
    """(suites, tests, skipped, failures, errors) summed over Gradle's TEST-*.xml files."""
    suites = tests = skipped = failures = errors = 0
    for xml in sorted(results_dir.glob("TEST-*.xml")):
        root = ET.parse(xml).getroot()
        suites += 1
        tests += int(root.get("tests", 0))
        skipped += int(root.get("skipped", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
    return suites, tests, skipped, failures, errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", type=Path, help="Gradle JUnit XML dir (core-host/build/test-results/test)")
    ap.add_argument("--summary", type=Path, help="append a markdown summary to this file")
    ap.add_argument("--verbose", action="store_true", help="print the per-file @Test counts")
    args = ap.parse_args()

    m = L.load_mapping()
    mapped_test_files = L.mapped_files(m, "test")
    expected, ignored, per_file = count_tests(mapped_test_files)

    # Domain tests, before exclusions (what the exclusions cost), and the whole Android module.
    all_domain = []
    for r in m.test_roots:
        all_domain += sorted((L.REPO_ROOT / r).rglob("*.kt"))
    domain_total, _, _ = count_tests(all_domain)
    excl_cost = []
    for e in m.test_excludes:
        n, _, _ = count_tests([L.REPO_ROOT / e.path])
        excl_cost.append((e.path, n))
    module_files = sorted((L.REPO_ROOT / "core-engine" / "src" / "test").rglob("*.kt"))
    module_total, _, _ = count_tests(module_files)

    lines: List[str] = []
    p = lines.append
    p("Desktop host lane (LX-1) test-count reconciliation")
    p("-" * 52)
    p(f"mapped test files                         : {len(mapped_test_files)}")
    p(f"expected tests (@Test in mapped sources)  : {expected}   [@Ignore occurrences: {ignored}]")
    p("")
    p("Where the mapped set sits against the whole Android module (source-derived, same counter):")
    p(f"  whole core-engine/src/test ({len(module_files)} files)         : {module_total}")
    p(f"  of which domain/ tests, before exclusions : {domain_total}")
    for path, n in excl_cost:
        p(f"    excluded {n:3d}  {path}")
    p(f"  mapped (domain minus exclusions)          : {domain_total - sum(n for _, n in excl_cost)}")
    p(f"  plan baseline (WP-11 gate 2026-08-07, whole module, quoted by the plan 2026-10-06) : {PLAN_BASELINE}")
    p(f"  whole-module now vs plan baseline         : {module_total - PLAN_BASELINE:+d}")
    p(f"  mapped vs plan baseline                   : {expected - PLAN_BASELINE:+d}")

    status = 0
    if args.results:
        if not args.results.is_dir():
            L.die(f"results dir not found: {args.results} (run ./gradlew :core-host:test first)")
        suites, tests, skipped, failures, errors = read_results(args.results)
        p("")
        p(f"Gradle JUnit XML ({args.results}):")
        p(f"  suites {suites}  tests {tests}  skipped {skipped}  failures {failures}  errors {errors}")
        p(f"  executed - expected                      : {tests - expected:+d}")
        if tests == 0:
            p("MISMATCH: zero tests executed")
            status = 1
        elif tests != expected:
            p("MISMATCH: the lane executed a different number of tests than the mapped sources contain.")
            p("  (> expected: inherited/parameterised tests; < expected: a class was not collected.)")
            status = 1
        elif failures or errors:
            p("COUNT MATCHES but there are failures/errors -- the lane is red.")
            status = 1
        else:
            p("RECONCILED: executed == expected, 0 failures, 0 errors.")
    if args.verbose:
        p("")
        p("per-file @Test counts (mapped):")
        for f, n in sorted(per_file.items()):
            p(f"  {n:4d}  {f}")

    text = "\n".join(lines)
    print(text)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as fh:
            fh.write("### Desktop host lane test-count reconciliation\n\n```\n" + text + "\n```\n")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
