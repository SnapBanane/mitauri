"""Waking the user for school. Google has no public way to set an alarm on a Nest, so at the right time this
plays spoken audio on it over the local network. It depends on this machine being on and on the same Wi-Fi."""

import functools
import http.server
import io
import json
import logging
import secrets
import socket
import threading
import time
import wave
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np

log = logging.getLogger(__name__)

SETTINGS_FILE = "wake.json"
FIRED_FILE = "wake_fired.txt"
DEFAULTS = {"enabled": False, "minutes_before": 90, "device": "Schlafzimmer", "volume": 0.5, "phone_backup": True}
FIRE_WINDOW_MINUTES = 15
MIN_MINUTES_BEFORE = 15
MAX_MINUTES_BEFORE = 240
GAP_SECONDS = 3
MAX_ALARM_SECONDS = 300
POLL_SECONDS = 0.5
CAST_SETTLE_SECONDS = 1
STOP_FILE = "wake_stop"
RINGING_FILE = "wake_ringing"
LEAD_IN_SECONDS = 2.0
MELODY = (392.0, 523.25, 659.25, 783.99)  # G, C, E, G
MELODY_ROUNDS = 2
NOTE_SPACING_SECONDS = 0.5
NOTE_RING_SECONDS = 1.8
NOTE_ATTACK_SECONDS = 0.012
NOTE_DECAY = 2.6
CHIME_LOUDNESS = 0.45
CAST_TIMEOUT_SECONDS = 15
FIND_ATTEMPTS = 4
FIND_RETRY_SECONDS = 5
SPEAKER_FILE = "wake_speaker.txt"
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


def request_stop(data_dir: Path) -> None:
    (data_dir / STOP_FILE).touch()


def is_ringing(data_dir: Path) -> bool:
    path = data_dir / RINGING_FILE
    return path.exists() and time.time() - path.stat().st_mtime < MAX_ALARM_SECONDS + 30


def with_tone(speech: bytes) -> bytes:
    """Silence for the speaker to wake up, then an alarm tone, then the words. Same format as the voice."""
    with wave.open(io.BytesIO(speech)) as file:
        rate, width, channels = file.getframerate(), file.getsampwidth(), file.getnchannels()
        words = file.readframes(file.getnframes())
    parts = [
        _silence(LEAD_IN_SECONDS, rate, channels), _chime(rate, channels),
        np.frombuffer(words, dtype=np.int16).tobytes(),
    ]
    output = io.BytesIO()
    with wave.open(output, "wb") as file:
        file.setnchannels(channels)
        file.setsampwidth(width)
        file.setframerate(rate)
        file.writeframes(b"".join(parts))
    return output.getvalue()


def _silence(seconds: float, rate: int, channels: int) -> bytes:
    return bytes(2 * channels * int(seconds * rate))


def _chime(rate: int, channels: int) -> bytes:
    """A soft rising melody, like a marimba: each note starts gently and rings out."""
    length = NOTE_SPACING_SECONDS * len(MELODY) * MELODY_ROUNDS + NOTE_RING_SECONDS
    mix = np.zeros(int(length * rate))
    times = np.arange(int(NOTE_RING_SECONDS * rate)) / rate
    envelope = np.minimum(times / NOTE_ATTACK_SECONDS, 1) * np.exp(-times * NOTE_DECAY)
    for index in range(len(MELODY) * MELODY_ROUNDS):
        pitch = MELODY[index % len(MELODY)]
        # Mostly the pure note, with a little of its octave for warmth.
        note = (np.sin(2 * np.pi * pitch * times) + 0.25 * np.sin(4 * np.pi * pitch * times)) * envelope
        start = int(index * NOTE_SPACING_SECONDS * rate)
        mix[start:start + len(note)] += note
    samples = (mix / np.abs(mix).max() * CHIME_LOUDNESS * 32767).astype(np.int16)
    return np.repeat(samples, channels).tobytes()


