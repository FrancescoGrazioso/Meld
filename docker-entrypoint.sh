#!/usr/bin/env bash
set -euo pipefail

debug_keystore="${HOME}/.android/debug.keystore"

if [[ ! -f "${debug_keystore}" ]]; then
  mkdir -p "$(dirname "${debug_keystore}")"
  keytool -genkeypair -v \
    -keystore "${debug_keystore}" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

exec "$@"
