#!/usr/bin/env python3
"""Free-tier catalog freshness pipeline.

The free-tier numbers in app/src/main/assets/free_tiers.json are external facts that drift.
We deliberately do NOT fabricate or auto-overwrite them (binding rule 8: external numbers are
shown with provenance, not asserted as ours). Instead this script:

  1. Reads the catalog's `lastUpdated` and reports its age.
  2. Best-effort fetches the maintained upstream list (cheahjs/free-llm-api-resources) so a
     human (or a future agent) can diff and refresh the figures by hand, with sources.
  3. Exits non-zero when the catalog is stale, so CI can open a reminder issue.

Run locally:  python3 scripts/update_free_tiers.py
"""
from __future__ import annotations

import datetime as dt
import json
import os
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app/src/main/assets/free_tiers.json"
UPSTREAM = "https://raw.githubusercontent.com/cheahjs/free-llm-api-resources/main/README.md"
STALE_DAYS = 35


def age_days(last_updated: str) -> int | None:
    for fmt in ("%Y-%m-%d", "%Y-%m"):
        try:
            d = dt.datetime.strptime(last_updated, fmt).date()
            return (dt.date.today() - d).days
        except ValueError:
            continue
    return None


def fetch_upstream() -> str | None:
    try:
        with urllib.request.urlopen(UPSTREAM, timeout=20) as r:
            return r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 - best effort
        print(f"(could not fetch upstream: {e})")
        return None


def emit_output(stale: bool, age: int | None) -> None:
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write(f"stale={'true' if stale else 'false'}\n")
            f.write(f"age={age if age is not None else -1}\n")


def main() -> int:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    last = data.get("lastUpdated", "")
    age = age_days(last)
    print(f"catalog lastUpdated={last!r} ({len(data.get('providers', []))} providers)")
    if age is None:
        print("could not parse lastUpdated — treat as stale")
    else:
        print(f"age: {age} days (threshold {STALE_DAYS})")

    up = fetch_upstream()
    if up:
        scratch = ROOT / "build" / "free_tiers_upstream.md"
        scratch.parent.mkdir(parents=True, exist_ok=True)
        scratch.write_text(up, encoding="utf-8")
        print(f"upstream snapshot written to {scratch} ({len(up)} bytes) — diff by hand to refresh figures")

    stale = age is None or age > STALE_DAYS
    emit_output(stale, age)
    if stale:
        print("STALE: refresh app/src/main/assets/free_tiers.json (verify each figure on its sourceUrl).")
        return 1
    print("fresh.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