def sound_until_stopped(player, seconds: float, should_stop, deadline: float, repeats: int | None = None) -> bool:
    """Plays over and over. Returns True if something stopped it, like the app or "Hey Google, stopp"."""
    played = 0
    while time.monotonic() < deadline and (repeats is None or played < repeats):
        if should_stop():
            return True
        played += 1
        player.start()
        ends = time.monotonic() + seconds + 1
        while time.monotonic() < ends:
            if should_stop() or player.stopped_by_user():
                return True
            time.sleep(POLL_SECONDS)
        if repeats is None:
            pause = time.monotonic() + GAP_SECONDS
            while time.monotonic() < pause:
                if should_stop():
                    return True
                time.sleep(POLL_SECONDS)
    return False


class _CastPlayer:
    def __init__(self, cast, url: str):
        self._cast, self._url = cast, url

    def start(self) -> None:
        controller = self._cast.media_controller
        controller.play_media(self._url, "audio/wav")
        controller.block_until_active(CAST_TIMEOUT_SECONDS)
        time.sleep(CAST_SETTLE_SECONDS)

    def stopped_by_user(self) -> bool:
        status = self._cast.media_controller.status
        return self._cast.is_idle or (status.player_state == "IDLE" and status.idle_reason in ("CANCELLED", "INTERRUPTED"))


def find_speaker(device: str, data_dir: Path, attempts: int = FIND_ATTEMPTS):
    """Network discovery misses sometimes, so it tries several times, and goes straight to the address that
    worked last time. Returns the speaker and a browser to stop. Raises OSError if it never turns up."""
    import pychromecast

    known_file = data_dir / SPEAKER_FILE
    for attempt in range(attempts):
        if attempt:
            time.sleep(FIND_RETRY_SECONDS)
        known = [known_file.read_text()] if known_file.exists() else None
        casts, browser = pychromecast.get_listed_chromecasts(
            friendly_names=[device], timeout=CAST_TIMEOUT_SECONDS, known_hosts=known,
        )
        if casts:
            known_file.write_text(casts[0].cast_info.host)
            return casts[0], browser
        browser.stop_discovery()
        log.warning("Speaker %s not found on attempt %s", device, attempt + 1)
    raise OSError(f"Speaker {device} wasn't found.")


def play(device: str, audio: bytes, seconds: float, volume: float, data_dir: Path, repeats: int | None = None) -> bool:
    """Serves the audio at a secret address only while it plays, and fully lets go of the speaker afterwards.
    Returns whether it was stopped. Raises if the speaker can't be reached."""
    stop_file, ringing = data_dir / STOP_FILE, data_dir / RINGING_FILE
    stop_file.unlink(missing_ok=True)
    cast, browser = find_speaker(device, data_dir)
    server = None
    try:
        cast.wait(timeout=CAST_TIMEOUT_SECONDS)
        address = _own_address(cast.cast_info.host)
        path = "/" + secrets.token_urlsafe(24) + ".wav"
        server = http.server.ThreadingHTTPServer((address, AUDIO_PORT), functools.partial(_OneFile, path, audio))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        cast.set_volume(volume)
        ringing.touch()
        return sound_until_stopped(
            _CastPlayer(cast, f"http://{address}:{AUDIO_PORT}{path}"), seconds, stop_file.exists,
            time.monotonic() + MAX_ALARM_SECONDS, repeats,
        )
    finally:
        ringing.unlink(missing_ok=True)
        stop_file.unlink(missing_ok=True)
        try:
            cast.media_controller.stop()
            cast.quit_app()
        except Exception:  # letting go is best effort, and the speaker may be gone
            pass
        if server:
            server.shutdown()
            server.server_close()
        browser.stop_discovery()


def wav_seconds(audio: bytes) -> float:
    with wave.open(io.BytesIO(audio)) as file:
        return file.getnframes() / file.getframerate()
