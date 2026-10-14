#!/usr/bin/env python3
"""Generate desktop/PORTABILITY.md -- a static inventory of JVM-only API usage in the host lane's
mapped sources. IOS-1 GROUNDWORK (docs/PORTING_PLAN.md section 6.3), NOT a migration.

IOS-1's job is to move domain/ + contracts/ to Kotlin `commonMain`: java.time -> kotlinx-datetime,
org.json -> kotlinx-serialization, the java.text users -> platform actuals, WatchService and
ProcessBuilder out of the iOS source set. This script produces the work list that migration will
consume. It changes no source, links no library and decides nothing.

The file set is exactly what the Gradle host lane compiles (desktop/core-host/mapping.txt), so the
numbers describe the same sources the lane proves compile and pass on a plain JDK.

Method (stated so the numbers can be audited, and re-derived by re-running this script)
  * Comments, KDoc, string literals and `backticked names` are masked out first (lanelib.mask_kotlin),
    so a doc-comment that mentions org.json is not counted as usage.
  * A "ref" is one CODE line that names the API by package: an `import` line or a fully-qualified use
    (e.g. `java.util.Base64.getEncoder()`), or -- for WatchService/ProcessBuilder -- a line naming the
    type. It is NOT a call-site count: `Instant.now()` on its own line is invisible to a package scan.
    Files are the stable unit; refs are an order-of-magnitude pointer.
  * Nothing is guessed: stdlib JVM-isms with no import (String.format, System.*, Thread, @Synchronized,
    @Volatile, kotlin.jvm.*) are NOT inventoried here; IOS-1 needs a compiler pass (a commonMain
    compile) to find those, and this file says so rather than pretending otherwise.

Usage
  portability_inventory.py            # print the markdown to stdout
  portability_inventory.py --write    # (re)write desktop/PORTABILITY.md
  portability_inventory.py --check    # exit 1 if desktop/PORTABILITY.md differs from a fresh run
"""
from __future__ import annotations

import argparse
import difflib
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lanelib as L  # noqa: E402

OUTPUT = L.DESKTOP_DIR / "PORTABILITY.md"

# (column label, regex over masked code). Order = column order.
CATEGORIES: List[Tuple[str, "re.Pattern[str]"]] = [
    ("org.json", re.compile(r"\borg\.json\b")),
    ("java.time", re.compile(r"\bjava\.time\b")),
    ("java.text", re.compile(r"\bjava\.text\b")),
    (
        "WatchService",
        re.compile(r"\b\w*Watch(?:Service|Key|Event)\w*\b|\bStandardWatchEventKinds\b"),
    ),
    ("ProcessBuilder", re.compile(r"\bProcessBuilder\b")),
    ("Base64 (java.util / android.util)", re.compile(r"\b(?:java\.util|android\.util)\.Base64\b")),
]
BASE64_NAME = re.compile(r"\bBase64\b")
ANDROID_REF = re.compile(r"\bandroid(?:x)?\.[a-z]\w*(?:\.\w+)*")
IMPORT_RE = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*)", re.MULTILINE)
PLATFORM_PREFIXES = ("java.", "javax.", "android.", "androidx.", "org.w3c.", "org.xml.", "sun.", "com.sun.")


class FileInfo:
    __slots__ = ("path", "pkg", "cats", "android", "imports")

    def __init__(self, path: Path, pkg: str):
        self.path = path
        self.pkg = pkg
        self.cats: Dict[str, int] = {}  # category -> ref lines
        self.android: List[str] = []  # android.* code references
        self.imports: List[str] = []  # platform-prefixed imports


def package_of(path: Path, roots: List[str]) -> str:
    rp = path.resolve()
    for r in roots:
        root = (L.REPO_ROOT / r).resolve()
        try:
            sub = rp.relative_to(root)
        except ValueError:
            continue
        if r.startswith("contracts"):
            return "contracts"
        return "domain/" + sub.parts[0] if len(sub.parts) > 1 else "domain (root files)"
    return "?"


def scan(files: List[Path], roots: List[str]) -> List[FileInfo]:
    infos: List[FileInfo] = []
    for f in files:
        masked = L.mask_kotlin(L.read_text(f))
        info = FileInfo(f, package_of(f, roots))
        lines = masked.split("\n")
        for label, rx in CATEGORIES:
            hits = [ln for ln in lines if rx.search(ln)]
            if not hits:
                continue
            if label.startswith("Base64"):
                # Only files that name a JVM Base64; refs = every line using the simple name there.
                n = sum(1 for ln in lines if BASE64_NAME.search(ln))
            else:
                n = len(hits)
            info.cats[label] = n
        info.android = [m.group(0) for ln in lines for m in ANDROID_REF.finditer(ln)]
        for m in IMPORT_RE.finditer(masked):
            name = m.group(1)
            if name.startswith(PLATFORM_PREFIXES):
                info.imports.append(name)
        infos.append(info)
    return infos


def cell(files: int, refs: int) -> str:
    return f"{files} / {refs}" if files else "-"


