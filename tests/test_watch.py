"""The watch client, against the frames captured from the real watch, with a fake radio."""

import asyncio
import datetime as dt
from pathlib import Path

import pytest

from mitauri import tools
from mitauri.watch import CHAR_CMD_WRITE, VeryFitWatch


class FakeClient:
    """Answers each write with whatever the test's `answer` returns."""

    def __init__(self, answer=None):
        self.answer = answer
        self.writes: list[bytes] = []
        self.disconnected = False
        self.handler = None

    async def connect(self):
        pass

    async def disconnect(self):
        self.disconnected = True

    async def start_notify(self, _characteristic, handler):
        self.handler = self.handler or handler

    async def write_gatt_char(self, characteristic, frame, response=True):
        assert characteristic == CHAR_CMD_WRITE
        self.writes.append(bytes(frame))
        reply = self.answer(bytes(frame)) if self.answer else None
        for data in reply or []:
            self.watch._on_cmd_notify(None, bytearray(data))


def make(answer=None):
    client = FakeClient(answer)
    watch = VeryFitWatch("AA:BB", client=client)
    client.watch = watch
    return watch, client


def good_ack(frame):
    return [frame[:4] + b"\x00"] if frame[0] == 5 else [frame[:2]]


def test_notification_matches_the_capture():
    watch, _ = make()
    packets = [p.hex() for p in watch._notification_packets("Jana ❤️:", "MITAURI12345")]
    assert packets == ["05030201080c000c4a616e6120e29da4efb88f3a", "050302024d495441555249313233343500000000"]


def test_v3_frames_match_the_capture():
    watch, _ = make()
    assert watch._v3_frame(0x0B, b"", 2, length_field=7).hex() == "33daaddaad010b00070002007edb"
    assert watch._v3_frame(0x0B, b"", 3, length_field=7).hex() == "33daaddaad010b00070003004fe8"


def test_commands_match_the_capture():
    async def go():
        watch, client = make(good_ack)
        await watch.set_time(dt.datetime(2026, 11, 1, 0, 45, 45))
        await watch.set_brightness(20)
        await watch.set_watch_face(3)
        return [w.hex() for w in client.writes]
    assert asyncio.run(go()) == ["0301ea070b01002d2d06000000000100", "033214010002000000000000", "031203"]


def test_an_aware_time_is_sent_as_local_time():
    async def go():
        watch, client = make(good_ack)
        moment = dt.datetime(2026, 11, 1, 12, 0, tzinfo=dt.timezone.utc)
        await watch.set_time(moment)
        return client.writes[0], moment.astimezone()
    frame, local = asyncio.run(go())
    assert (frame[6], frame[7]) == (local.hour, local.minute)


def test_brightness_never_goes_black_or_off_the_captured_levels():
    async def go():
        watch, client = make(good_ack)
        for percent in (0, 55, 9999):
            await watch.set_brightness(percent)
        return [w[2] for w in client.writes]
    assert asyncio.run(go()) == [20, 60, 100]


