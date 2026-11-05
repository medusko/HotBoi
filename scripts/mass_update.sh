#!/usr/bin/env bash
set -e

# ==============================================================================
# mass_update.sh — HotBoi Automated Build, Release & Device Deploy Pipeline
# ==============================================================================
# Usage:
#   ./scripts/mass_update.sh [--commit-msg "message"]
# ==============================================================================

COMMIT_MSG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --commit-msg|-m)
      COMMIT_MSG="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

echo "=========================================================="
echo "🚀 HotBoi Mass Update Pipeline"
echo "=========================================================="

# 1. Detect Version
ANDROID_VERSION=$(grep 'versionName =' "$ROOT_DIR/app/build.gradle.kts" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')
echo "ℹ️  Detected Android Version: v$ANDROID_VERSION"

# 2. Build Artifacts
echo ""
echo "🔨 Building HotBoi Android APK (v$ANDROID_VERSION)..."
if [[ -z "${JAVA_HOME:-}" || ! -d "$JAVA_HOME" ]]; then
  if [[ -d "/opt/homebrew/opt/openjdk@21" ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
  elif [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi

./gradlew testDebugUnitTest assembleRelease

APK_SRC="app/build/outputs/apk/release/app-release.apk"
APK_DEST="HotBoi-Android-v${ANDROID_VERSION}.apk"
if [[ -f "$APK_SRC" ]]; then
  cp "$APK_SRC" "$APK_DEST"
  echo "✅ Built $APK_DEST ($(du -h "$APK_DEST" | cut -f1))"
else
  echo "❌ Error: Could not locate $APK_SRC"
  exit 1
fi

# 3. Git Commit & Push Main
echo ""
echo "📦 Syncing code to GitHub..."
git add .
if ! git diff --cached --quiet; then
  DEFAULT_MSG="Update HotBoi build: v$ANDROID_VERSION"
  MSG="${COMMIT_MSG:-$DEFAULT_MSG}"
  git commit -m "$MSG"
  echo "✅ Committed: $MSG"
else
  echo "ℹ️  Working tree clean, no new code changes to commit."
fi

git push origin main
echo "✅ Pushed main to origin"

# 4. GitHub Release Tag & Artifact Upload
TAG="v${ANDROID_VERSION}"
echo ""
echo "🏷️  Checking Git tag $TAG..."
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
  git tag "$TAG"
  git push origin "$TAG"
  echo "✅ Created and pushed tag $TAG"
fi

echo "🌐 Publishing GitHub Release for $TAG..."
if command -v gh >/dev/null 2>&1; then
  if gh release view "$TAG" >/dev/null 2>&1; then
    echo "ℹ️  Release $TAG exists, uploading latest APK asset..."
    gh release upload "$TAG" "$APK_DEST" --clobber
  else
    gh release create "$TAG" "$APK_DEST" \
      --title "HotBoi for Android v$ANDROID_VERSION" \
      --notes "Release v$ANDROID_VERSION - see CHANGELOG.md for detailed highlights."
  fi
  echo "✅ Published GitHub Release: https://github.com/medusko/HotBoi/releases/tag/$TAG"
else
  echo "⚠️  'gh' CLI not found; skipping GitHub Release creation."
fi

# 5. Push App to Device (Wireless/USB ADB)
echo ""
echo "📱 Attempting to push app to test device via ADB..."

# Try to connect via Wireless ADB mDNS if device is not currently attached
if ! adb devices | grep -E '\bdevice\b' >/dev/null 2>&1; then
  echo "🔍 Looking up Wireless ADB devices via mDNS..."
  WIFI_SERVICE=$(adb mdns services 2>/dev/null | grep '_adb-tls-connect._tcp' | head -n1 || true)
  if [[ -n "$WIFI_SERVICE" ]]; then
    WIFI_HOST_PORT=$(echo "$WIFI_SERVICE" | awk '{print $3}')
    echo "📡 Found Wireless Debugging service at $WIFI_HOST_PORT, attempting connection..."
    adb connect "$WIFI_HOST_PORT" || true
  fi
fi

# Check again if a device is connected (pick first active device target)
TARGET_DEV=$(adb devices | grep -E '\bdevice\b' | grep -v '_tcp' | head -n1 | awk '{print $1}')
if [[ -z "$TARGET_DEV" ]]; then
  TARGET_DEV=$(adb devices | grep -E '\bdevice\b' | head -n1 | awk '{print $1}')
fi

if [[ -n "$TARGET_DEV" ]]; then
  echo "📲 Installing $APK_DEST to device $TARGET_DEV..."
  adb -s "$TARGET_DEV" install -r "$APK_DEST"
  echo "✅ Successfully installed on $TARGET_DEV!"
else
  echo "⚠️  No ADB device attached. To install wirelessly or via USB:"
  echo "   1) Plug in USB OR enable Wireless Debugging in Developer Options"
  echo "   2) Run: adb install -r $APK_DEST"
fi

echo ""
echo "=========================================================="
echo "🎉 HotBoi Mass Update Complete!"
echo "=========================================================="
