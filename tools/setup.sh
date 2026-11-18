#!/usr/bin/env bash
# Prepares this machine to build the app: the Android SDK in ~/Android/Sdk and
# the Gradle wrapper of the project. Run it once. It asks to accept the licenses
# of the Android SDK: read them, they are Google's.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS_ZIP="commandlinetools-linux-16111833_latest.zip"
GRADLE_VERSION="9.9.1"
cd "$(dirname "$0")/.."

if ! ls /usr/lib/jvm/*/bin/javac >/dev/null 2>&1 && ! command -v javac >/dev/null; then
  echo "Falta el JDK (el compilador de Java). En Ubuntu: sudo apt install openjdk-21-jdk-headless" >&2
  exit 1
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Descargando las herramientas del SDK de Android en $SDK…"
  mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "Licencias del SDK de Android (responde «y» a las que aceptes):"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "platform-tools" "platforms;android-37" "build-tools;36.1.0"

echo "sdk.dir=$SDK" > local.properties

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "Preparando Gradle $GRADLE_VERSION…"
  dist="$HOME/Android/gradle-$GRADLE_VERSION"
  if [ ! -x "$dist/bin/gradle" ]; then
    tmp="$(mktemp -d)"
    curl -fL -o "$tmp/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q "$tmp/gradle.zip" -d "$HOME/Android"
    rm -rf "$tmp"
  fi
  "$dist/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon
fi

echo
echo "Listo. Para compilar: ./gradlew assembleDebug"
