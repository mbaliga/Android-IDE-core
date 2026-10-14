"""Shared helpers for the desktop/ host-lane scripts (porting plan LX-1 / IOS-1 groundwork).

Pure standard library, Python 3.8+. Nothing here touches the network or writes outside the paths a
caller names. Everything is deterministic: file lists are sorted, nothing reads a clock.

What lives here
  * load_mapping()   -- parses desktop/core-host/mapping.txt, the single source of truth for which
                        files the host lane maps and excludes (build.gradle.kts reads the same file).
  * mapped_files()   -- the exact main/test file sets Gradle compiles (roots minus exclusions).
  * mask_kotlin()    -- replaces comments, string/char literals and `backticked names` with spaces so
                        that regex counting sees CODE only (a KDoc mention of org.json is not usage,
                        and a test whose backticked name contains "@Test" is not a second test).
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Set

DESKTOP_DIR = Path(__file__).resolve().parent.parent  # .../desktop
REPO_ROOT = DESKTOP_DIR.parent
MAPPING_FILE = DESKTOP_DIR / "core-host" / "mapping.txt"


@dataclass
class Exclusion:
    path: str  # repo-root-relative, forward slashes
    witness: str
    reason: str


@dataclass
class Mapping:
    main_roots: List[str] = field(default_factory=list)
    test_roots: List[str] = field(default_factory=list)
    main_excludes: List[Exclusion] = field(default_factory=list)
    test_excludes: List[Exclusion] = field(default_factory=list)


def load_mapping(path: Path = MAPPING_FILE) -> Mapping:
    m = Mapping()
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split(" | ")]
        kind = parts[0]
        if kind == "main-root":
            m.main_roots.append(parts[1])
        elif kind == "test-root":
            m.test_roots.append(parts[1])
        elif kind in ("main-exclude", "test-exclude"):
            if len(parts) != 4:
                raise SystemExit(f"{path}:{lineno}: {kind} needs 'kind | file | witness | reason'")
            ex = Exclusion(parts[1], parts[2], parts[3])
            (m.main_excludes if kind == "main-exclude" else m.test_excludes).append(ex)
        else:
            raise SystemExit(f"{path}:{lineno}: unknown mapping kind {kind!r}")
    return m


def _kt_files(root: Path) -> List[Path]:
    return sorted(p for p in root.rglob("*.kt") if p.is_file())


def mapped_files(m: Mapping, kind: str) -> List[Path]:
    """Absolute paths of the .kt files the host lane compiles. kind is 'main' or 'test'."""
    roots = m.main_roots if kind == "main" else m.test_roots
    excl = {(REPO_ROOT / e.path).resolve() for e in (m.main_excludes if kind == "main" else m.test_excludes)}
    seen: Set[Path] = set()
    out: List[Path] = []
    for r in roots:
        for p in _kt_files(REPO_ROOT / r):
            rp = p.resolve()
            if rp in excl or rp in seen:
                continue
            seen.add(rp)
            out.append(p)
    return sorted(out)


def read_text(p: Path) -> str:
    # errors='replace': one mapped test file contains raw control bytes (grep treats it as binary).
    return p.read_bytes().decode("utf-8", errors="replace")


# ---------------------------------------------------------------------------------------------
# Kotlin lexical masking
# ---------------------------------------------------------------------------------------------
def mask_kotlin(src: str) -> str:
    """Return `src` with every comment, string/char literal body and `backticked name` replaced by
    spaces (newlines kept, so line numbers and line counts are preserved). Code, including code in
    `${...}` string templates, is left untouched.

    Handles: // line comments, nested /* */ block comments (KDoc included), "..." strings with
    escapes, \"\"\"raw strings\"\"\", '.' char literals with escapes, `names with 'apostrophes'`, and
    ${ } template expressions (nested braces) inside strings.
    """
    n = len(src)
    out: List[str] = []
    i = 0
    # Stack of frames. 'code' frames carry a brace depth (only the template frames use it).
    # Frame kinds: ('code', depth) / ('str',) / ('raw',)
    stack: List[list] = [["code", 0, False]]  # [kind, depth, is_template]

    def blank(ch: str) -> str:
        return "\n" if ch == "\n" else " "

    while i < n:
        top = stack[-1]
        kind = top[0]
        c = src[i]
        if kind == "code":
            two = src[i : i + 2]
            if two == "//":
                j = src.find("\n", i)
                j = n if j == -1 else j
                out.append(" " * (j - i))
                i = j
            elif two == "/*":
                depth = 1
                j = i + 2
                while j < n and depth:
                    if src.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif src.startswith("*/", j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
                out.append("".join(blank(ch) for ch in src[i:j]))
                i = j
            elif src.startswith('"""', i):
                out.append('"""')
                stack.append(["raw"])
                i += 3
            elif c == '"':
                out.append('"')
                stack.append(["str"])
                i += 1
            elif c == "'":
                j = i + 1
                while j < n and src[j] != "'":
                    j += 2 if src[j] == "\\" else 1
                j = min(j + 1, n)
                out.append("'" + " " * max(0, j - i - 2) + ("'" if j - i >= 2 else ""))
                i = j
            elif c == "`":
                j = src.find("`", i + 1)
                j = n - 1 if j == -1 else j
                out.append("`" + "".join(blank(ch) for ch in src[i + 1 : j]) + "`")
                i = j + 1
            elif c == "{":
                top[1] += 1
                out.append(c)
                i += 1
            elif c == "}":
                if top[1] == 0 and top[2]:  # closes a ${ } template expression
                    stack.pop()
                    out.append(c)
                    i += 1
                else:
                    top[1] = max(0, top[1] - 1)
                    out.append(c)
                    i += 1
            else:
                out.append(c)
                i += 1
        elif kind == "str":
            if c == "\\":
                out.append("  ")
                i += 2
            elif c == '"':
                out.append('"')
                stack.pop()
                i += 1
            elif c == "$" and src.startswith("${", i):
                out.append("${")
                stack.append(["code", 0, True])
                i += 2
            else:
                out.append(blank(c))
                i += 1
        else:  # raw string
            if src.startswith('"""', i):
                # a raw string ends at the LAST quote of a run of 3+ quotes
                j = i
                while j < n and src[j] == '"':
                    j += 1
                out.append('"' * (j - i))
                stack.pop()
                i = j
            elif c == "$" and src.startswith("${", i):
                out.append("${")
                stack.append(["code", 0, True])
                i += 2
            else:
                out.append(blank(c))
                i += 1
    return "".join(out)


def rel(p: Path) -> str:
    return p.resolve().relative_to(REPO_ROOT.resolve()).as_posix()


def die(msg: str) -> "None":
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(2)
