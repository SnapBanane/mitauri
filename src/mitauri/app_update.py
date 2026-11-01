"""Holds the newest phone app, so the phone can fetch it without a cable.

The phone downloads over its signed connection, and Android only installs an update carrying the same signature
as the installed app. So this folder needs no secrecy of its own, and the signing key never comes near it.
"""

import hashlib
import json
import os
import shutil
from pathlib import Path

BUILD_FOLDER = Path(__file__).resolve().parents[2] / "android/app/build/outputs/apk/release"


class PublishError(Exception):
    pass


def apk_path(data_dir: Path) -> Path:
    return data_dir / "updates" / "mitauri.apk"


def latest(data_dir: Path) -> dict | None:
    """What's on offer: version_code, version_name, sha256 and size."""
    path = data_dir / "updates" / "latest.json"
    if not path.exists() or not apk_path(data_dir).exists():
        return None
    return json.loads(path.read_text())


def publish(data_dir: Path, source: Path = BUILD_FOLDER) -> dict:
    """Offers a release build, from a folder with the APK and the output-metadata.json Gradle writes beside it."""
    try:
        element = json.loads((source / "output-metadata.json").read_text())["elements"][0]
        apk = source / element["outputFile"]
        version_code, version_name = int(element["versionCode"]), str(element["versionName"])
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        raise PublishError(f"No release build in {source}: {error}") from None
    if not apk.is_file():
        raise PublishError(f"{apk} is missing.")
    current = latest(data_dir)
    if current and version_code <= current["version_code"]:
        raise PublishError(
            f"Version code {version_code} isn't newer than {current['version_code']}, which is already published. "
            "Phones only take a higher one, so raise versionCode and build again."
        )
    folder = data_dir / "updates"
    folder.mkdir(mode=0o700, exist_ok=True)
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    info = {"version_code": version_code, "version_name": version_name, "sha256": digest.hexdigest(), "size": apk.stat().st_size}
    # Each file appears whole. A phone asking in between sees a hash mismatch and tries again later.
    temporary = folder / "mitauri.apk.part"
    shutil.copyfile(apk, temporary)
    temporary.chmod(0o600)
    os.replace(temporary, apk_path(data_dir))
    manifest = folder / "latest.json.part"
    manifest.write_text(json.dumps(info))
    os.replace(manifest, folder / "latest.json")
    return info
