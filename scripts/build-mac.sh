#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="${MIDROP_APP_OUTPUT:-$PROJECT_ROOT/dist/MiDrop.app}"
if [[ ! -f "$PROJECT_ROOT/assets/MiDrop.icns" ]]; then bash "$PROJECT_ROOT/scripts/build-icons.sh"; fi
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$PROJECT_ROOT/.build/module-cache"
swiftc -O -target "$(uname -m)-apple-macosx13.0" -module-cache-path "$PROJECT_ROOT/.build/module-cache" \
  "$PROJECT_ROOT/mac/MiDrop.swift" "$PROJECT_ROOT/mac/StateDirectoryMonitor.swift" -o "$APP/Contents/MacOS/MiDrop" -framework SwiftUI -framework AppKit
cp "$PROJECT_ROOT/mac/receiver.py" "$APP/Contents/Resources/receiver.py"
cp "$PROJECT_ROOT/assets/MiDrop.icns" "$APP/Contents/Resources/MiDrop.icns"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.midrop.mac</string>
<key>CFBundleName</key><string>MiDrop</string>
<key>CFBundleExecutable</key><string>MiDrop</string>
<key>CFBundleIconFile</key><string>MiDrop.icns</string>
<key>LSUIElement</key><true/>
<key>CFBundleVersion</key><string>3</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.2.1</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>NSLocalNetworkUsageDescription</key><string>发现小米手机并接收局域网内发送的照片和文件。</string>
<key>NSBonjourServices</key><array><string>_midrop._tcp</string></array>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$APP"
echo "已生成：$APP"
