"""mitauri.watch — async BLE client for the user's VeryFitPro / iDO-SDK watch (ACT307).

Reverse-engineered from VeryFitPro com.veryfit2hr.second v3.7.2 (device "pissarropro")
via static decompilation + a btsnoop capture. Target: integration into the "mitauri"
personal assistant.

TRANSPORT (fully confirmed from capture):
  GATT service 0xAF0 (00000af0-0000-1000-8000-00805f9b34fb)
    0xAF6 write   (phone -> watch)  command channel
    0xAF7 notify  (watch -> phone)  command responses / acks
    0xAF1 write   (phone -> watch)  bulk/file channel (watch faces)  [not used here yet]
    0xAF2 notify  (watch -> phone)  bulk data (health sync)          [not used here yet]
  No encryption, no bind-auth handshake. Just connect, enable notifications, send TLV.

FRAME SHAPE:  byte0 = group (0x02 = query/GET, 0x03 = set/SET), byte1 = command id,
              then payload. Every 0x03xx set is acked by the watch with a 2-byte 0x03xx.

CONFIDENCE LEVELS are marked per command: [CONFIRMED] = exact bytes seen on the wire;
[INFERRED] = reconstructed from model + one sample; [UNKNOWN] = not yet recovered.

This is the laptop's client and the reference for the protocol. The phone talks to the watch through its own
port in android/.../WatchProtocol.kt, and both are tested against the same captured frames. The watch accepts
one central at a time, so while the phone holds it, this client won't find it.
"""
from __future__ import annotations
import asyncio
import collections
import logging
import struct
import unicodedata
import datetime as _dt
from dataclasses import dataclass
from typing import Optional, Callable

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.exc import BleakError


log = logging.getLogger(__name__)


def _u(x: int) -> str:
    return f"0000{x:04x}-0000-1000-8000-00805f9b34fb"

SVC_CMD   = _u(0x0AF0)
CHAR_CMD_WRITE  = _u(0x0AF6)   # write commands here
CHAR_CMD_NOTIFY = _u(0x0AF7)   # responses/acks arrive here
CHAR_BULK_WRITE = _u(0x0AF1)
CHAR_BULK_NOTIFY= _u(0x0AF2)

GROUP_GET = 0x02
GROUP_SET = 0x03
MAX_FRAME_BYTES = 20  # one ATT write at the default MTU
BRIGHTNESS_LEVELS = (20, 40, 60, 80, 100)  # the only levels seen on the wire
BIDI_CONTROLS = set("\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069")


@dataclass
class Response:
    group: int
    cmd: int
    payload: bytes
    raw: bytes