def test_long_text_is_bounded_without_splitting_a_character():
    watch, _ = make()
    packets = watch._notification_packets("🙂" * 500, "ä" * 5000)
    assert len(packets) == 20 and all(len(p) == 20 for p in packets)
    content = b"".join(p[4:] for p in packets)
    text_length = int.from_bytes(content[1:3], "little")
    assert content[3] == 64
    assert content[4 + 64:4 + 64 + text_length].decode() == "ä" * (text_length // 2)


def test_control_and_direction_characters_dont_reach_the_display():
    watch, _ = make()
    content = watch._notification_packets("a‮b", "x\ny\x00")[0][4:]
    assert content[4:9] == b"abx y"


def test_every_notification_packet_must_be_acked_for_its_own_index():
    async def go():
        watch, client = make(good_ack)
        assert await watch.send_notification("x" * 40, title="t") is True
        return client.writes
    assert len(asyncio.run(go())) == 3


def test_a_rejected_packet_is_a_failure():
    async def go():
        watch, client = make(lambda frame: [frame[:4] + b"\x01"])
        assert await watch.send_notification("x" * 40) is False
        return client.writes
    assert len(asyncio.run(go())) == 1


def test_a_silent_watch_stops_the_message_at_the_first_packet():
    async def go():
        watch, client = make()
        assert await watch.send_notification("x" * 200, ack_timeout=0.01) is False
        return client.writes
    assert len(asyncio.run(go())) == 1


def test_a_late_ack_for_the_previous_packet_does_not_count():
    async def go():
        def only_old(frame):
            return [frame[:3] + bytes([frame[3] - 1, 0])]
        watch, _ = make(only_old)
        return await watch.send_notification("x" * 40, ack_timeout=0.01)
    assert asyncio.run(go()) is False


def test_disconnecting_wakes_whoever_waits_at_once():
    async def go():
        watch, client = make(lambda frame: watch._on_disconnected())
        started = asyncio.get_running_loop().time()
        assert await watch.send_notification("x", ack_timeout=30) is False
        return asyncio.get_running_loop().time() - started
    assert asyncio.run(go()) < 1


def test_failing_to_subscribe_lets_go_of_the_watch():
    async def go():
        watch, client = make()

        async def refuse(*_):
            raise OSError("no")
        client.start_notify = refuse
        with pytest.raises(OSError):
            await watch.connect()
        return client.disconnected
    assert asyncio.run(go())


def test_oversized_frames_are_refused():
    async def go():
        watch, client = make(good_ack)
        with pytest.raises(ValueError):
            await watch.send_command(3, 1, b"x" * 19)
        return client.writes
    assert asyncio.run(go()) == []


@pytest.mark.parametrize(("frame", "expected"), [
    ("0201b31a28010036010001000a", 54),
    ("0201b31a280100ff010001000a", None),
    ("0201b31a", None),
])
def test_battery_is_read_from_status_and_not_believed_blindly(frame, expected):
    async def go():
        watch, _ = make(lambda _: [bytes.fromhex(frame)])
        return await watch.get_battery()
    assert asyncio.run(go()) == expected


def test_a_short_mac_answer_is_an_error():
    async def go():
        watch, _ = make(lambda _: [bytes.fromhex("0204aabb")])
        with pytest.raises(ValueError):
            await watch.get_mac()
    asyncio.run(go())


def test_a_broken_bulk_handler_cant_hurt_the_connection():
    watch, _ = make()
    watch._on_bulk = lambda _: 1 / 0
    watch._on_bulk_notify(None, bytearray(b"x"))


def test_scan_refuses_to_guess_between_two_watches(monkeypatch):
    from mitauri import watch as module
    from types import SimpleNamespace as NS

    class Scanner:
        def __init__(self, callback):
            self.callback = callback

        async def __aenter__(self):
            adv = NS(service_uuids=[module.SVC_CMD], rssi=-40)
            self.callback(NS(address="AA", name="ACT307"), adv)
            self.callback(NS(address="BB", name="ACT307"), NS(service_uuids=[module.SVC_CMD], rssi=-20))
            self.callback(NS(address="CC", name="ACT307"), NS(service_uuids=[], rssi=-10))

        async def __aexit__(self, *_):
            pass

    monkeypatch.setattr(module, "BleakScanner", Scanner)
    with pytest.raises(RuntimeError, match="AA.*BB"):
        asyncio.run(VeryFitWatch.scan(timeout=0))


def test_claude_can_reach_only_the_fixed_watch_actions_and_tool_startup_stays_free_of_bluetooth():
    folder = Path(tools.__file__).with_name("modules")
    source = (folder / "watch.py").read_text()
    assert "replay" not in source and "send_command" not in source
    assert not any("mitauri.watch" in p.read_text() or "bleak" in p.read_text() or "..watch" in p.read_text()
                   for p in folder.glob("*.py"))
