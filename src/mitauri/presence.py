"""Whether the phone is near a Bluetooth sensor. The phone broadcasts a token that changes every 5 minutes,
so only this server can tell it's you. Sightings stay in memory and are never saved, so no location history
builds up. Bluetooth distance is rough: walls, pockets and bodies change it by metres."""

import hashlib
import hmac
import json
import logging
import math
import secrets
import struct
import threading
import time
from pathlib import Path

TOKEN_WINDOW_SECONDS = 300
TOKEN_BYTES = 8
ALLOWED_WINDOWS = 3  # the phone's timer can run late while it sleeps
AWAY_AFTER_SECONDS = 120
SENSOR_SILENT_AFTER_SECONDS = 30
MAX_VALID_RSSI = 0
SMOOTHING = 0.3
RSSI_AT_ONE_METRE = -59
PATH_LOSS = 2.5
NEAR_METRES = 2
ROOM_METRES = 6
BAUD = 115200
RECONNECT_SECONDS = 5

log = logging.getLogger(__name__)


def load_key(data_dir: Path) -> bytes:
    path = data_dir / "presence_key"
    if not path.exists():
        path.touch(mode=0o600)
        path.write_text(secrets.token_hex(32))
    return bytes.fromhex(path.read_text())


def token_for(key: bytes, window: int) -> str:
    return hmac.new(key, struct.pack(">q", window), hashlib.sha256).digest()[:TOKEN_BYTES].hex()


def distance_metres(rssi: float) -> float:
    return 10 ** ((RSSI_AT_ONE_METRE - rssi) / (10 * PATH_LOSS))


class Presence:
    def __init__(self, key: bytes):
        self._key = key
        self._rssi: float | None = None
        self._last_seen = 0.0
        self._last_heartbeat = 0.0

    def _valid(self, token: str, now: float) -> bool:
        window = math.floor(now / TOKEN_WINDOW_SECONDS)
        return any(
            hmac.compare_digest(token_for(self._key, window + offset), token)
            for offset in range(-ALLOWED_WINDOWS, 2)
        )

    def handle_line(self, line: str, now: float | None = None) -> None:
        """Takes one line from the sensor, which isn't trusted to be well formed."""
        now = time.time() if now is None else now
        try:
            event = json.loads(line)
            if event.get("hb"):
                self._last_heartbeat = now
                return
            token, rssi = str(event["t"]), int(event["r"])
        except (ValueError, KeyError, TypeError, AttributeError):
            return
        self._last_heartbeat = now
        if rssi >= MAX_VALID_RSSI or not self._valid(token, now):
            return
        fresh = now - self._last_seen > AWAY_AFTER_SECONDS or self._rssi is None
        self._rssi = rssi if fresh else SMOOTHING * rssi + (1 - SMOOTHING) * self._rssi
        self._last_seen = now

    def status(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        if now - self._last_heartbeat > SENSOR_SILENT_AFTER_SECONDS:
            return {"state": "unknown", "reason": "The presence sensor isn't reporting."}
        if self._rssi is None or now - self._last_seen > AWAY_AFTER_SECONDS:
            return {"state": "away", "note": "Not heard recently. Bluetooth may simply be off."}
        metres = distance_metres(self._rssi)
        state = "near" if metres < NEAR_METRES else "in_room" if metres < ROOM_METRES else "nearby"
        return {"state": state, "metres_rough": round(metres, 1), "seen_seconds_ago": round(now - self._last_seen)}

    def watch_serial(self, port: str) -> None:
        threading.Thread(target=self._read_forever, args=(port,), daemon=True).start()

    def _read_forever(self, port: str) -> None:
        import serial
        while True:
            try:
                connection = serial.Serial()
                connection.port = port
                connection.baudrate = BAUD
                connection.timeout = 2
                # Opening a port normally pulses these lines, which would keep resetting the board.
                connection.dtr = False
                connection.rts = False
                connection.open()
                log.info("Presence sensor connected")
                while True:
                    self.handle_line(connection.readline().decode(errors="ignore"))
            except OSError:
                time.sleep(RECONNECT_SECONDS)
