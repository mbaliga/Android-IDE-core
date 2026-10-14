#!/usr/bin/env python3
"""Fail if the desktop/ build's pins have drifted from the repo-root pins they mirror.

The host lane is only meaningful if it compiles the Android core's sources with the SAME compiler and
tests them with the SAME libraries the Android gate uses. desktop/gradle/libs.versions.toml therefore
re-states four root pins; this script proves they are still equal. It READS the root build files and
never writes them. Exit 0 = parity, 1 = drift (or a root pin could not be found).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lanelib as L  # noqa: E402


def toml_versions(path: Path) -> dict:
    """Parse only the [versions] table: `name = "value"` lines."""
    out, in_versions = {}, False
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if in_versions:
            m = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"', line)
            if m:
                out[m.group(1)] = m.group(2)
    return out


def main() -> int:
    desk = toml_versions(L.DESKTOP_DIR / "gradle" / "libs.versions.toml")
    root = toml_versions(L.REPO_ROOT / "gradle" / "libs.versions.toml")
    core_build = (L.REPO_ROOT / "core-engine" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'"org\.json:json:([0-9A-Za-z.]+)"', core_build)
    root_json = m.group(1) if m else None

    pairs = [
        ("kotlin", desk.get("kotlin"), root.get("kotlin"), "gradle/libs.versions.toml [versions] kotlin"),
        ("coroutines", desk.get("coroutines"), root.get("coroutines"), "gradle/libs.versions.toml [versions] coroutines"),
        ("junit", desk.get("junit"), root.get("junit"), "gradle/libs.versions.toml [versions] junit"),
        ("org.json", desk.get("orgJson"), root_json, "core-engine/build.gradle.kts testImplementation org.json:json"),
    ]
    bad = 0
    for name, d, r, where in pairs:
        ok = d is not None and d == r
        print(f"{'ok   ' if ok else 'DRIFT'} {name:11s} desktop={d} root={r}  ({where})")
        bad += 0 if ok else 1
    if bad:
        print(f"{bad} pin(s) drifted: desktop/gradle/libs.versions.toml must equal the root pins.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
