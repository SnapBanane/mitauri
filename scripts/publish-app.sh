#!/usr/bin/env bash
# Offers the newest release build of the phone app from the server. Only run this when a release is really meant.
set -euo pipefail

server="${MITAURI_SERVER:-opc@mitauri-server}"
key="${MITAURI_SERVER_KEY:-$HOME/.ssh/mitauri_oracle}"
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
build="$repo/android/app/build/outputs/apk/release"

[ -f "$build/output-metadata.json" ] || { echo "No release build. Run: (cd android && ./gradlew assembleRelease)" >&2; exit 1; }

# Only the app and its description go across, and the signing key stays here.
tar -C "$build" -c output-metadata.json "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["outputFile"])' "$build/output-metadata.json")" \
    | ssh -i "$key" "$server" "sudo -iu mitauri bash -c 'folder=\$(mktemp -d) && trap \"rm -rf \$folder\" EXIT && tar -x -C \$folder && ~/current/.venv/bin/mitauri publish-update \$folder'"
