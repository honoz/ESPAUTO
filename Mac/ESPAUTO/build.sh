#!/bin/bash
# ESPAUTO macOS Build Script - 使用 Xcode-beta + SwiftUI + Liquid Glass
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_NAME="ESPAUTO"
BUILD_DIR="$SCRIPT_DIR/build"
APP_BUNDLE="$BUILD_DIR/$APP_NAME.app"
PLUGIN_PATH="/Applications/Xcode-beta.app/Contents/Developer/Platforms/MacOSX.platform/Developer/usr/lib/swift/host/plugins"

echo "=== Building ESPAUTO for macOS (SwiftUI + Liquid Glass) ==="

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

SOURCES=$(find "$SCRIPT_DIR/ESPAUTO" -name "*.swift" -type f)

echo "[1/3] Compiling..."
cd "$SCRIPT_DIR"
swiftc \
    -plugin-path "$PLUGIN_PATH" \
    -target arm64-apple-macos26.0 \
    -framework CoreBluetooth \
    -framework AVFoundation \
    -framework CoreVideo \
    -framework CoreMedia \
    -framework AppKit \
    -framework CoreImage \
    -framework SwiftUI \
    -O \
    $SOURCES \
    -o "$BUILD_DIR/$APP_NAME" 2>&1

if [ ! -f "$BUILD_DIR/$APP_NAME" ]; then echo "ERROR: Binary not found"; exit 1; fi
echo "Binary size: $(du -h "$BUILD_DIR/$APP_NAME" | cut -f1)"

echo "[2/3] Creating .app bundle..."
mkdir -p "$APP_BUNDLE/Contents/MacOS" "$APP_BUNDLE/Contents/Resources"
cp "$BUILD_DIR/$APP_NAME" "$APP_BUNDLE/Contents/MacOS/$APP_NAME"
cp "$SCRIPT_DIR/Info.plist" "$APP_BUNDLE/Contents/"
if [ -f "$SCRIPT_DIR/ESPAUTO/Resources/AppIcon.icns" ]; then
    cp "$SCRIPT_DIR/ESPAUTO/Resources/AppIcon.icns" "$APP_BUNDLE/Contents/Resources/"
fi
# 拷贝本地化资源（.lproj）：系统面板（保存/打开等）语言取决于 App 声明的支持语言，
# 缺少 .lproj 时系统会认为 App 只支持英文，面板按钮会显示英文
cp -R "$SCRIPT_DIR"/ESPAUTO/Resources/*.lproj "$APP_BUNDLE/Contents/Resources/"
chmod +x "$APP_BUNDLE/Contents/MacOS/$APP_NAME"

# Ad-hoc 代码签名：未签名的 App 在其他机器上会被 Gatekeeper 隔离属性拦截，
# 且可能无法正常触发系统权限（蓝牙）弹窗
echo "[3/4] Ad-hoc codesign..."
codesign --force --deep --sign - "$APP_BUNDLE"

echo "[4/4] Build complete!"
echo "App: $APP_BUNDLE"
echo "Run: open \"$APP_BUNDLE\""
