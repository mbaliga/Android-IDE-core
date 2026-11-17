#!/usr/bin/env bash
# Headless-Chrome smoke of the real www/ page (CSP included). Needs google-chrome or chromium on PATH.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
chrome="$(command -v google-chrome || command -v chromium || command -v chromium-browser || true)"
[ -n "$chrome" ] || { echo "smoke: no chrome/chromium, SKIPPED"; exit 0; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
cp -r "$here/www" "$tmp/www"; cp "$here/tests/smoke.js" "$tmp/www/smoke.js"
sed -i 's#<script src="app.js"></script>#<script src="app.js"></script><script src="smoke.js"></script>#' "$tmp/www/index.html"
out="$(timeout 40 "$chrome" --headless=new --no-sandbox --no-first-run --disable-background-networking --disable-component-update --disable-sync --disable-extensions --no-proxy-server --disable-dev-shm-usage --disable-gpu --user-data-dir="$tmp/prof" --allow-file-access-from-files \
  --virtual-time-budget=3000 --dump-dom "file://$tmp/www/index.html" 2>/dev/null || true)"
line="$(printf '%s' "$out" | grep -o 'SMOKE:[^<]*' | head -1 || true)"
echo "$line"
case "$line" in *BAD*|"") echo "smoke: FAIL"; exit 1;; *) echo "smoke: ok";; esac
