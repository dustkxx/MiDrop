#!/bin/bash
# Build this dependency-free Android app directly with the official Android SDK.
# This path also works with the installed JDK 25, without downloading Gradle/JDK 21.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-$PROJECT_ROOT/.sdk}"
TOOLS="$SDK_ROOT/build-tools/35.0.0"
PLATFORM="$SDK_ROOT/platforms/android-35/android.jar"
BUILD="$PROJECT_ROOT/.build/android-sdk"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin"
elif [[ -x /opt/homebrew/opt/openjdk/bin/javac ]]; then
  JAVA_BIN=/opt/homebrew/opt/openjdk/bin
else
  JAVA_BIN="$(dirname "$(command -v javac)")"
fi
export JAVA_HOME="$(cd "$JAVA_BIN/.." && pwd)"
export PATH="$JAVA_BIN:$PATH"
JAVA_MAJOR="$("$JAVA_BIN/javac" -version 2>&1 | awk '{split($2,v,"."); print v[1]}')"
if [[ "$JAVA_MAJOR" -lt 17 ]]; then
  echo '需要 JDK 17+。本机可使用 JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home 运行此脚本。' >&2
  exit 1
fi
[[ -f "$PLATFORM" && -x "$TOOLS/aapt2" ]] || { echo "缺少 Android SDK Platform 35 或 Build Tools 35.0.0" >&2; exit 1; }
mkdir -p "$BUILD/classes" "$BUILD/generated" "$BUILD/dex" "$PROJECT_ROOT/dist" "$PROJECT_ROOT/.tools"
# Clear only compiler outputs, preserving the signing key and downloaded tools.
find "$BUILD/classes" "$BUILD/generated" "$BUILD/dex" -type f -delete
python3 - "$PROJECT_ROOT/android/app/src/main/AndroidManifest.xml" "$BUILD/AndroidManifest.xml" <<'PY'
import sys,xml.etree.ElementTree as E
E.register_namespace('android','http://schemas.android.com/apk/res/android')
tree=E.parse(sys.argv[1]); tree.getroot().set('package','dev.midrop')
tree.write(sys.argv[2],encoding='utf-8',xml_declaration=True)
PY
"$TOOLS/aapt2" compile --dir "$PROJECT_ROOT/android/app/src/main/res" -o "$BUILD/resources.zip"
"$TOOLS/aapt2" link -o "$BUILD/resources.apk" -I "$PLATFORM" \
  --manifest "$BUILD/AndroidManifest.xml" --java "$BUILD/generated" \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 3 --version-name 0.2.1 \
  "$BUILD/resources.zip"
find "$PROJECT_ROOT/android/app/src/main/java" "$BUILD/generated" -name '*.java' > "$BUILD/sources.txt"
"$JAVA_BIN/javac" -encoding UTF-8 --release 8 -classpath "$PLATFORM" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"
"$JAVA_BIN/jar" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
for DEX in "$BUILD"/dex/*.dex; do /usr/bin/zip -q -j "$BUILD/unsigned.apk" "$DEX"; done
"$TOOLS/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
KEYSTORE="$PROJECT_ROOT/.tools/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  "$JAVA_BIN/keytool" -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -validity 10000
  chmod 600 "$KEYSTORE"
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out "$PROJECT_ROOT/dist/MiDrop-android.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$PROJECT_ROOT/dist/MiDrop-android.apk"
"$TOOLS/zipalign" -c -v 4 "$PROJECT_ROOT/dist/MiDrop-android.apk" | tail -1
echo "已生成：$PROJECT_ROOT/dist/MiDrop-android.apk"