def per_package_table(infos: List[FileInfo]) -> List[str]:
    labels = [c[0] for c in CATEGORIES]
    short = ["org.json", "java.time", "java.text", "WatchService", "ProcessBuilder", "Base64"]
    pkgs = sorted({i.pkg for i in infos})
    out = ["| package | files | " + " | ".join(short) + " |", "|---|--:|" + "--:|" * len(short)]
    tot_files = 0
    tot = {lab: [0, 0] for lab in labels}
    for p in pkgs:
        members = [i for i in infos if i.pkg == p]
        row = [p, str(len(members))]
        tot_files += len(members)
        for lab in labels:
            hit = [i for i in members if lab in i.cats]
            f, r = len(hit), sum(i.cats[lab] for i in hit)
            tot[lab][0] += f
            tot[lab][1] += r
            row.append(cell(f, r))
        out.append("| " + " | ".join(row) + " |")
    out.append(
        "| **total** | **%d** | " % tot_files
        + " | ".join("**%s**" % cell(*tot[lab]) if tot[lab][0] else "**-**" for lab in labels)
        + " |"
    )
    return out


def other_imports_table(infos: List[FileInfo]) -> List[str]:
    files_by_pkg: Dict[str, set] = defaultdict(set)
    refs_by_pkg: Dict[str, int] = defaultdict(int)
    for i in infos:
        for name in i.imports:
            pkg = name.rsplit(".", 1)[0] if "." in name else name
            files_by_pkg[pkg].add(i.path)
            refs_by_pkg[pkg] += 1
    rows = sorted(files_by_pkg, key=lambda k: (-len(files_by_pkg[k]), k))
    out = ["| imported package | files | import lines |", "|---|--:|--:|"]
    for k in rows:
        out.append(f"| `{k}` | {len(files_by_pkg[k])} | {refs_by_pkg[k]} |")
    if not rows:
        out.append("| (none) | 0 | 0 |")
    return out


def category_file_lists(infos: List[FileInfo]) -> List[str]:
    out: List[str] = []
    for label, _ in CATEGORIES:
        hit = sorted((i for i in infos if label in i.cats), key=lambda i: (i.pkg, L.rel(i.path)))
        out.append(f"<details><summary><b>{label}</b> -- {len(hit)} files</summary>\n")
        if hit:
            out.append("| file | refs |")
            out.append("|---|--:|")
            for i in hit:
                out.append(f"| `{L.rel(i.path)}` | {i.cats[label]} |")
        else:
            out.append("_none in the mapped sources_")
        out.append("\n</details>\n")
    return out


