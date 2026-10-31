#!/usr/bin/env bash
# Blocks Tor Browser on the connected Android phone by installing empty placeholder apps
# under its package names, so the Play Store can't install it. Undo with: block-tor.sh --remove
set -euo pipefail

PACKAGES=(org.torproject.torbrowser org.torproject.torbrowser_alpha)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/35.0.0"
KEYSTORE="$HOME/.local/share/mitauri/tor-block.keystore"

if [[ "${1:-}" == "--remove" ]]; then
    for package in "${PACKAGES[@]}"; do
        adb uninstall "$package" || true
    done
    exit 0
fi

for package in "${PACKAGES[@]}"; do
    if [[ -n "$(adb shell pm path "$package")" ]] && ! adb shell dumpsys package "$package" | grep -q 'versionName=placeholder'; then
        echo "The real $package is installed. Uninstall it first, then run this again." >&2
        exit 1
    fi
done

if [[ ! -f "$KEYSTORE" ]]; then
    # Only signs these empty placeholders, so its password doesn't need to be secret.
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias block \
        -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Tor block" 2>/dev/null
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for package in "${PACKAGES[@]}"; do
    cat > "$work/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$package" android:versionCode="1" android:versionName="placeholder">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />
    <application android:hasCode="false" android:label="Tor block" />
</manifest>
EOF
    "$TOOLS/aapt2" link -o "$work/raw.apk" -I "$SDK/platforms/android-35/android.jar" --manifest "$work/AndroidManifest.xml"
    "$TOOLS/zipalign" -f 4 "$work/raw.apk" "$work/aligned.apk"
    "$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --out "$work/$package.apk" "$work/aligned.apk"
    adb install -r "$work/$package.apk"
    echo "Blocked $package"
done
