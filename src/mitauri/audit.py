"""A record of what Mitauri did. It holds names and outcomes only, never messages or tool arguments."""

import json
import time
from pathlib import Path

MAX_BYTES = 1024 * 1024


def record(data_dir: Path, event: str, **fields) -> None:
    path = data_dir / "audit.log"
    if path.exists() and path.stat().st_size > MAX_BYTES:
        path.replace(data_dir / "audit.old.log")
    with path.open("a") as log:
        log.write(json.dumps({"time": int(time.time()), "event": event, **fields}) + "\n")
