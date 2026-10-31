"""Waking the user for school. Google has no public way to set an alarm on a Nest, so at the right time this
plays spoken audio on it over the local network. It depends on this machine being on and on the same Wi-Fi."""

import functools
import http.server
import json
import secrets
import socket
import threading
import time
from datetime import date, datetime, timedelta
from pathlib import Path

SETTINGS_FILE = "wake.json"
FIRED_FILE = "wake_fired.txt"
DEFAULTS = {"enabled": False, "minutes_before": 90, "device": "Schlafzimmer", "volume": 0.5, "phone_backup": True}
FIRE_WINDOW_MINUTES = 15
MIN_MINUTES_BEFORE = 15
MAX_MINUTES_BEFORE = 240
REPEATS = 3
GAP_SECONDS = 20
CAST_TIMEOUT_SECONDS = 15
AUDIO_PORT = 8767  # fixed, so a firewall can allow just this


def load_settings(data_dir: Path) -> dict:
    path = data_dir / SETTINGS_FILE
    return {**DEFAULTS, **(json.loads(path.read_text()) if path.exists() else {})}


def save_settings(data_dir: Path, **changes) -> dict:
    settings = {**load_settings(data_dir), **{k: v for k, v in changes.items() if v is not None}}
    settings["minutes_before"] = max(MIN_MINUTES_BEFORE, min(int(settings["minutes_before"]), MAX_MINUTES_BEFORE))
    settings["volume"] = max(0.1, min(float(settings["volume"]), 1.0))
    (data_dir / SETTINGS_FILE).write_text(json.dumps(settings))
    return settings


def already_fired(data_dir: Path, day: date) -> bool:
    path = data_dir / FIRED_FILE
    return path.exists() and path.read_text() == day.isoformat()


def mark_fired(data_dir: Path, day: date) -> None:
    (data_dir / FIRED_FILE).write_text(day.isoformat())


def next_wake(firsts: dict[date, int], now: datetime, minutes_before: int, fired: date | None = None) -> dict | None:
    """The next wake-up still to come, or one just missed by less than the fire window."""
    for day in sorted(firsts):
        if day < now.date() or day == fired:
            continue
        start = firsts[day]
        lesson = datetime(day.year, day.month, day.day, start // 100, start % 100, tzinfo=now.tzinfo)
        wake = lesson - timedelta(minutes=minutes_before)
        if wake >= now - timedelta(minutes=FIRE_WINDOW_MINUTES):
            return {"day": day, "lesson": lesson, "wake": wake}
    return None


def alarm_text(lesson: datetime) -> str:
    return f"Guten Morgen! Zeit aufzustehen. Deine erste Stunde beginnt heute um {lesson.hour} Uhr {lesson.minute:02d}."


def _own_address(target: str) -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect((target, 8009))
        return probe.getsockname()[0]


class _OneFile(http.server.BaseHTTPRequestHandler):
    def __init__(self, path: str, audio: bytes, *args, **kwargs):
        self._path, self._audio = path, audio
        super().__init__(*args, **kwargs)

    def do_GET(self):
        if self.path != self._path:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(self._audio)))
        self.end_headers()
        self.wfile.write(self._audio)

    def log_message(self, *_):
        pass


def play(device: str, audio: bytes, seconds: float, volume: float, repeats: int = REPEATS) -> None:
    """Serves the audio at a secret address only while it plays. Raises if the speaker can't be reached."""
    import pychromecast

    casts, browser = pychromecast.get_listed_chromecasts(friendly_names=[device], timeout=CAST_TIMEOUT_SECONDS)
    server = None
    try:
        if not casts:
            raise OSError(f"Speaker {device} wasn't found.")
        cast = casts[0]
        cast.wait(timeout=CAST_TIMEOUT_SECONDS)
        host = cast.cast_info.host
        address = _own_address(host)
        path = "/" + secrets.token_urlsafe(24) + ".wav"
        server = http.server.ThreadingHTTPServer((address, AUDIO_PORT), functools.partial(_OneFile, path, audio))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        cast.set_volume(volume)
        for attempt in range(repeats):
            if attempt:
                time.sleep(GAP_SECONDS)
            cast.media_controller.play_media(f"http://{address}:{server.server_address[1]}{path}", "audio/wav")
            cast.media_controller.block_until_active(CAST_TIMEOUT_SECONDS)
            time.sleep(seconds + 1)
    finally:
        if server:
            server.shutdown()
            server.server_close()
        browser.stop_discovery()


def wav_seconds(audio: bytes) -> float:
    import io
    import wave
    with wave.open(io.BytesIO(audio)) as file:
        return file.getnframes() / file.getframerate()