class VeryFitWatch:
    """
    Minimal async client. Example:

        async def main():
            watch = await VeryFitWatch.scan(name_contains="...")   # or address="AA:BB:..."
            async with watch:
                await watch.set_time()
                print(await watch.get_status())
    """

    # SECURITY NOTE: this watch has NO pairing, authentication, or link encryption. Any BLE
    # device in range can connect and control it, and notification text travels in cleartext.
    # The client cannot change that; callers should treat the watch display as spoofable and
    # avoid sending secrets in notifications.

    def __init__(self, address, *, on_bulk: Optional[Callable[[bytes], None]] = None, client=None):
        self.address = getattr(address, "address", address)
        self._client = client or BleakClient(address, disconnected_callback=self._on_disconnected)
        self._on_bulk = on_bulk
        # opcode (group<<8 | cmd) -> future, for request/ack correlation
        self._waiters: dict[int, asyncio.Future] = {}
        self._notify_index: Optional[int] = None  # the packet whose ack we're waiting for
        self._log: collections.deque = collections.deque(maxlen=256)  # bounded; no leak
        self._lock = asyncio.Lock()  # serialize commands so ack-futures never collide

    # ---- discovery -------------------------------------------------------
    @classmethod
    async def scan(cls, *, name_contains: Optional[str] = None,
                   address: Optional[str] = None, timeout: float = 8.0) -> "VeryFitWatch":
        """
        Find the watch by its advertised 0xAF0 service, listening for the whole window. The watch can't prove who
        it is, and anything nearby can advertise the same service, so a stranger must never win by being louder:
        with more than one candidate this refuses to guess. Once you know the address, pass it. It's a static
        random address on this watch (seen unchanged across captures and scans), so it's safe to keep.
        """
        if address:
            return cls(address)
        found: dict = {}

        def _cb(d, adv):
            uuids = {str(u).lower() for u in adv.service_uuids}
            name_ok = not name_contains or (d.name and name_contains.lower() in d.name.lower())
            if SVC_CMD.lower() in uuids and name_ok:
                found[d.address] = d

        async with BleakScanner(_cb):
            await asyncio.sleep(timeout)
        if not found:
            raise RuntimeError("watch not found; ensure it's awake, nearby, and not held by the phone")
        if len(found) > 1:
            raise RuntimeError(f"several possible watches, pass address= to choose: {sorted(found)}")
        return cls(next(iter(found.values())))

    # ---- lifecycle -------------------------------------------------------
    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *exc):
        await self.disconnect()

    async def connect(self):
        await self._client.connect()
        try:
            await self._client.start_notify(CHAR_CMD_NOTIFY, self._on_cmd_notify)
        except Exception:
            await self.disconnect()  # don't keep holding a watch we can't hear
            raise
        try:
            await self._client.start_notify(CHAR_BULK_NOTIFY, self._on_bulk_notify)
        except Exception:
            log.debug("Watch bulk channel unavailable")

    async def disconnect(self):
        try:
            await self._client.disconnect()
        except Exception:
            log.debug("Watch disconnect failed", exc_info=True)
        self._on_disconnected()

    def _on_disconnected(self, _client=None):
        """Whoever is waiting for an ack hears now, rather than after the timeout."""
        for fut in self._waiters.values():
            if not fut.done():
                fut.set_exception(ConnectionError("watch disconnected"))
        self._waiters.clear()

    # ---- notification handlers ------------------------------------------
    def _on_cmd_notify(self, _c: BleakGATTCharacteristic, data: bytearray):
        data = bytes(data)
        if len(data) < 2:
            return
        resp = Response(group=data[0], cmd=data[1], payload=data[2:], raw=data)
        self._log.append(resp)
        key = (resp.group << 8) | resp.cmd
        if key == self._NOTIFY_KEY and (len(resp.payload) < 2 or resp.payload[1] != self._notify_index):
            return  # a late ack for an earlier packet must not count for this one
        fut = self._waiters.pop(key, None)
        if fut and not fut.done():
            fut.set_result(resp)

    def _on_bulk_notify(self, _c: BleakGATTCharacteristic, data: bytearray):
        if self._on_bulk:
            try:
                self._on_bulk(bytes(data))
            except Exception:
                log.exception("Watch bulk handler failed")

    # ---- core send/ack ---------------------------------------------------
    async def send_command(self, group: int, cmd: int, payload: bytes = b"",
                           *, wait_ack: bool = True, timeout: float = 5.0) -> Optional[Response]:
        """Write one TLV frame to 0xAF6 and optionally await its matching ack on 0xAF7.
        Commands are serialized by an internal lock so concurrent callers can't collide on
        the shared ack key."""
        if not (0 <= group <= 0xFF and 0 <= cmd <= 0xFF):
            raise ValueError("group/cmd must be bytes (0..255)")
        frame = bytes([group, cmd]) + payload
        if len(frame) > MAX_FRAME_BYTES:
            raise ValueError(f"frame longer than {MAX_FRAME_BYTES} bytes")
        async with self._lock:
            if not wait_ack:
                await self._client.write_gatt_char(CHAR_CMD_WRITE, frame, response=True)
                return None
            key = (group << 8) | cmd
            fut = asyncio.get_running_loop().create_future()
            self._waiters[key] = fut
            try:
                await self._client.write_gatt_char(CHAR_CMD_WRITE, frame, response=True)
                return await asyncio.wait_for(fut, timeout)
            finally:
                self._waiters.pop(key, None)

    async def replay(self, hex_frame: str, *, wait_ack: bool = True) -> Optional[Response]:
        """Send an exact captured frame, e.g. replay('0301ea070b01002d2d0600000000 0100')."""
        raw = bytes.fromhex(hex_frame.replace(" ", ""))
        if len(raw) < 2:
            raise ValueError("frame must be at least 2 bytes (group + cmd)")
        return await self.send_command(raw[0], raw[1], raw[2:], wait_ack=wait_ack)

    # ---- queries ---------------------------------------------------------
    async def get_status(self) -> Response:
        """[CONFIRMED] 0x0201 -> payload e.g. b31a2801003a010001000a. Byte 5 is the battery: see get_battery()."""
        return await self.send_command(GROUP_GET, 0x01)

    async def get_mac(self) -> str:
        """[CONFIRMED] 0x0204 -> 6 bytes = BLE MAC (the watch's own address)."""
        r = await self.send_command(GROUP_GET, 0x04)
        if len(r.payload) < 6:
            raise ValueError("short MAC response")
        return ":".join(f"{b:02X}" for b in r.payload[:6])

    async def get_battery(self) -> Optional[int]:
        """
        [CONFIRMED] Battery percent. NOTE: the standard char 0x2A19 is unreliable on this watch
        (returned 90 while real battery was 56); the real value is byte 5 of the 0x0201 status
        response (0x38=56 matched the device). So we read it from status.
        """
        r = await self.send_command(GROUP_GET, 0x01)
        # None rather than a made-up number: whatever answers here isn't authenticated.
        return r.payload[5] if len(r.payload) > 5 and r.payload[5] <= 100 else None

    async def get_device_name(self) -> str:
        """[CONFIRMED] Standard GAP Device Name 0x2A00. Chosen by the device, so it's untrusted text."""
        val = await self._client.read_gatt_char(_u(0x2A00))
        return bytes(val).decode("utf-8", "replace")

    # ---- confirmed setters ----------------------------------------------
    async def set_time(self, when: Optional[_dt.datetime] = None, time_zone: int = 0) -> Response:
        """
        [CONFIRMED] cmdType 104 -> wire 0x0301.
        Byte layout replicated from 3 captured samples:
          03 01 | year(u16 LE) | month | day | hour | minute | second | week |
          time_zone(u32 LE) | 01 00
        'week' = Mon=0..Sun=6 (Python datetime.weekday()). Tail 01 00 observed constant
        on this device (v2_support_set_time_zone_float == false).
        """
        if not 0 <= time_zone <= 0xFFFFFFFF:
            raise ValueError("time_zone must fit an unsigned 32-bit field")
        t = when or _dt.datetime.now()
        if t.tzinfo:
            t = t.astimezone()  # the watch shows these fields as they are, so they must be local time
        payload = struct.pack("<H", t.year) + bytes([t.month, t.day, t.hour,
                   t.minute, t.second, t.weekday()]) + struct.pack("<I", time_zone) + bytes([0x01, 0x00])
        return await self.send_command(GROUP_SET, 0x01, payload)

    async def set_step_goal(self, steps: int) -> Response:
        """
        [INFERRED] cmdType 105 -> wire 0x0303. Reconstructed from one sample
        (0303 00 10270000 00000000 == 10000 steps). Verify against your watch.
          03 03 | target_type(=00) | walk_goal_steps(u32 LE) | 00 00 00 00
        """
        if not (1 <= steps <= 200_000):
            raise ValueError("steps out of range (1..200000)")
        payload = bytes([0x00]) + struct.pack("<I", steps) + b"\x00\x00\x00\x00"
        return await self.send_command(GROUP_SET, 0x03, payload)

    # ---- appearance (CONFIRMED against capture: face switch + brightness) ----
    async def set_watch_face(self, index: int) -> Response:
        """
        [CONFIRMED] Select a built-in watch face/dial by index. 0x0312 <index>.
        Capture showed the app cycling index 01..04 while switching faces.
        """
        if not (0 <= index <= 0xFF):
            raise ValueError("watch-face index must be 0..255")
        return await self.send_command(GROUP_SET, 0x12, bytes([index]))

    async def set_brightness(self, percent: int) -> Response:
        """
        [CONFIRMED] Set screen brightness. 0x0332 <level> 01 00 02 00 00 00 00 00 00.
        Capture showed level in {0x14,0x28,0x3c,0x50,0x64} = {20,40,60,80,100}. Trailing bytes
        are the ScreenBrightness mode/opera fields, replicated as observed. Other values go to the nearest
        of those, so nothing untested is sent and the screen can't be set to black.
        """
        level = min(BRIGHTNESS_LEVELS, key=lambda known: abs(known - percent))
        return await self.send_command(GROUP_SET, 0x32,
                                       bytes([level]) + bytes.fromhex("010002000000000000"))

    # ---- v3 transport (reversed from libVeryFitMulti.so, CRC-validated) --
    #
    # Two framings coexist on 0xAF6:
    #   * v2/legacy  : [group][cmd][payload]      (set_time, goal, brightness…) — above.
    #   * v3         : 0x33 + daaddaad frame       (notifications, newer commands) — here.
    #
    V3_PREFIX = 0x33
    V3_MAGIC = bytes.fromhex("daaddaad")
    V3_VERSION = 0x01

    @staticmethod
    def _crc16_ccitt(data: bytes, seed: int = 0xFFFF) -> int:
        """Nordic crc16_compute (CRC-16/CCITT, poly 0x1021, init 0xFFFF)."""
        c = seed
        for b in data:
            c = ((c >> 8) | ((c << 8) & 0xFFFF))
            c ^= b
            c ^= (c & 0xFF) >> 4
            c ^= (c << 12) & 0xFFFF
            c ^= ((c & 0xFF) << 5) & 0xFFFF
            c &= 0xFFFF
        return c

    def _v3_frame(self, cmd_id: int, body: bytes, nseq: int, length_field: Optional[int] = None) -> bytes:
        """
        [CONFIRMED transport] Build a v3 frame. crc16 covers everything after the 0x33 prefix.
        length_field: the u16 after the reserved byte. Its exact semantics (body len vs header
        count) are not yet pinned (the captured cmd-0x0b frames were header-only); defaults to
        len(body) when a body is present. Validate against a real notification before relying.
        """
        lf = length_field if length_field is not None else len(body)
        covered = (self.V3_MAGIC + bytes([self.V3_VERSION, cmd_id, 0x00])
                   + struct.pack("<H", lf) + struct.pack("<H", nseq) + body)
        crc = self._crc16_ccitt(covered)
        return bytes([self.V3_PREFIX]) + covered + struct.pack("<H", crc)

    # ---- notification (group 0x05) — CONFIRMED against a live capture ----
    #
    # NOTE: this watch does NOT use the v3 daaddaad path for real notifications; it uses the
    # simple group-0x05 packet protocol below. The _v3_frame helper above is kept (it's a
    # correct, CRC-validated transport used by other v3 commands) but is not the notify path.
    #
    # Observed (WhatsApp "MITAURI12345" from "Jana ❤️:"):
    #   pkt1: 05 03 02 01 | 08 0c00 0c 4a616e6120e29da4efb88f3a
    #   pkt2: 05 03 02 02 | 4d495441555249313233343500000000
    # header = [0x05][0x03][total_packets][packet_index]
    # content (reassembled) = [app_type u8][text_len u16 LE][title_len u8][title utf8][text utf8]
    #                         padded with 0x00 to a multiple of 16.
    NOTIFY_CMD = bytes([0x05, 0x03])
    NOTIFY_CHUNK = 16  # 20-byte ATT write minus the 4-byte header

    # Far below what the fields could hold (255 and 4 KB). Senders choose this text, and only 5 packets have been
    # seen on the wire, so nobody gets to find out what the watch's firmware does with more. 20 packets at most.
    TITLE_MAX = 64
    TEXT_MAX = 250
    _NOTIFY_KEY = (0x05 << 8) | 0x03

    @staticmethod
    def _utf8_trunc(s: str, max_bytes: int) -> bytes:
        """Encode to UTF-8 and truncate to <= max_bytes without splitting a character."""
        b = s.encode("utf-8")
        if len(b) <= max_bytes:
            return b
        return b[:max_bytes].decode("utf-8", "ignore").encode("utf-8")

    @staticmethod
    def _display_text(s: str) -> str:
        """Removes what could garble the display or disguise who wrote it. Emoji joiners stay."""
        return "".join(" " if unicodedata.category(c) == "Cc" else c for c in s if c not in BIDI_CONTROLS)

    def _notification_packets(self, title: str, text: str, app_type: int = 0x08) -> list[bytes]:
        if not (0 <= app_type <= 0xFF):
            raise ValueError("app_type must be 0..255")
        tb = self._utf8_trunc(self._display_text(title), self.TITLE_MAX)
        nb = self._utf8_trunc(self._display_text(text), self.TEXT_MAX)
        content = bytes([app_type]) + struct.pack("<H", len(nb)) + bytes([len(tb)]) + tb + nb
        if len(content) % self.NOTIFY_CHUNK:
            content += b"\x00" * (self.NOTIFY_CHUNK - len(content) % self.NOTIFY_CHUNK)
        chunks = [content[i:i + self.NOTIFY_CHUNK] for i in range(0, len(content), self.NOTIFY_CHUNK)]
        total = len(chunks)
        return [self.NOTIFY_CMD + bytes([total, i + 1]) + c for i, c in enumerate(chunks)]

    async def send_notification(self, text: str, *, title: str = "", app_type: int = 0x08,
                                ack_timeout: float = 5.0) -> bool:
        """
        [CONFIRMED] Push a notification to the watch (group 0x05). `title` is the sender/app
        line, `text` is the body, both sent in cleartext. Long ones are cut on a UTF-8 boundary.
        Each packet is written and acked on 0xAF7 as 05 03 <total> <idx> <status>. Returns True only if the watch
        accepted every packet, and gives up at the first failure instead of pushing the rest at a dead link.
        """
        packets = self._notification_packets(title, text, app_type)
        async with self._lock:       # hold across all packets so nothing interleaves
            for index, pkt in enumerate(packets, start=1):
                fut = asyncio.get_running_loop().create_future()
                self._notify_index = index
                self._waiters[self._NOTIFY_KEY] = fut
                try:
                    await self._client.write_gatt_char(CHAR_CMD_WRITE, pkt, response=True)
                    ack = await asyncio.wait_for(fut, ack_timeout)
                except (TimeoutError, ConnectionError, BleakError):
                    return False
                finally:
                    self._waiters.pop(self._NOTIFY_KEY, None)
                if len(ack.payload) > 2 and ack.payload[2] != 0:
                    return False
        return True


# --- quick manual test -----------------------------------------------------
if __name__ == "__main__":
    import sys

    async def _main():
        if len(sys.argv) > 1:
            watch = VeryFitWatch(sys.argv[1])          # address given
        else:
            print("Scanning… (pass a BLE address as argv[1] to skip)")
            for d in await BleakScanner.discover(timeout=8.0):
                print(f"  {d.address}  {d.name}")
            return
        async with watch:
            print("connected; setting time…")
            print("set_time ack:", (await watch.set_time()).raw.hex())
            print("status:", (await watch.get_status()).raw.hex())

    asyncio.run(_main())
