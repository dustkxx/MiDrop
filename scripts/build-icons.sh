#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/.build/module-cache" "$ROOT/.build/MiDrop.iconset"
swiftc -module-cache-path "$ROOT/.build/module-cache" "$ROOT/scripts/render-assets.swift" -o "$ROOT/.build/render-assets"
"$ROOT/.build/render-assets" "$ROOT"
for SIZE in 16 32 128 256 512; do
  sips -z "$SIZE" "$SIZE" "$ROOT/assets/MiDrop-1024.png" --out "$ROOT/.build/MiDrop.iconset/icon_${SIZE}x${SIZE}.png" >/dev/null
  DOUBLE=$((SIZE*2))
  sips -z "$DOUBLE" "$DOUBLE" "$ROOT/assets/MiDrop-1024.png" --out "$ROOT/.build/MiDrop.iconset/icon_${SIZE}x${SIZE}@2x.png" >/dev/null
done
iconutil -c icns "$ROOT/.build/MiDrop.iconset" -o "$ROOT/assets/MiDrop.icns"
for PAIR in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  DENSITY="${PAIR%:*}"; SIZE="${PAIR#*:}"
  mkdir -p "$ROOT/android/app/src/main/res/mipmap-$DENSITY"
  sips -z "$SIZE" "$SIZE" "$ROOT/assets/MiDrop-1024.png" --out "$ROOT/android/app/src/main/res/mipmap-$DENSITY/ic_launcher.png" >/dev/null
done
