#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ ! -f "$SDK_ROOT/platforms/android-35/android.jar" ]]; then
  echo "缺少 Android SDK 35。请先安装 Android Studio，并在 SDK Manager 安装 Android SDK Platform 35。" >&2
  exit 1
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java)"
fi
VERSION="$("$JAVA" -version 2>&1 | head -1)"
if ! echo "$VERSION" | /usr/bin/grep -Eq '"(17|18|19|20|21|22|23)\.'; then
  echo "构建需要 JDK 17–23，推荐 Android Studio 自带的 JDK 21。当前：$VERSION" >&2
  echo '可设置：export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"' >&2
  exit 1
fi
TOOLS="$PROJECT_ROOT/.tools"
GRADLE_VERSION=8.11.1
GRADLE="$TOOLS/gradle-$GRADLE_VERSION/bin/gradle"
if [[ ! -x "$GRADLE" ]]; then
  mkdir -p "$TOOLS"
  ZIP="$TOOLS/gradle-$GRADLE_VERSION-bin.zip"
  curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  EXPECTED="$(curl -fsSL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256")"
  ACTUAL="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  [[ "$EXPECTED" == "$ACTUAL" ]] || { echo "Gradle 校验失败" >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$TOOLS"
fi
export ANDROID_HOME="$SDK_ROOT"
export GRADLE_USER_HOME="$PROJECT_ROOT/.gradle-cache"
"$GRADLE" -p "$PROJECT_ROOT/android" --no-daemon :app:assembleDebug
mkdir -p "$PROJECT_ROOT/dist"
cp "$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk" "$PROJECT_ROOT/dist/MiDrop-android.apk"
echo "已生成：$PROJECT_ROOT/dist/MiDrop-android.apk"
