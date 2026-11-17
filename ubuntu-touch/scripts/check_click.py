#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Static checks for the Ubuntu Touch click payload (runs on any Linux, no Docker/Clickable needed).

Enforces the binding rules that can be checked from files: no networking policy group, no external
URLs or remote scripts in the web payload, CSP present, referenced hook files exist, click name shape,
the honest-label strings are present, no signing material. Exit 1 on any violation.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
errs = []
def need(cond, msg):
    if not cond: errs.append(msg)

m = json.loads((ROOT / "manifest.json").read_text())
need(re.fullmatch(r"[a-z0-9][a-z0-9.\-]*\.[a-z0-9][a-z0-9\-]*", m["name"]), f"click name shape: {m['name']}")
need(re.fullmatch(r"\d+\.\d+\.\d+", m["version"]), "version must be x.y.z")
need(m["framework"].startswith("ubuntu-sdk-"), "framework")
need(m["architecture"] == "all", "pure payload must be architecture 'all'")
for hook, files in m["hooks"].items():
    for kind, fn in files.items():
        need((ROOT / fn).is_file(), f"hook {hook}.{kind} -> {fn} missing")

aa = json.loads((ROOT / "fonebrew-diff.apparmor").read_text())
groups = set(aa.get("policy_groups", []))
need(groups <= {"webview", "content_exchange", "content_exchange_source"}, f"policy groups beyond the allowed set: {groups}")
need("networking" not in groups and "connectivity" not in groups, "networking policy group present (rule 1/2)")
need("accounts" not in groups and "keep-display-on" not in groups, "unexpected policy group")

desk = (ROOT / "fonebrew-diff.desktop").read_text()
need("Exec=qmlscene" in desk and "qml/Main.qml" in desk, "desktop Exec")
need(not re.search(r"https?://", desk), "URL in desktop file")

html = (ROOT / "www/index.html").read_text()
need("Content-Security-Policy" in html and "connect-src 'none'" in html, "CSP with connect-src 'none' required")
need("not the Fonebrew Android app" in html, "honest-label string missing from UI")
for p in list((ROOT / "www").glob("*")) + [ROOT / "qml/Main.qml"]:
    t = p.read_text()
    need(not re.search(r"""(src|href)\s*=\s*["']https?://""", t), f"{p.name}: remote resource reference")
    need("XMLHttpRequest" not in t and "fetch(" not in t and "WebSocket" not in t and "sendBeacon" not in t, f"{p.name}: network API used")
qml = (ROOT / "qml/Main.qml").read_text()
need("IgnoreRequest" in qml, "QML must refuse navigation outside the bundle")

bad = [p for p in ROOT.rglob("*") if p.suffix in {".keystore", ".p12", ".pem", ".key", ".jks"} or p.name in {".env", "id_rsa"}]
need(not bad, f"signing/secret material present: {bad}")

if errs:
    print("check_click: FAIL"); [print(" -", e) for e in errs]; sys.exit(1)
print("check_click: ok (manifest, hooks, apparmor groups=%s, CSP, no network APIs, honest label)" % sorted(groups))
