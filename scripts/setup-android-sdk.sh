#!/usr/bin/env bash
# setup-android-sdk.sh — provision the Android SDK so the project can build on a
# clean machine (CI, a fresh dev box, or a Claude Code web session).
#
# Installs the command-line tools, the target platform, and build-tools. The NDK
# is OPTIONAL and only needed to build the native llama.cpp bridge (currently not
# wired into the Gradle build) — pass --with-ndk to include it.
#
# NETWORK: downloads from dl.google.com. Requires outbound egress to that host;
# in restricted network policies this will fail and the SDK must be provided
# another way. The script does not assume egress silently — it will error out.
#
# Usage:
#   ./scripts/setup-android-sdk.sh [--with-ndk]
# Then either export ANDROID_HOME=$HOME/android-sdk or write local.properties
# (the script prints the exact line).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # commandlinetools build number
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"
NDK="ndk;27.0.12077973"
WITH_NDK=0
[ "${1:-}" = "--with-ndk" ] && WITH_NDK=1

log() { printf '\033[1;36m[android-sdk]\033[0m %s\n' "$*"; }

mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Downloading command-line tools..."
  tmp="$(mktemp -d)"
  curl -fSL -o "$tmp/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/cmdtools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
log "Accepting licenses..."
yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true

PKGS=("platform-tools" "$PLATFORM" "$BUILD_TOOLS")
[ "$WITH_NDK" = 1 ] && PKGS+=("$NDK")
log "Installing: ${PKGS[*]}"
"$SDKM" --sdk_root="$ANDROID_HOME" "${PKGS[@]}"

log "Done. SDK at: $ANDROID_HOME"
log "Point Gradle at it with either:"
log "  export ANDROID_HOME=$ANDROID_HOME"
log "  # or write: echo \"sdk.dir=$ANDROID_HOME\" > local.properties"
[ "$WITH_NDK" = 1 ] || log "NDK skipped (native llama.cpp build off). Re-run with --with-ndk to add it."
