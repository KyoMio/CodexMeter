#!/usr/bin/env bash
# Build and sign a release APK for CodexMeter.
# Signing config is read from ~/.android/codexmeter-release-keystore.properties
# Output APK is copied to the project root as codexmeter-release-<versionName>.apk

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_PROPS="$HOME/.android/codexmeter-release-keystore.properties"
APK_DIR="$PROJECT_ROOT/app/build/outputs/apk/release"

# ── preflight ────────────────────────────────────────────────────────────────
if [[ ! -f "$KEYSTORE_PROPS" ]]; then
  echo "ERROR: keystore properties not found at $KEYSTORE_PROPS" >&2
  exit 1
fi

# source keystore props as shell vars for verification
store_file=$(grep '^storeFile' "$KEYSTORE_PROPS" | cut -d= -f2-)
if [[ ! -f "$store_file" ]]; then
  echo "ERROR: keystore file not found: $store_file" >&2
  exit 1
fi

# ── build ────────────────────────────────────────────────────────────────────
cd "$PROJECT_ROOT"

echo "→ Cleaning previous build..."
./gradlew clean --quiet

echo "→ Assembling release APK..."
./gradlew assembleRelease

# ── locate output ────────────────────────────────────────────────────────────
apk_path=$(find "$APK_DIR" -name "*.apk" | head -1)
if [[ -z "$apk_path" ]]; then
  echo "ERROR: No APK found in $APK_DIR" >&2
  exit 1
fi

# extract versionName from build.gradle.kts
version=$(grep 'versionName\s*=' "$PROJECT_ROOT/app/build.gradle.kts" \
  | grep -o '"[^"]*"' | tr -d '"' | head -1)
dest="$PROJECT_ROOT/codexmeter-release-${version}.apk"

cp "$apk_path" "$dest"

# ── verify signature ─────────────────────────────────────────────────────────
echo "→ Verifying APK signature..."
if command -v apksigner &>/dev/null; then
  apksigner verify --verbose "$dest" 2>&1 | grep -E 'Verified|v[0-9]'
elif command -v jarsigner &>/dev/null; then
  jarsigner -verify -verbose -certs "$dest" 2>&1 | grep -E 'jar verified|WARNING'
else
  echo "  (apksigner/jarsigner not in PATH — skipping verification)"
fi

echo ""
echo "✓ Release APK ready: $dest"
echo "  Size: $(du -sh "$dest" | cut -f1)"
