#!/usr/bin/env python3
"""Fail if a spike's DEFAULT pins have drifted from the repo's do-not-regress pins.

Each spike is an isolated Gradle build, so it re-states the root pins as literals. A spike only means
"works at the CURRENT pins" while those literals still equal gradle/libs.versions.toml. This script
READS the root catalog and the spike build files and never writes anything.
Exit 0 = parity, 1 = drift or a literal could not be found.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def root_versions() -> dict:
    out, in_versions = {}, False
    for raw in (ROOT / "gradle" / "libs.versions.toml").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if in_versions:
            m = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', line)
            if m:
                out[m.group(1)] = m.group(2)
    return out


# (spike file, regex whose group(1) is the pinned literal, root catalog key)
CHECKS = [
    ("S-FB1/build.gradle.kts", r'kotlin\("multiplatform"\) version "([^"]+)"', "kotlin"),
    ("S-FB1/build.gradle.kts", r'id\("com\.google\.devtools\.ksp"\) version "([^"]+)"', "ksp"),
    ("S-FB1/build.gradle.kts", r'id\("androidx\.room"\) version "([^"]+)"', "room"),
    ("S-FB1/build.gradle.kts", r'val roomVersion = "([^"]+)"', "room"),
    ("S-FB1/build.gradle.kts", r'val coroutinesVersion = "([^"]+)"', "coroutines"),
    ("S-FB1/build.gradle.kts", r'orElse\("([^"]+)"\)', "sqliteBundled"),
    ("S-FB2/build.gradle.kts", r'kotlin\("multiplatform"\) version "([^"]+)"', "kotlin"),
    ("S-FB2/build.gradle.kts", r'id\("com\.google\.devtools\.ksp"\) version "([^"]+)"', "ksp"),
    ("S-FB2/build.gradle.kts", r'id\("app\.cash\.sqldelight"\) version "([^"]+)"', "sqldelight"),
    ("S-FB2/build.gradle.kts", r'val roomVersion = "([^"]+)"', "room"),
    ("S-FB2/build.gradle.kts", r'val sqldelightVersion = "([^"]+)"', "sqldelight"),
    ("S-FB2/build.gradle.kts", r'val sqldelightAndroidxDriverVersion = "([^"]+)"', "sqldelightAndroidxDriver"),
    ("S-FB2/build.gradle.kts", r'val sqliteBundledVersion = "([^"]+)"', "sqliteBundled"),
    ("S-FB2/build.gradle.kts", r'val coroutinesVersion = "([^"]+)"', "coroutines"),
    ("S-FB3/build.gradle.kts", r'kotlin\("jvm"\) version "([^"]+)"', "kotlin"),
    ("S-FB4/build.gradle.kts", r'multiplatform-markdown-renderer-m3:([0-9.]+)"', "markdownRenderer"),
    # S-FB4 is deliberately re-runnable on other Kotlin/Compose pairings (-P overrides); only its DEFAULT must equal the pin.
    ("S-FB4/gradle.properties", r'^kotlinVersion=(\S+)', "kotlin"),
]


def main() -> int:
    root = root_versions()
    bad = 0
    for rel, rx, key in CHECKS:
        text = (HERE / rel).read_text(encoding="utf-8")
        m = re.search(rx, text, re.M)
        want = root.get(key)
        got = m.group(1) if m else None
        ok = got is not None and got == want
        print(f"{'ok   ' if ok else 'DRIFT'} {rel:26s} {key:26s} spike={got} root={want}")
        bad += 0 if ok else 1
    if bad:
        print(f"{bad} pin(s) drifted: update the spike literals (or re-run the spike and re-record its verdict).", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