def render() -> str:
    m = L.load_mapping()
    main_files = L.mapped_files(m, "main")
    test_files = L.mapped_files(m, "test")
    main = scan(main_files, m.main_roots)
    test = scan(test_files, m.test_roots)
    p: List[str] = []
    w = p.append

    w("# desktop/PORTABILITY.md")
    w("")
    w("> **GROUNDWORK for IOS-1 — NOT a migration.** This file is a *static inventory* of JVM-only API usage")
    w("> in the sources the desktop host lane (LX-1) maps. Nothing here migrates, replaces or rewrites any")
    w("> code; no `commonMain` source set, no `kotlinx-datetime`, no `kotlinx-serialization` exists in this")
    w("> repo because of it. It is the work list that `docs/PORTING_PLAN.md` step **IOS-1** (domain and")
    w("> contracts to `commonMain`: `org.json` → kotlinx-serialization, `java.time` → kotlinx-datetime, the")
    w("> `java.text` users → platform actuals, `WatchService` / `ProcessBuilder` out of the iOS source set)")
    w("> will consume. IOS-1 itself is gated (`OQ-2`, `OQ-38`, `P-LX seam green`) and is **not started**.")
    w("")
    w("**Generated file — do not edit by hand.** Regenerate with `python3 -I desktop/scripts/portability_inventory.py --write`;")
    w("`--check` fails if this file is stale. The output is deterministic (sorted, no clock, no absolute paths), so")
    w("the same sources always yield the same bytes.")
    w("")
    w("## Scope")
    w("")
    w("The same file set Gradle compiles in `desktop/core-host` (single source: `desktop/core-host/mapping.txt`):")
    w("")
    w("| | files |")
    w("|---|--:|")
    w(f"| mapped **main** (`domain/` + `contracts/kotlin`) | {len(main)} |")
    w(f"| mapped **test** (`domain/` tests) | {len(test)} |")
    w("")
    w("Roots: " + ", ".join(f"`{r}`" for r in m.main_roots + m.test_roots) + ".")
    w("")
    w("Not inventoried because the lane does not compile them (each carries a back-edge into `data/` or the")
    w("`shared-libraries` submodule; see `mapping.txt` for the witness line and reason):")
    w("")
    for e in m.main_excludes + m.test_excludes:
        w(f"- `{e.path}`")
    w("")
    w("## How to read the numbers")
    w("")
    w("Each cell is **`files / refs`**. *files* = mapped files that name the API at all. *refs* = CODE lines that")
    w("name it by package (an `import` line or a fully-qualified use) — or, for `WatchService` and")
    w("`ProcessBuilder`, a line naming the type. Comments, KDoc, string literals and backticked test names are")
    w("masked out first, so documentation that merely *mentions* `org.json` is not counted. **refs is not a")
    w("call-site count** (`JSONObject(...)` on a line with no `org.json` is invisible to a package scan); files are")
    w("the stable unit. `-` means zero.")
    w("")
    w("## 1. Main sources (`domain/` + `contracts/kotlin`) — per package")
    w("")
    p.extend(per_package_table(main))
    w("")
    w("## 2. Test sources (`domain/` tests) — per package")
    w("")
    w("IOS-1 moves tests to `commonTest` \"where they can\"; this is what stands in the way, per package.")
    w("")
    p.extend(per_package_table(test))
    w("")
    w("## 3. Work list: main files per API")
    w("")
    w("These are the files IOS-1 has to touch for each API. (Expand a row.)")
    w("")
    p.extend(category_file_lists(main))
    w("## 4. Android-runtime references in mapped main code (any form, not just `import`)")
    w("")
    w("The porting plan's section 2 says the domain tree has \"no `android.*`/`androidx.*` import (grep)\". That is")
    w("true of `import` lines and false of the code: a fully-qualified reference has no import line. This table")
    w("is what an `import`-grep cannot see. Every row is a place the file cannot compile on a plain JVM without a")
    w("stand-in, and a place iOS needs an `expect`/`actual`.")
    w("")
    android_main = [i for i in main if i.android]
    if android_main:
        w("| file | references |")
        w("|---|---|")
        for i in sorted(android_main, key=lambda i: L.rel(i.path)):
            uniq = sorted(set(i.android))
            w(f"| `{L.rel(i.path)}` | {len(i.android)} × " + ", ".join(f"`{u}`" for u in uniq) + " |")
        w("")
        w("The host lane compiles these files unchanged against `desktop/core-host/src/hostShim/kotlin/android/icu/text/BreakIterator.kt`,")
        w("a compile-time stand-in whose factory throws (the same outcome `android.jar`'s stub produces in the Android")
        w("unit-test lane), so `Segmenter.defaultSource` takes its `java.text` fallback branch, exactly as in the Android gate.")
    else:
        w("_none_")
    w("")
    w("(The same scan over the mapped *test* sources finds: " + (
        ", ".join(sorted({u for i in test for u in i.android})) or "none") + ".)")
    w("")
    w("## 5. Every other platform-specific import (mechanical, main sources)")
    w("")
    w("Imports whose package starts with `java.`, `javax.`, `android.`, `androidx.`, `org.w3c.`, `org.xml.`, `sun.` or")
    w("`com.sun.`, grouped by imported package, most-used first. Several of these are fine on iOS (Kotlin maps")
    w("some `java.util` types) and several are not (`java.util.concurrent`, `java.io`, `java.security`,")
    w("`javax.crypto`); deciding which is IOS-1's analysis, not this file's.")
    w("")
    p.extend(other_imports_table(main))
    w("")
    w("## 6. Same table for mapped test sources")
    w("")
    p.extend(other_imports_table(test))
    w("")
    w("## What this inventory cannot tell you")
    w("")
    w("- **JVM-isms with no import**: `String.format`, `System.currentTimeMillis`, `Thread`, `@Synchronized`,")
    w("  `@Volatile`, `kotlin.jvm.*` annotations and `java.lang.*` types are implicit, so a package scan misses them.")
    w("  The only reliable finder is compiling the same sources as `commonMain` (IOS-1's first step).")
    w("- **Behavioural equivalence** of any replacement (`kotlinx-datetime` vs `java.time` zone rules,")
    w("  `kotlinx-serialization` vs `org.json` number/ordering/escaping): the golden canonicalization vectors")
    w("  (`schemas/loops/fixtures/canonicalization/`, `scripts/canon_reference.py`) must stay byte-identical, and")
    w("  nothing here has been tested against them on a non-JVM target.")
    w("- **iOS runtime behaviour**: nothing in this repo builds, links or runs on iOS. This file is not evidence of")
    w("  iOS feasibility.")
    w("")
    return "\n".join(p) + "\n"


def main_cli() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--write", action="store_true", help="write desktop/PORTABILITY.md")
    g.add_argument("--check", action="store_true", help="exit 1 if desktop/PORTABILITY.md is stale")
    args = ap.parse_args()
    text = render()
    if args.write:
        OUTPUT.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {L.rel(OUTPUT)} ({len(text.splitlines())} lines)")
        return 0
    if args.check:
        if not OUTPUT.is_file():
            print(f"{L.rel(OUTPUT)} does not exist; run with --write", file=sys.stderr)
            return 1
        cur = OUTPUT.read_text(encoding="utf-8")
        if cur == text:
            print(f"{L.rel(OUTPUT)} is current")
            return 0
        diff = difflib.unified_diff(cur.splitlines(), text.splitlines(), "committed", "regenerated", lineterm="", n=0)
        print("\n".join(list(diff)[:60]), file=sys.stderr)
        print(f"{L.rel(OUTPUT)} is STALE; run: python3 -I desktop/scripts/portability_inventory.py --write", file=sys.stderr)
        return 1
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main_cli())
