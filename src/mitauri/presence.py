"""Whether the user's devices are near each Bluetooth sensor. Each device broadcasts a token that changes every
5 minutes, so only this server can tell it's yours. Sightings stay in memory and are never saved, so no location history
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

DEVICES = ("phone", "laptop")
DEFAULT_SENSOR = "home"
COMPANY_ID = 0xFFFF
MAGIC = b"MT"
BEACON_MAGIC = b"MS"
BEACON_PAYLOAD_BYTES = 14  # magic, 4-byte counter, 8-byte signature
BEACON_COUNTER_STALE_SECONDS = 180  # a sensor moves its counter on every minute, so older is a replay
REPORTER_SILENT_AFTER_SECONDS = 300
MAX_REPORT_SIGHTINGS = 16

log = logging.getLogger(__name__)


def load_key(data_dir: Path) -> bytes:
    path = data_dir / "presence_key"
    if not path.exists():
        path.touch(mode=0o600)
        path.write_text(secrets.token_hex(32))
    return bytes.fromhex(path.read_text())


def token_for(key: bytes, window: int) -> str:
    return hmac.new(key, struct.pack(">q", window), hashlib.sha256).digest()[:TOKEN_BYTES].hex()


def device_key(key: bytes, device: str) -> bytes:
    """The phone holds the main key. Others get their own, so a lost device can't pretend to be the phone."""
    if device == "phone":
        return key
    return hmac.new(key, b"presence-device:" + device.encode(), hashlib.sha256).digest()


def beacon_payload(key: bytes, window: int) -> bytes:
    """What a device broadcasts after the company ID, which is the format the sensor looks for."""
    return MAGIC + bytes.fromhex(token_for(key, window))


def parse_sensors(text: str) -> dict[str, str]:
    """Reads "desk=/dev/serial/by-id/x,home=/dev/ttyUSB0" into names and ports."""
    pairs = (item.split("=", 1) for item in text.split(",") if "=" in item)
    return {name.strip(): port.strip() for name, port in pairs if name.strip() and port.strip()}


def beacon_key(key: bytes, name: str) -> bytes:
    """The key held by a sensor that broadcasts, so a stolen sensor can only fake itself."""
    return device_key(key, "beacon:" + name)


def beacon_signature(key: bytes, counter: int) -> bytes:
    return hmac.new(key, struct.pack(">I", counter), hashlib.sha256).digest()[:TOKEN_BYTES]


def parse_beacons(text: str) -> list[str]:
    return [name.strip() for name in text.split(",") if name.strip()]


def distance_metres(rssi: float) -> float:
    return 10 ** ((RSSI_AT_ONE_METRE - rssi) / (10 * PATH_LOSS))


