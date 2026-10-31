#!/usr/bin/env bash
# Creates the key that signs the release app. Keep it: an update signed with a different key can't
# be installed over the app. It stays outside the repo, private to this user.
set -euo pipefail
dir="$HOME/.local/share/mitauri/signing"
[[ -e "$dir/app.keystore" ]] && { echo "A signing key already exists at $dir"; exit 0; }
umask 077
mkdir -p "$dir"
password=$(head -c 32 /dev/urandom | base64 -w0 | tr -d '/+=')
keytool -genkeypair -keystore "$dir/app.keystore" -storepass "$password" -keypass "$password" -alias mitauri \
    -keyalg EC -groupname secp256r1 -validity 36500 -dname "CN=Mitauri" 2>/dev/null
printf 'storeFile=%s\nstorePassword=%s\nkeyPassword=%s\nkeyAlias=mitauri\n' "$dir/app.keystore" "$password" "$password" > "$dir/signing.properties"
echo "Created a signing key at $dir"
