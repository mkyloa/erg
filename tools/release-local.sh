#!/usr/bin/env bash
# Build a signed release APK locally with the key in ~/.config/erg/.
# Usage: tools/release-local.sh 1.2.3
set -euo pipefail
VERSION="${1:?usage: tools/release-local.sh <version>}"
PROPS="${ERG_SIGNING:-$HOME/.config/erg/signing.properties}"
prop() { grep "^$1=" "$PROPS" | cut -d= -f2-; }
export KEYSTORE_FILE="$(prop keystore.file)"
export KEYSTORE_PASSWORD="$(prop keystore.password)"
export SIGNING_KEY_ALIAS="$(prop signing.key.alias)"
export SIGNING_KEY_PASSWORD="$(prop signing.key.password)"
export JELLYFIN_VERSION="$VERSION"
cd "$(dirname "$0")/.."
./gradlew :app:assembleRelease
ls -1 app/build/outputs/apk/release/erg-*-release.apk