class Presence:
    def __init__(self, key: bytes, beacons: tuple[str, ...] | list[str] = ()):
        self._keys = {device: device_key(key, device) for device in DEVICES}
        self._beacon_keys = {name: beacon_key(key, name) for name in beacons}
        self._beacon_counters: dict[str, tuple[int, float]] = {}  # newest counter, and when it first appeared
        self._reporters: dict[str, tuple[float, bool]] = {}  # when each listener last reported, and if it can hear
        self._heartbeats: dict[str, float] = {}
        self._sightings: dict[tuple[str, str], tuple[float, float]] = {}  # rssi and when, by sensor and device

    def _device_for(self, token: str, now: float) -> str | None:
        window = math.floor(now / TOKEN_WINDOW_SECONDS)
        found = None
        for device, key in self._keys.items():
            for offset in range(-ALLOWED_WINDOWS, 2):
                if hmac.compare_digest(token_for(key, window + offset), token):
                    found = device
        return found

    def handle_line(self, line: str, now: float | None = None, sensor: str = DEFAULT_SENSOR) -> None:
        """Takes one line from a sensor, which isn't trusted to be well formed."""
        now = time.time() if now is None else now
        try:
            event = json.loads(line)
            if event.get("hb"):
                self._heartbeats[sensor] = now
                return
            token, rssi = str(event["t"]), int(event["r"])
        except (ValueError, KeyError, TypeError, AttributeError):
            return
        self._heartbeats[sensor] = now
        device = self._device_for(token, now)
        if rssi >= MAX_VALID_RSSI or device is None:
            return
        self._record(sensor, device, rssi, now)

    def _beacon_for(self, payload: bytes, now: float) -> str | None:
        if len(payload) != BEACON_PAYLOAD_BYTES or payload[:2] != BEACON_MAGIC:
            return None
        counter = struct.unpack(">I", payload[2:6])[0]
        for name, key in self._beacon_keys.items():
            if not hmac.compare_digest(beacon_signature(key, counter), payload[6:]):
                continue
            newest, since = self._beacon_counters.get(name, (-1, now))
            if counter > newest:
                self._beacon_counters[name] = (counter, now)
                return name
            return name if counter == newest and now - since <= BEACON_COUNTER_STALE_SECONDS else None
        return None

    def handle_report(self, listener: str, report: object, now: float | None = None) -> None:
        """Takes a listener's report of the sensors it hears. Its contents aren't trusted to be well formed."""
        now = time.time() if now is None else now
        if not isinstance(report, dict):
            return
        self._reporters[listener] = (now, report.get("listening") is True)
        sightings = report.get("sightings")
        if not isinstance(sightings, list):
            return
        for sighting in sightings[:MAX_REPORT_SIGHTINGS]:
            try:
                payload, rssi = bytes.fromhex(str(sighting["p"])), int(sighting["r"])
            except (ValueError, KeyError, TypeError):
                continue
            name = self._beacon_for(payload, now)
            if name is None or rssi >= MAX_VALID_RSSI:
                continue
            self._record(name, listener, rssi, now)

    def _record(self, sensor: str, device: str, rssi: float, now: float) -> None:
        previous = self._sightings.get((sensor, device))
        fresh = previous is None or now - previous[1] > AWAY_AFTER_SECONDS
        self._sightings[sensor, device] = (rssi if fresh else SMOOTHING * rssi + (1 - SMOOTHING) * previous[0], now)

    def _beacon_status(self, name: str, listener: str, now: float) -> dict:  # listener: who reports hearing it
        reported, listening = self._reporters.get(listener, (0.0, False))
        if now - reported > REPORTER_SILENT_AFTER_SECONDS:
            return {"state": "unknown", "reason": f"The {listener} hasn't reported lately."}
        if not listening:
            return {"state": "unknown", "reason": f"The {listener} can't listen: Bluetooth is off or not allowed."}
        return self._device_status(name, listener, now)

    def _device_status(self, sensor: str, device: str, now: float) -> dict:
        sighting = self._sightings.get((sensor, device))
        if sighting is None or now - sighting[1] > AWAY_AFTER_SECONDS:
            return {"state": "away", "note": "Not heard recently. Its Bluetooth may simply be off."}
        metres = distance_metres(sighting[0])
        state = "near" if metres < NEAR_METRES else "in_room" if metres < ROOM_METRES else "nearby"
        return {"state": state, "metres_rough": round(metres, 1), "seen_seconds_ago": round(now - sighting[1])}

    def status(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        sensors = {}
        for sensor, heartbeat in self._heartbeats.items():
            if now - heartbeat > SENSOR_SILENT_AFTER_SECONDS:
                sensors[sensor] = {"state": "unknown", "reason": "This sensor isn't reporting."}
            else:
                sensors[sensor] = {device: self._device_status(sensor, device, now) for device in DEVICES}
        for name in self._beacon_keys:
            sensors[name] = {who: self._beacon_status(name, who, now) for who in {"phone", *self._reporters}}
        return {"sensors": sensors}

    def reporters(self, now: float | None = None) -> dict[str, dict]:
        now = time.time() if now is None else now
        return {
            name: {"seconds_ago": round(now - reported), "listening": listening}
            for name, (reported, listening) in self._reporters.items()
        }

    def watch_serial(self, port: str, sensor: str = DEFAULT_SENSOR) -> None:
        self._heartbeats.setdefault(sensor, 0.0)
        threading.Thread(target=self._read_forever, args=(port, sensor), daemon=True).start()

    def _read_forever(self, port: str, sensor: str) -> None:
        import serial
        while True:
            connection = serial.Serial()
            try:
                connection.port = port
                connection.baudrate = BAUD
                connection.timeout = 2
                # Opening a port normally pulses these lines, which would keep resetting the board.
                connection.dtr = False
                connection.rts = False
                connection.open()
                log.info("Presence sensor %s connected", sensor)
                while True:
                    self.handle_line(connection.readline().decode(errors="ignore"), sensor=sensor)
            except OSError:
                connection.close()
                time.sleep(RECONNECT_SECONDS)
