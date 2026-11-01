"""A read-only status page for the user's browser. The tailnet can include other people, so it has its own login.
Nothing shown here is saved: the phone's location stays in memory, and only while someone is looking."""

import hashlib
import json
import logging
import os
import secrets
import shutil
import time
from collections import deque
from pathlib import Path

CODE_LIFETIME_SECONDS = 600
CODE_ATTEMPTS = 5
SESSION_SECONDS = 30 * 24 * 3600
COOKIE = "mitauri_dashboard"
FILES = {
    "/dashboard": ("page.html", "text/html"),
    "/dashboard/page.css": ("page.css", "text/css"),
    "/dashboard/page.js": ("page.js", "text/javascript"),
}
HEADERS = {
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    ),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}
MAX_ERROR_CHARS = 300
MAX_PROBLEMS = 30
PROBLEM_WINDOW_SECONDS = 24 * 3600
AUDIT_TAIL_BYTES = 128 * 1024
LOW_WATCH_BATTERY = 20
LOW_DISK_PERCENT = 10


def _digest(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


class Access:
    """One-time codes from the terminal become browser sessions. Only hashes are stored."""

    def __init__(self, data_dir: Path):
        self._code_file = data_dir / "dashboard_code.json"
        self._sessions_file = data_dir / "dashboard_sessions.json"

    def new_code(self) -> str:
        code = secrets.token_urlsafe(16)
        self._code_file.write_text(json.dumps({
            "hash": _digest(code), "expires": time.time() + CODE_LIFETIME_SECONDS, "attempts": 0,
        }))
        return code

    def login(self, code: str) -> str | None:
        """Returns a new session token, or None if the code is wrong, used up or expired."""
        if not self._code_file.exists():
            return None
        state = json.loads(self._code_file.read_text())
        if time.time() > state["expires"]:
            self._code_file.unlink()
            return None
        if not secrets.compare_digest(_digest(code), state["hash"]):
            state["attempts"] += 1
            if state["attempts"] >= CODE_ATTEMPTS:
                self._code_file.unlink()
            else:
                self._code_file.write_text(json.dumps(state))
            return None
        self._code_file.unlink()
        token = secrets.token_urlsafe(32)
        sessions = self._sessions()
        sessions[_digest(token)] = time.time() + SESSION_SECONDS
        self._save(sessions)
        return token

    def is_valid(self, token: str) -> bool:
        return bool(token) and self._sessions().get(_digest(token), 0) > time.time()

    def logout(self, token: str) -> None:
        sessions = self._sessions()
        if sessions.pop(_digest(token), None) is not None:
            self._save(sessions)

    def revoke_all(self) -> None:
        self._save({})

    def _sessions(self) -> dict[str, float]:
        if not self._sessions_file.exists():
            return {}
        now = time.time()
        return {key: until for key, until in json.loads(self._sessions_file.read_text()).items() if until > now}

    def _save(self, sessions: dict[str, float]) -> None:
        self._sessions_file.write_text(json.dumps(sessions))


class ErrorFeed(logging.Handler):
    """Keeps the newest warnings and errors in memory. An exception shows only its type, as its text can hold content."""

    def __init__(self, size: int = 50):
        super().__init__(logging.WARNING)
        self.entries: deque[dict] = deque(maxlen=size)

    def emit(self, record: logging.LogRecord) -> None:
        text = record.getMessage()
        if record.exc_info and record.exc_info[0]:
            text += f" ({record.exc_info[0].__name__})"
        self.entries.append({"time": int(record.created), "source": record.name, "text": text[:MAX_ERROR_CHARS]})


def _audit_problem(entry: dict) -> str | None:
    event = entry.get("event")
    if event == "auth_rejected":
        return f"Refused a request from {entry.get('address', 'unknown')}"
    if event == "tool" and entry.get("ok") is False:
        return f"Tool {entry.get('name', 'unknown')} failed"
    if event == "phone_request" and entry.get("ok") is False:
        return f"Phone couldn't do {entry.get('kind', 'a request')}"
    return None


def audit_problems(data_dir: Path, now: float) -> list[dict]:
    path = data_dir / "audit.log"
    if not path.exists():
        return []
    with path.open("rb") as log:
        log.seek(max(0, path.stat().st_size - AUDIT_TAIL_BYTES))
        lines = log.read().decode(errors="replace").splitlines()
    problems = []
    for line in lines:
        try:
            entry = json.loads(line)
            text = _audit_problem(entry)
            when = int(entry["time"])
        except (ValueError, KeyError, TypeError, AttributeError):
            continue  # the first line can be cut in half
        if text and now - when <= PROBLEM_WINDOW_SECONDS:
            problems.append({"time": when, "source": "audit", "text": text})
    return problems


def claude_problems(data_dir: Path) -> list[dict]:
    """What Claude Code last wrote to stderr. The file starts empty each time its process starts."""
    problems = []
    for path in sorted(data_dir.glob("claude-errors*.log")):
        lines = path.read_text(errors="replace").strip().splitlines()
        if lines:
            problems.append({"time": int(path.stat().st_mtime), "source": "claude", "text": lines[-1][:MAX_ERROR_CHARS]})
    return problems


def recent_problems(data_dir: Path, feed: ErrorFeed, now: float) -> list[dict]:
    found = audit_problems(data_dir, now) + claude_problems(data_dir) + list(feed.entries)
    return sorted(found, key=lambda problem: problem["time"], reverse=True)[:MAX_PROBLEMS]


def host_health(data_dir: Path) -> dict:
    disk = shutil.disk_usage(data_dir)
    health = {"disk_free_gb": round(disk.free / 1e9, 1), "disk_free_percent": round(100 * disk.free / disk.total)}
    if hasattr(os, "getloadavg"):  # not on Windows
        health["load"] = round(os.getloadavg()[0], 2)
    try:
        pages = int(Path("/proc/self/statm").read_text().split()[1])
        health["memory_mb"] = round(pages * os.sysconf("SC_PAGE_SIZE") / 1e6)
    except (OSError, ValueError, AttributeError):
        pass
    return health


def needs_attention(status: dict) -> list[str]:
    """What's wrong right now. A device being away is normal, but a sensor that can't tell is not."""
    issues = []
    phone, watch, server = status["phone"], status["watch"], status["server"]
    if not phone["connected"]:
        issues.append("The phone app isn't connected.")
    if phone["location"].get("error"):
        issues.append(f"Phone location: {phone['location']['error']}")
    if watch.get("error"):
        issues.append(f"Watch: {watch['error']}")
    if watch.get("linked") and not watch.get("connected"):
        issues.append("The watch is linked but not connected.")
    battery = watch.get("battery_percent")
    if isinstance(battery, int) and battery < LOW_WATCH_BATTERY:
        issues.append(f"The watch battery is at {battery}%.")
    for sensor, devices in status["sensors"].items():
        if devices.get("state") == "unknown":
            issues.append(f"Sensor {sensor}: {devices.get('reason')}")
            continue
        reasons = {device.get("reason") for device in devices.values() if device.get("state") == "unknown"}
        issues += [f"Sensor {sensor}: {reason}" for reason in sorted(filter(None, reasons))]
    if server["disk_free_percent"] < LOW_DISK_PERCENT:
        issues.append(f"Only {server['disk_free_percent']}% of the disk is free.")
    if server.get("discord") is False:
        issues.append("The Discord bot isn't connected.")
    return issues
