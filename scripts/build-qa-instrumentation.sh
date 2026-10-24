#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$ROOT/.sdk/build-tools/35.0.0"
BUILD="$ROOT/.build/android-instrumentation"
mkdir -p "$BUILD/classes" "$BUILD/dex"
cat > "$BUILD/manifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.midrop.qa"><application android:label="MiDrop QA"/><instrumentation android:name="dev.midrop.qa.TrustInstrumentation" android:targetPackage="dev.midrop" android:functionalTest="true"/></manifest>
XML
"$TOOLS/aapt2" link --manifest "$BUILD/manifest.xml" -I "$ROOT/.sdk/platforms/android-35/android.jar" --min-sdk-version 26 --target-sdk-version 35 -o "$BUILD/resources.apk"
javac --release 8 -Xlint:-options -classpath "$ROOT/.sdk/platforms/android-35/android.jar" -d "$BUILD/classes" "$ROOT/tests/android-qa/TrustInstrumentation.java"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$TOOLS/d8" --lib "$ROOT/.sdk/platforms/android-35/android.jar" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
zip -q -j "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex"
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" sign --ks "$ROOT/.tools/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --out "$BUILD/qa.apk" "$BUILD/aligned.apk"
