#!/usr/bin/env bash
set -euo pipefail
: "${SDK_TOOLS_URL:?official dl.google.com archive URL required}"
: "${SDK_TOOLS_SHA256:?verified archive checksum required}"
: "${ANDROID_HOME:?SDK installation directory required}"
case "$SDK_TOOLS_URL" in https://dl.google.com/android/repository/commandlinetools-linux-*.zip) ;; *) echo "Unexpected origin" >&2; exit 2;; esac
test "${ACCEPT_ANDROID_LICENSES:-false}" = true || { echo "License acceptance required" >&2; exit 2; }
test ! -e "$ANDROID_HOME/cmdline-tools/latest" || { echo "Refusing overwrite" >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
curl --fail --location --retry 2 "$SDK_TOOLS_URL" -o "$tmp/tools.zip"
printf '%s  %s\n' "$SDK_TOOLS_SHA256" "$tmp/tools.zip" | sha256sum -c -
unzip -q "$tmp/tools.zip" -d "$tmp"
mkdir -p "$ANDROID_HOME/cmdline-tools"
mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
set +o pipefail
yes | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
set -o pipefail
"$sdkmanager" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-37.1" "build-tools;37.0.0"

