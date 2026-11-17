#!/usr/bin/env python3
"""Guard for apple/shared/mapping.txt: every mapped file exists and main files are common-Kotlin clean
(no java.*, javax.*, android.*, org.json, kotlin.jvm.* JVM-only annotations). Run with python3 -I."""
import re, sys, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
bad = re.compile(r'^\s*import\s+(java\.|javax\.|android\.|org\.json|kotlin\.jvm\.)|\b(java|javax|android)\.[a-z]+\.[A-Za-z]|@(Synchronized|Volatile|JvmStatic|JvmField|JvmOverloads)\b|\.(toSortedSet|toSortedMap)\(|\b(sortedSetOf|sortedMapOf)\(')
errors, n = [], 0
for i, raw in enumerate((root / "apple/shared/mapping.txt").read_text().splitlines(), 1):
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    kind, _, path = [p.strip() for p in line.split(" | ")][:1] + [None] + [line.split(" | ")[1].strip()]
    f = root / path
    if not f.is_file():
        errors.append(f"mapping.txt:{i}: missing {path}"); continue
    n += 1
    if kind == "main":
        for ln, text in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            code = text.split("//")[0]
            if bad.search(code):
                errors.append(f"{path}:{ln}: JVM-only construct in a commonMain file: {text.strip()}")
if errors:
    print("\n".join(errors)); sys.exit(1)
print(f"ok: {n} mapped files exist; main files are common-Kotlin clean")
