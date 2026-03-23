#!/bin/bash
# Build script for komga-gorse extension APK (standalone repo)
#
# Prerequisites:
# - Android SDK installed
# - extensions-source cloned at ~/code/extensions-source (or set EXT_SOURCE_DIR)
# - Java 17+ installed
#
# Usage: ./build-extension.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXT_SOURCE_DIR="${EXT_SOURCE_DIR:-$HOME/code/extensions-source}"

echo "=== Building komga-gorse extension ==="

# 1. Copy extension source to extensions-source
echo "Copying extension source..."
rm -rf "$EXT_SOURCE_DIR/src/all/komgagorse"
mkdir -p "$EXT_SOURCE_DIR/src/all/komgagorse"
cp -r "$SCRIPT_DIR/src" "$SCRIPT_DIR/res" "$SCRIPT_DIR/build.gradle" "$EXT_SOURCE_DIR/src/all/komgagorse/"

# 2. Build the APK
echo "Building APK..."
cd "$EXT_SOURCE_DIR"
./gradlew :src:all:komgagorse:assembleDebug

# 3. Find the built APK
APK_PATH=$(find "$EXT_SOURCE_DIR/src/all/komgagorse/build" -name "*.apk" | head -1)

if [ -z "$APK_PATH" ]; then
    echo "ERROR: APK not found!"
    exit 1
fi

echo "APK built: $APK_PATH"

# 4. Copy APK to repo directory
VERSION_NAME=$(date +%Y.%m%d.%H%M)
APK_NAME="tachiyomi-all.komga-gorse-v${VERSION_NAME}.apk"
mkdir -p "$SCRIPT_DIR/repo/apk"
cp "$APK_PATH" "$SCRIPT_DIR/repo/apk/$APK_NAME"
echo "APK copied to: $SCRIPT_DIR/repo/apk/$APK_NAME"

echo ""
echo "Extension built successfully!"
echo ""
echo "Next steps:"
echo "1. Commit the built APK: git add repo/ && git commit -m '构建 komga-gorse 扩展 APK'"
echo "2. Push to GitHub: git push"
echo "3. In Mihon, add extension repo URL:"
echo "   https://raw.githubusercontent.com/maolei1024/komgagorse-extension/master/repo/index.min.json"
