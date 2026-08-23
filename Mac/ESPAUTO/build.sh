#!/bin/bash
# ESPAUTO macOS Build Script - 使用 Xcode + xcodebuild
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="ESPAUTO"
BUILD_DIR="$SCRIPT_DIR/build"
APP_BUNDLE="$BUILD_DIR/$APP_NAME.app"

# 自动检测 Xcode：优先使用 Xcode-beta，回退到正式版
if [ -d "/Applications/Xcode-beta.app" ]; then
    export DEVELOPER_DIR="/Applications/Xcode-beta.app/Contents/Developer"
elif [ -d "/Applications/Xcode.app" ]; then
    export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi

echo "=== Building ESPAUTO for macOS (Xcode) ==="
echo "Using: $(xcodebuild -version 2>/dev/null | head -1 || echo 'unknown')"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "[1/2] Building with xcodebuild..."
cd "$SCRIPT_DIR"
xcodebuild \
    -project ESPAUTO.xcodeproj \
    -scheme ESPAUTO \
    -configuration Release \
    -derivedDataPath "$BUILD_DIR/DerivedData" \
    build

BUILT_APP="$BUILD_DIR/DerivedData/Build/Products/Release/$APP_NAME.app"
if [ ! -d "$BUILT_APP" ]; then
    echo "ERROR: App bundle not found at $BUILT_APP"
    exit 1
fi

echo "[2/2] Copying app bundle..."
cp -R "$BUILT_APP" "$APP_BUNDLE"

echo "=== Build complete! ==="
echo "App: $APP_BUNDLE"
echo "Run: open \"$APP_BUNDLE\""
