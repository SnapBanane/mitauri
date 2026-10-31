"""Who may talk to Mitauri. The phone proves itself with a key that can't leave its secure hardware,
so nothing on the wire or on disk can be replayed or copied to get in."""

import base64
import hashlib
import json
import secrets
import time
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

MAX_CLOCK_SKEW_SECONDS = 120
PAIR_CODE_LIFETIME_SECONDS = 600
PAIR_CODE_ATTEMPTS = 5
MAX_FAILURES = 10
FAILURE_WINDOW_SECONDS = 300
LOCKOUT_SECONDS = 900
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no look-alike characters


def device_id(public_key: bytes) -> str:
    return hashlib.sha256(public_key).hexdigest()[:16]


def signed_message(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    return "\n".join([method, path, timestamp, nonce, hashlib.sha256(body).hexdigest()]).encode()


class Devices:
    def __init__(self, data_dir: Path):
        self._devices_file = data_dir / "devices.json"
        self._code_file = data_dir / "pair_code.json"
        self._seen_nonces: dict[str, float] = {}

    def all(self) -> dict[str, dict]:
        return json.loads(self._devices_file.read_text()) if self._devices_file.exists() else {}

    def _save(self, devices: dict) -> None:
        self._devices_file.write_text(json.dumps(devices))

    def remove(self, device: str) -> bool:
        devices = self.all()
        removed = devices.pop(device, None) is not None
        self._save(devices)
        return removed

    def new_code(self) -> str:
        """Creates a one-time code. Only its hash is stored, and a new code replaces any old one."""
        raw = "".join(secrets.choice(CODE_ALPHABET) for _ in range(10))
        self._code_file.write_text(json.dumps({
            "hash": hashlib.sha256(raw.encode()).hexdigest(),
            "expires": time.time() + PAIR_CODE_LIFETIME_SECONDS,
            "attempts": 0,
        }))
        return f"{raw[:5]}-{raw[5:]}"

    def pair(self, code: str, public_key_b64: str, name: str) -> str | None:
        """Returns the new device's id, or None if the code is wrong, used up or expired."""
        if not self._code_file.exists():
            return None
        state = json.loads(self._code_file.read_text())
        normalized = code.replace("-", "").replace(" ", "").upper()
        given = hashlib.sha256(normalized.encode()).hexdigest()
        if time.time() > state["expires"]:
            self._code_file.unlink()
            return None
        if not secrets.compare_digest(given, state["hash"]):
            state["attempts"] += 1
            if state["attempts"] >= PAIR_CODE_ATTEMPTS:
                self._code_file.unlink()
            else:
                self._code_file.write_text(json.dumps(state))
            return None
        public_key = base64.b64decode(public_key_b64, validate=True)
        if not isinstance(load_der_public_key(public_key), ec.EllipticCurvePublicKey):
            raise ValueError("not an EC key")
        self._code_file.unlink()
        device = device_id(public_key)
        devices = self.all()
        devices[device] = {"key": public_key_b64, "name": name[:60], "added": int(time.time())}
        self._save(devices)
        return device

    def verify(self, header: str, method: str, path: str, body: bytes) -> str | None:
        """Checks a request's signature and returns its device id, or None if it isn't valid."""
        try:
            device, timestamp, nonce, signature = header.split(".")
            now = time.time()
            if abs(now - int(timestamp)) > MAX_CLOCK_SKEW_SECONDS:
                return None
            entry = self.all().get(device)
            if entry is None or nonce in self._seen_nonces:
                return None
            key = load_der_public_key(base64.b64decode(entry["key"]))
            key.verify(
                base64.urlsafe_b64decode(signature),
                signed_message(method, path, timestamp, nonce, body),
                ec.ECDSA(hashes.SHA256()),
            )
        except (ValueError, TypeError, InvalidSignature):
            return None
        self._seen_nonces = {n: t for n, t in self._seen_nonces.items() if t > now}
        self._seen_nonces[nonce] = now + 2 * MAX_CLOCK_SKEW_SECONDS
        return device


class FailureLimiter:
    """Locks out an address that keeps failing to authenticate."""

    def __init__(self):
        self._failures: dict[str, list[float]] = {}
        self._locked_until: dict[str, float] = {}

    def is_locked(self, address: str) -> bool:
        return self._locked_until.get(address, 0) > time.time()

    def record_failure(self, address: str) -> None:
        now = time.time()
        recent = [t for t in self._failures.get(address, []) if t > now - FAILURE_WINDOW_SECONDS] + [now]
        self._failures[address] = recent
        if len(recent) >= MAX_FAILURES:
            self._locked_until[address] = now + LOCKOUT_SECONDS
            self._failures.pop(address)
