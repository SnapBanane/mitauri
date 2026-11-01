"""A small background service for a computer that isn't the server: it listens for sensor beacons and reports what it
hears over Tailscale. It has its own key, and the server only lets that key send these reports, so this computer can't
reach chats, memories or anything else through it. It knows nothing about the sensors' keys: the server checks them."""

import argparse
import asyncio
import base64
import ipaddress
import json
import logging
import os
import secrets
import time
from pathlib import Path
from urllib.error import URLError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .auth import signed_message
from .config import API_PORT
from .presence import BEACON_MAGIC, BEACON_PAYLOAD_BYTES, COMPANY_ID, MAX_REPORT_SIGHTINGS

REPORT_SECONDS = 60
REPORT_PATH = "/presence-report"
REQUEST_TIMEOUT_SECONDS = 15
TAILNET = ipaddress.ip_network("100.64.0.0/10")

log = logging.getLogger(__name__)


def folder() -> Path:
    return Path(os.environ.get("MITAURI_LISTENER_DIR", "~/.local/share/mitauri-listener")).expanduser()


def is_on_tailnet(server: str) -> bool:
    """Reports travel unencrypted apart from Tailscale, so nothing else is accepted."""
    if server.endswith(".ts.net"):
        return True
    try:
        return ipaddress.ip_address(server) in TAILNET
    except ValueError:
        return False


def auth_header(key: ec.EllipticCurvePrivateKey, device: str, method: str, path: str, body: bytes) -> str:
    timestamp, nonce = str(int(time.time())), secrets.token_hex(8)
    signature = key.sign(signed_message(method, path, timestamp, nonce, body), ec.ECDSA(hashes.SHA256()))
    return f"{device}.{timestamp}.{nonce}.{base64.urlsafe_b64encode(signature).decode()}"


def build_report(listening: bool, heard: dict[str, int]) -> bytes:
    sightings = [{"p": payload, "r": rssi} for payload, rssi in heard.items()]
    return json.dumps({"listening": listening, "sightings": sightings}).encode()


def note_sighting(heard: dict[str, int], data: bytes | None, rssi: int) -> None:
    if data and len(data) == BEACON_PAYLOAD_BYTES and data[:2] == BEACON_MAGIC and len(heard) < MAX_REPORT_SIGHTINGS:
        heard[data.hex()] = rssi


def setup(server: str, code: str, directory: Path) -> str:
    if not is_on_tailnet(server):
        raise SystemExit("Use the server's Tailscale address, as reports aren't encrypted without it.")
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    body = json.dumps({"code": code, "public_key": base64.b64encode(public).decode(), "name": "listener"}).encode()
    try:
        with urlopen(Request(f"http://{server}:{API_PORT}/pair", data=body), timeout=REQUEST_TIMEOUT_SECONDS) as response:
            device = json.load(response)["device"]
    except (URLError, ValueError, KeyError) as error:
        raise SystemExit(f"Pairing failed: {error}. Codes work once and last 10 minutes.")
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(directory / "key.pem", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as file:
        file.write(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        ))
    (directory / "config.json").write_text(json.dumps({"server": server, "device": device}))
    return device


def send_report(key, device: str, server: str, body: bytes) -> None:
    request = Request(
        f"http://{server}:{API_PORT}{REPORT_PATH}", data=body,
        headers={"X-Mitauri-Auth": auth_header(key, device, "POST", REPORT_PATH, body)},
    )
    with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS):
        pass


async def listen_once(heard: dict[str, int]) -> bool:
    """Scans for one report's length. A fresh scan each time also recovers from sleep and Bluetooth restarts."""
    from bleak import BleakScanner

    def on_advert(_, advert) -> None:
        note_sighting(heard, advert.manufacturer_data.get(COMPANY_ID), advert.rssi)

    try:
        async with BleakScanner(on_advert):
            await asyncio.sleep(REPORT_SECONDS)
        return True
    except Exception as error:  # Bluetooth being off is expected, and the server should hear about it
        log.warning("Can't listen: %s", error)
        await asyncio.sleep(REPORT_SECONDS)
        return False


async def run(directory: Path) -> None:
    try:
        config = json.loads((directory / "config.json").read_text())
        key = serialization.load_pem_private_key((directory / "key.pem").read_bytes(), password=None)
    except OSError:
        raise SystemExit("Not set up yet: run mitauri-listener setup SERVER CODE")
    if not is_on_tailnet(config["server"]):
        raise SystemExit("The saved server isn't a Tailscale address.")
    log.info("Reporting to %s", config["server"])
    last_error = None
    while True:
        heard: dict[str, int] = {}
        listening = await listen_once(heard)
        try:
            await asyncio.to_thread(send_report, key, config["device"], config["server"], build_report(listening, heard))
            last_error = None
        except OSError as error:  # the server being away is normal, so say so once
            if str(error) != last_error:
                log.warning("Can't reach the server, will keep trying: %s", error)
                last_error = str(error)


def main() -> None:
    parser = argparse.ArgumentParser(prog="mitauri-listener")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="listen and report, for the background service")
    set_up = commands.add_parser("setup", help="connect to the server with a code from mitauri pair-listener")
    set_up.add_argument("server", help="the server's Tailscale address")
    set_up.add_argument("code")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("bleak").setLevel(logging.WARNING)
    if args.command == "setup":
        print(f"Connected as {setup(args.server, args.code, folder())}.")
        return
    asyncio.run(run(folder()))
