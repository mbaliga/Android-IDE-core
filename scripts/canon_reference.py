import json, hashlib, unicodedata, os

OUT = "" + os.path.dirname(os.path.dirname(os.path.abspath(__file__))) + "/schemas/loops/fixtures/canonicalization"
os.makedirs(OUT, exist_ok=True)

def nfc_deep(obj):
    if isinstance(obj, str):
        return unicodedata.normalize('NFC', obj)
    if isinstance(obj, dict):
        return {nfc_deep(k): nfc_deep(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [nfc_deep(v) for v in obj]
    return obj

def check_number_policy(obj, path="$"):
    # forbid floats anywhere; ints ok; decimal strings are just strings (already fine)
    if isinstance(obj, float):
        raise ValueError(f"forbidden IEEE-754 double at {path}")
    if isinstance(obj, dict):
        for k, v in obj.items():
            check_number_policy(v, f"{path}.{k}")
    if isinstance(obj, list):
        for i, v in enumerate(obj):
            check_number_policy(v, f"{path}[{i}]")

def canonicalize(obj):
    check_number_policy(obj)
    obj = nfc_deep(obj)
    # sort_keys=True in Python sorts by code point, equivalent to UTF-16 code unit
    # order for BMP-only content (all vectors here are BMP-only).
    return json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(',', ':'))

def emit(name, obj, note):
    canon = canonicalize(obj)
    canon_bytes = canon.encode('utf-8')
    digest = hashlib.sha256(canon_bytes).hexdigest()
    with open(f"{OUT}/{name}.input.json", "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(f"{OUT}/{name}.canonical.txt", "w", encoding="utf-8") as f:
        f.write(canon)
    with open(f"{OUT}/{name}.digest.txt", "w", encoding="utf-8") as f:
        f.write(f"sha256:{digest}\n")
    with open(f"{OUT}/{name}.note.txt", "w", encoding="utf-8") as f:
        f.write(note + "\n")
    print(name, "->", canon, "->", digest)

# 1. key ordering: keys given out of order must sort by UTF-16 code unit
emit("01-key-order", {"b": 1, "a": 2, "A": 3, "aa": 4}, "Proves object-key sorting by UTF-16 code unit; uppercase A (0x41) sorts before lowercase a (0x61).")

# 2. integer-only numbers pass; decimal quantities carried as constrained decimal strings
emit("02-decimal-as-string", {"budgetWeight": "0.750", "stepCount": 12, "negativeAllowed": "-3.5"}, "Decimal quantities are JSON strings matching ^-?[0-9]+\\.[0-9]+$, never bare JSON floats. stepCount stays a plain integer.")

# 3. NFC normalization: NFD-decomposed input normalizes to NFC in canonical output
emit("03-nfc-normalization", {"title": "Café"}, "Input string uses combining acute accent (NFD: e + U+0301); canonical form MUST be NFC-composed (é).")

# 4. array order preserved (arrays are ordered data, never sorted)
emit("04-array-order-preserved", {"steps": ["third", "first", "second"]}, "Array element order is source order; only OBJECT keys are sorted, never array elements.")

# 5. nested object with keys needing recursive sort
emit("05-nested-sort", {"z": {"y": 1, "x": 2}, "a": {"c": 3, "b": 4}}, "Recursive key sorting applies at every depth, independently per object.")

# 6. no insignificant whitespace regardless of pretty input
emit("06-no-whitespace", {"a": 1, "b": [1, 2, 3], "c": {"d": True}}, "Canonical form has zero insignificant whitespace: no spaces after ':' or ','.")

print("done")
