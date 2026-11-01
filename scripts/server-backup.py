#!/usr/bin/env python3
"""Writes an encrypted backup of the data folder to stdout. It's the only thing the backup login can run.

Encrypting to a public key means the server can't read its own backups, and they're never plain outside it.
"""

import os
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

DATA = Path(os.environ.get("MITAURI_DATA_DIR", "~/.local/share/mitauri")).expanduser()
PUBLIC_KEY = Path("~/backup-public.asc").expanduser()
SKIP = {"models", "voices", "updates"}  # all of these can be downloaded or published again
LIVE_SUFFIXES = {".db-wal", ".db-shm"}


def add_folder(archive: tarfile.TarFile, copies: Path) -> None:
    for path in sorted(DATA.rglob("*")):
        relative = path.relative_to(DATA)
        if relative.parts[0] in SKIP or path.suffix in LIVE_SUFFIXES or not path.is_file() or path.is_symlink():
            continue
        if path.suffix != ".db":
            archive.add(path, arcname=str(relative), recursive=False)
            continue
        # A plain copy of a database in use can come out broken, so SQLite makes the copy.
        copy = copies / path.name
        source = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        target = sqlite3.connect(copy)
        with target:
            source.backup(target)
        source.close()
        target.close()
        archive.add(copy, arcname=str(relative), recursive=False)
        copy.unlink()


def main() -> None:
    if not PUBLIC_KEY.exists():
        sys.exit("No backup key on the server.")
    os.umask(0o077)
    with tempfile.TemporaryDirectory() as copies:
        gpg = subprocess.Popen(
            ["gpg", "--batch", "--quiet", "--no-tty", "--homedir", copies, "--trust-model", "always",
             "--recipient-file", str(PUBLIC_KEY), "--encrypt", "--output", "-"],
            stdin=subprocess.PIPE,
        )
        with tarfile.open(fileobj=gpg.stdin, mode="w|gz") as archive:
            add_folder(archive, Path(copies))
        gpg.stdin.close()
        if gpg.wait() != 0:
            sys.exit("Encrypting the backup failed.")


if __name__ == "__main__":
    main()
