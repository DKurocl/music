#!/usr/bin/env bash
set -euo pipefail
set +x
test "$#" -eq 2 || { echo "Usage: sign_local.sh unsigned.apk signed.apk" >&2; exit 2; }
: "${ANDROID_HOME:?}"
: "${OWNER_KEYSTORE_PATH:?}"
: "${OWNER_KEYSTORE_PASSWORD:?}"
: "${OWNER_KEY_ALIAS:?}"
: "${OWNER_KEY_PASSWORD:?}"
: "${OWNER_CERT_SHA256:?trusted certificate SHA256 required}"
tools="$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-37.0.0}"
test ! -e "$2" || { echo "Refusing overwrite" >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
"$tools/zipalign" -p -f 4 "$1" "$tmp/aligned.apk"
"$tools/apksigner" sign --ks "$OWNER_KEYSTORE_PATH" --ks-key-alias "$OWNER_KEY_ALIAS" --ks-pass env:OWNER_KEYSTORE_PASSWORD --key-pass env:OWNER_KEY_PASSWORD --out "$tmp/signed.apk" "$tmp/aligned.apk"
"$tools/apksigner" verify --verbose --print-certs "$tmp/signed.apk" > "$tmp/verify.txt"
actual="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$tmp/verify.txt" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
expected="$(printf '%s' "$OWNER_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
test -n "$actual" && test "$actual" = "$expected" || { echo "Owner certificate mismatch" >&2; exit 2; }
"$tools/zipalign" -c 4 "$tmp/signed.apk"
cp "$tmp/signed.apk" "$2"
sha256sum "$2"

