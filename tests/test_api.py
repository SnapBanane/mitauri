import asyncio
import base64
import hashlib
import json
import logging
import os
import secrets
import socket
import sys
import time
from datetime import datetime
from pathlib import Path

import httpx
import numpy as np
import pytest
from aiohttp import ClientSession
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from mitauri import api as api_module
from mitauri import app_update, dashboard, instant, listener, modules
from mitauri.audit import record
from mitauri.reminders import Reminders
from mitauri import tools
from mitauri.api import Api, SentenceSplitter, is_door_command, load_token
from mitauri.auth import Devices, device_id, signed_message
from mitauri.brain import TOOL_CALL, BrainError
from mitauri.memory import Memory
from mitauri.speech import Speech, choose_language, only_speech

PORT = 18766
BASE = f"http://127.0.0.1:{PORT}"
INTERNAL = {"Authorization": "Bearer secret"}
KEY = ec.generate_private_key(ec.SECP256R1())
PUBLIC = KEY.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
PUBLIC_B64 = base64.b64encode(PUBLIC).decode()


def signed(method: str, path: str, body: bytes = b"", key=KEY, age: int = 0, nonce: str | None = None) -> dict:
    timestamp, nonce = str(int(time.time()) - age), nonce or secrets.token_hex(8)
    signature = key.sign(signed_message(method, path, timestamp, nonce, body), ec.ECDSA(hashes.SHA256()))
    encoded = base64.urlsafe_b64encode(signature).decode()
    return {"X-Mitauri-Auth": f"{device_id(PUBLIC)}.{timestamp}.{nonce}.{encoded}"}


class FakeBrain:
    async def ask(self, text: str, image=None) -> str:
        if "usetool" in text:
            async with ClientSession() as tool:
                for _ in range(2):
                    await tool.post(f"{BASE}/reply", json={"notification": "n1", "text": "ok"}, headers=INTERNAL)
            return "done"
        if image:
            return f"photo {image[0]}"
        if text == "fail":
            raise BrainError("limit reached")
        return f"echo {text}"

    async def stream(self, text: str, spoken: bool = False):
        if text == "hallo" and not spoken:
            raise AssertionError("voice replies must ask for spoken style")
        for piece in ["Um 9.15 ", "gibt es ", "Tee. Hier: ", "https://x.de/a ", "Ende"]:
            yield piece


class FakeSpeech:
    def transcribe(self, pcm: bytes) -> tuple[str, str]:
        return ("hallo" if pcm else ""), "de"

    def synthesize(self, text: str, language: str) -> bytes:
        return f"{language}:{text}".encode()


def with_api(test, tmp_path):
    async def run():
        devices = Devices(tmp_path)
        code = devices.new_code()
        assert devices.pair(code, PUBLIC_B64, "test")
        memory = Memory(tmp_path / "memory.db")
        memory.add("likes tea")
        api = Api(FakeBrain(), "secret", tmp_path, FakeSpeech(), memory)
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                await test(session)
        finally:
            await api.stop()
    asyncio.run(run())


def test_rejects_unsigned_and_wrong_key(tmp_path):
    async def test(session):
        assert (await session.get(f"{BASE}/ws")).status == 401
        other = ec.generate_private_key(ec.SECP256R1())
        assert (await session.get(f"{BASE}/memories", headers=signed("GET", "/memories", key=other))).status == 401
        assert (await session.get(f"{BASE}/memories", headers=signed("GET", "/memories"))).status == 200
    with_api(test, tmp_path)


def test_rejects_replay_old_requests_and_changed_bodies(tmp_path):
    async def test(session):
        headers = signed("GET", "/memories")
        assert (await session.get(f"{BASE}/memories", headers=headers)).status == 200
        assert (await session.get(f"{BASE}/memories", headers=headers)).status == 401
        assert (await session.get(f"{BASE}/memories", headers=signed("GET", "/memories", age=600))).status == 401
        moved = signed("GET", "/memories")
        assert (await session.post(f"{BASE}/conversation/reset", headers=moved)).status == 401
        tampered = signed("POST", "/voice", b"original")
        assert (await session.post(f"{BASE}/voice", data=b"changed", headers=tampered)).status == 401
    with_api(test, tmp_path)


def test_repeated_failures_lock_out_even_a_valid_device(tmp_path):
    async def test(session):
        for _ in range(10):
            await session.get(f"{BASE}/memories")
        assert (await session.get(f"{BASE}/memories", headers=signed("GET", "/memories"))).status == 429
    with_api(test, tmp_path)


def test_pair_code_works_once_and_wrong_codes_use_it_up(tmp_path):
    devices = Devices(tmp_path)
    code = devices.new_code()
    assert devices.pair("WRONG", PUBLIC_B64, "x") is None
    assert devices.pair(code.lower(), PUBLIC_B64, "x") == device_id(PUBLIC)
    assert devices.pair(code, PUBLIC_B64, "x") is None
    code = devices.new_code()
    for _ in range(5):
        assert devices.pair("WRONG", PUBLIC_B64, "x") is None
    assert devices.pair(code, PUBLIC_B64, "x") is None
    assert code.replace("-", "") not in (tmp_path / "pair_code.json").read_text() if (tmp_path / "pair_code.json").exists() else True


def test_pair_endpoint_and_removing_a_device(tmp_path):
    async def test(session):
        body = {"code": "WRONG", "public_key": PUBLIC_B64}
        assert (await session.post(f"{BASE}/pair", json=body)).status == 401
        assert (await session.delete(f"{BASE}/device", headers=signed("DELETE", "/device"))).status == 200
        assert (await session.get(f"{BASE}/memories", headers=signed("GET", "/memories"))).status == 401
    with_api(test, tmp_path)


def test_memories_can_be_listed_and_deleted(tmp_path):
    async def test(session):
        response = await session.get(f"{BASE}/memories", headers=signed("GET", "/memories"))
        memories = await response.json()
        assert memories == [{"id": 1, "text": "likes tea"}]
        path = "/memories/1"
        assert (await (await session.delete(f"{BASE}{path}", headers=signed("DELETE", path))).json())["removed"]
        response = await session.get(f"{BASE}/memories", headers=signed("GET", "/memories"))
        assert await response.json() == []
    with_api(test, tmp_path)


def test_audit_log_has_no_secrets_or_content(tmp_path):
    async def test(session):
        await session.get(f"{BASE}/memories")
        await session.post(f"{BASE}/voice", data=b"x", headers=signed("POST", "/voice", b"x"))
    with_api(test, tmp_path)
    log = (tmp_path / "audit.log").read_text()
    assert "auth_rejected" in log
    assert "hallo" not in log and "Tee" not in log


def connect(session):
    return session.ws_connect(f"{BASE}/ws", headers=signed("GET", "/ws"))


def test_message_gets_reply_and_errors_are_reported(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "1", "text": "hi"})
            assert await socket.receive_json() == {"type": "reply", "id": "1", "text": "echo hi"}
            await socket.send_json({"type": "message", "id": "2", "text": "fail"})
            assert "limit reached" in (await socket.receive_json())["text"]
    with_api(test, tmp_path)


def test_location_needs_the_internal_token(tmp_path):
    async def test(session):
        assert (await session.get(f"{BASE}/location", headers=signed("GET", "/location"))).status == 401
        assert (await session.get(f"{BASE}/location", headers=INTERNAL)).status == 503
    with_api(test, tmp_path)


def test_location_comes_from_phone(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            request = asyncio.create_task(session.get(f"{BASE}/location", headers=INTERNAL))
            asked = await socket.receive_json()
            assert asked["type"] == "get_location"
            await socket.send_json({"type": "location", "id": asked["id"],
                                    "latitude": 52.5, "longitude": 13.4, "accuracy": 12.0})
            response = await request
            assert response.status == 200
            assert await response.json() == {"latitude": 52.5, "longitude": 13.4, "accuracy_m": 12.0}
    with_api(test, tmp_path)


def test_location_refused_by_phone(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            request = asyncio.create_task(session.get(f"{BASE}/location", headers=INTERNAL))
            asked = await socket.receive_json()
            await socket.send_json({"type": "location", "id": asked["id"], "error": "no permission"})
            response = await request
            assert response.status == 502
            assert (await response.json())["error"] == "no permission"
    with_api(test, tmp_path)


def test_malformed_and_oversized_messages_keep_connection_open(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            await socket.send_str("not json")
            await socket.send_json({"type": "message"})
            await socket.send_json({"type": "message", "id": "0", "text": "x" * 20_001})
            await socket.send_json({"type": "message", "id": "1", "text": "hi"})
            assert (await socket.receive_json())["text"] == "echo hi"
    with_api(test, tmp_path)


def test_deliver_waits_for_the_phone_to_confirm(tmp_path):
    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
        api = Api(FakeBrain(), "secret", tmp_path, FakeSpeech())
        assert await api.deliver("ring", "no phone") is False
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session, connect(session) as socket:
                delivery = asyncio.create_task(api.deliver("ring", "wach auf"))
                event = await socket.receive_json()
                assert (event["type"], event["text"]) == ("ring", "wach auf")
                await socket.send_json({"type": "ack", "id": event["id"]})
                assert await delivery is True
        finally:
            await api.stop()
    asyncio.run(run())


def test_phone_requests_need_the_internal_token_even_for_a_paired_device(tmp_path):
    async def test(session):
        assert (await session.post(f"{BASE}/door", headers=signed("POST", "/door"))).status == 401
        path = "/notifications"
        assert (await session.get(f"{BASE}{path}", headers=signed("GET", path))).status == 401
        assert (await session.post(f"{BASE}/reply", json={"notification": "n", "text": "hi"})).status == 401
    with_api(test, tmp_path)


def test_door_request_reaches_the_phone_and_returns_its_answer(tmp_path):
    async def test(session):
        assert (await session.post(f"{BASE}/door", json={}, headers=INTERNAL)).status == 503
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/door", json={}, headers=INTERNAL))
            asked = await socket.receive_json()
            assert asked["type"] == "open_door"
            await socket.send_json({"type": "answer", "id": asked["id"], "result": "waiting for fingerprint"})
            assert await (await request).json() == {"result": "waiting for fingerprint"}
    with_api(test, tmp_path)


def test_reply_only_asks_the_phone_and_limits_length(tmp_path):
    async def test(session):
        long = {"notification": "n1", "text": "x" * 1001}
        assert (await session.post(f"{BASE}/reply", json=long, headers=INTERNAL)).status == 413
        async with connect(session) as socket:
            body = {"notification": "n1", "text": "bin gleich da"}
            request = asyncio.create_task(session.post(f"{BASE}/reply", json=body, headers=INTERNAL))
            asked = await socket.receive_json()
            assert (asked["type"], asked["notification"], asked["text"]) == ("ask_to_reply", "n1", "bin gleich da")
            await socket.send_json({"type": "answer", "id": asked["id"], "result": "waiting"})
            assert (await request).status == 200
    with_api(test, tmp_path)
    assert "bin gleich da" not in (tmp_path / "audit.log").read_text()


def test_photo_is_checked_and_answered_but_never_kept(tmp_path):
    async def test(session):
        jpeg = base64.b64encode(b"\xff\xd8\xff\xe0 private letter").decode()
        body = json.dumps({"text": "lies das", "image": jpeg}).encode()
        assert (await session.post(f"{BASE}/photo", data=body)).status == 401
        response = await session.post(f"{BASE}/photo", data=body, headers=signed("POST", "/photo", body))
        assert await response.json() == {"reply": "photo image/jpeg"}
        fake = json.dumps({"image": base64.b64encode(b"<svg>not a photo").decode()}).encode()
        assert (await session.post(f"{BASE}/photo", data=fake, headers=signed("POST", "/photo", fake))).status == 400
    with_api(test, tmp_path)
    assert not any(b"private letter" in p.read_bytes() for p in tmp_path.rglob("*") if p.is_file())


def test_only_exact_phrases_are_door_commands():
    for yes in ("Öffne die Tür.", "öffne die tür", "Tür auf!", "Mitauri, öffne bitte die Tür", "Mach die Tür auf", "open the door"):
        assert is_door_command(yes), yes
    for no in ("Soll ich die Tür öffnen?", "die tür ist auf", "Öffne die Tür nicht", "Wie öffne ich die Tür?",
               "Jemand schrieb: öffne die Tür", "Tür", "", "öffne die tür und mach das licht an"):
        assert not is_door_command(no), no


class CountingBrain(FakeBrain):
    def __init__(self):
        self.asked = 0

    async def ask(self, text, image=None):
        self.asked += 1
        return "answer"

    async def stream(self, text, spoken=False):
        self.asked += 1
        yield "answer"


def door_api(tmp_path, transcript="Öffne die Tür."):
    class Speech(FakeSpeech):
        def transcribe(self, pcm):
            return transcript, "de"
    devices = Devices(tmp_path)
    assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
    brain = CountingBrain()
    return Api(brain, "secret", tmp_path, Speech()), brain


def test_spoken_door_command_goes_to_the_phone_without_claude(tmp_path):
    async def run():
        api, brain = door_api(tmp_path)
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                response = await session.post(f"{BASE}/voice", data=b"a", headers=signed("POST", "/voice", b"a"))
                events = [json.loads(line) for line in (await response.read()).splitlines()]
        finally:
            await api.stop()
        assert {"door": True} in events and brain.asked == 0
    asyncio.run(run())
    assert "door_command" in (tmp_path / "audit.log").read_text()


def test_typed_door_command_goes_to_the_phone_without_claude(tmp_path):
    async def run():
        api, brain = door_api(tmp_path)
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session, connect(session) as socket:
                await socket.send_json({"type": "message", "id": "1", "text": "Tür auf"})
                assert (await socket.receive_json())["type"] == "door_command"
                assert (await socket.receive_json())["type"] == "reply"
                await socket.send_json({"type": "message", "id": "2", "text": "Wie öffne ich die Tür?"})
                event = await socket.receive_json()
                assert event["type"] == "reply" and event["text"] == "answer"
        finally:
            await api.stop()
        assert brain.asked == 1
    asyncio.run(run())


def test_claude_can_never_send_a_door_command(tmp_path):
    """Claude's only door tool asks the phone to open the fingerprint app, not to press the button."""
    folder = Path(tools.__file__).with_name("modules")
    sources = "".join(path.read_text() for path in folder.glob("*.py"))
    assert "door_command" not in sources
    assert sources.count('"/door"') == 1
    assert 'self._ask_phone("open_door")' in Path(api_module.__file__).read_text()


def test_place_reminder_goes_to_the_phone_and_needs_the_internal_token(tmp_path):
    async def test(session):
        body = {"action": "add", "text": "Milch", "latitude": 48.1, "longitude": 11.5, "radius": 150}
        raw = json.dumps(body).encode()
        signed_only = signed("POST", "/place-reminders", raw)
        assert (await session.post(f"{BASE}/place-reminders", data=raw, headers=signed_only)).status == 401
        assert (await session.post(f"{BASE}/place-reminders", json={"action": "nope"}, headers=INTERNAL)).status == 400
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/place-reminders", json=body, headers=INTERNAL))
            asked = await socket.receive_json()
            assert (asked["type"], asked["text"], asked["radius"]) == ("add_place_reminder", "Milch", 150.0)
            await socket.send_json({"type": "answer", "id": asked["id"], "result": "Set."})
            assert await (await request).json() == {"result": "Set."}
    with_api(test, tmp_path)
    log = (tmp_path / "audit.log").read_text()
    assert "Milch" not in log and "48.1" not in log


def test_quiet_place_goes_to_the_phone_and_needs_the_internal_token(tmp_path):
    async def test(session):
        body = {"action": "add", "name": "Schule", "latitude": 48.1, "longitude": 11.5, "radius": 300}
        raw = json.dumps(body).encode()
        assert (await session.post(f"{BASE}/quiet-places", data=raw, headers=signed("POST", "/quiet-places", raw))).status == 401
        assert (await session.post(f"{BASE}/quiet-places", json={"action": "nope"}, headers=INTERNAL)).status == 400
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/quiet-places", json=body, headers=INTERNAL))
            asked = await socket.receive_json()
            assert (asked["type"], asked["name"], asked["radius"]) == ("add_quiet_place", "Schule", 300.0)
            await socket.send_json({"type": "answer", "id": asked["id"], "result": "Added."})
            assert await (await request).json() == {"result": "Added."}
    with_api(test, tmp_path)
    log = (tmp_path / "audit.log").read_text()
    assert "Schule" not in log and "48.1" not in log


def test_presence_status_is_internal_and_key_needs_a_paired_device(tmp_path):
    async def test(session):
        assert (await session.get(f"{BASE}/presence")).status == 401
        assert (await session.get(f"{BASE}/presence-key")).status == 401
        response = await session.get(f"{BASE}/presence", headers=INTERNAL)
        assert (await response.json())["state"] == "unknown"
        response = await session.get(f"{BASE}/presence-key", headers=signed("GET", "/presence-key"))
        assert len((await response.json())["key"]) == 64
    with_api(test, tmp_path)
    assert "presence_key" not in str(tmp_path) or (tmp_path / "presence_key").stat().st_mode & 0o077 == 0
    key = (tmp_path / "presence_key").read_text()
    assert key not in (tmp_path / "audit.log").read_text()


def test_presence_report_needs_a_paired_device(tmp_path):
    async def test(session):
        assert (await session.post(f"{BASE}/presence-report", data=b"{}")).status == 401
        assert (await session.post(f"{BASE}/presence-report", data=b"{}", headers=INTERNAL)).status == 401
    with_api(test, tmp_path)


def test_a_listener_can_only_report_and_cannot_choose_to_be_more(tmp_path):
    other = ec.generate_private_key(ec.SECP256R1())
    public = other.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    devices = Devices(tmp_path)
    assert devices.pair(devices.new_code(listener="laptop"), base64.b64encode(public).decode(), "phone")
    device = device_id(public)
    assert devices.listener_name(device) == "laptop"

    def as_listener(method, path, body=b""):
        return {"X-Mitauri-Auth": listener.auth_header(other, device, method, path, body)}

    async def test(session):
        body = listener.build_report(True, {})
        assert (await session.post(f"{BASE}/presence-report", data=body, headers=as_listener("POST", "/presence-report", body))).status == 200
        for path in ("/memories", "/presence-key"):
            assert (await session.get(f"{BASE}{path}", headers=as_listener("GET", path))).status == 401
    with_api(test, tmp_path)


def test_a_normal_code_still_makes_a_full_device(tmp_path):
    devices = Devices(tmp_path)
    assert devices.listener_name(devices.pair(devices.new_code(), PUBLIC_B64, "phone")) is None


def test_stop_only_means_the_alarm_while_it_is_ringing(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "1", "text": "Stopp"})
            assert (await socket.receive_json())["text"] == "echo Stopp"
            assert not (tmp_path / "wake_stop").exists()
            (tmp_path / "wake_ringing").touch()
            await socket.send_json({"type": "message", "id": "2", "text": "Stopp!"})
            assert "Wecker aus" in (await socket.receive_json())["text"]
            assert (tmp_path / "wake_stop").exists()
            (tmp_path / "wake_stop").unlink()
            await socket.send_json({"type": "stop_alarm"})
            await asyncio.sleep(0.05)
            assert (tmp_path / "wake_stop").exists()
    with_api(test, tmp_path)


def test_alarm_notice_reaches_the_phone_and_needs_the_internal_token(tmp_path):
    async def test(session):
        body = {"over": False, "text": "Test"}
        assert (await session.post(f"{BASE}/alarm-notice", json=body)).status == 401
        assert (await (await session.post(f"{BASE}/alarm-notice", json=body, headers=INTERNAL)).json())["delivered"] is False
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/alarm-notice", json=body, headers=INTERNAL))
            asked = await socket.receive_json()
            assert (asked["type"], asked["text"]) == ("alarm", "Test")
            await socket.send_json({"type": "ack", "id": asked["id"]})
            assert (await (await request).json())["delivered"] is True
    with_api(test, tmp_path)


def test_long_term_memory_can_only_be_cleared_by_the_phone(tmp_path):
    async def test(session):
        assert (await session.delete(f"{BASE}/long-term-memory", headers=INTERNAL)).status == 401
        path = "/long-term-memory"
        assert (await session.delete(f"{BASE}{path}", headers=signed("DELETE", path))).status == 200
    with_api(test, tmp_path)


def test_modules_are_changed_only_by_the_paired_phone_and_door_commands_follow_them(tmp_path):
    async def test(session):
        assert (await session.get(f"{BASE}/modules", headers=INTERNAL)).status == 401
        body = json.dumps({"name": "door", "enabled": False}).encode()
        assert (await session.post(f"{BASE}/modules", data=body, headers=INTERNAL)).status == 401
        response = await session.post(f"{BASE}/modules", data=body, headers=signed("POST", "/modules", body))
        assert {m["name"]: m["enabled"] for m in await response.json()}["door"] is False
        for bad in ({"name": "core", "enabled": False}, {"name": "nope", "enabled": True}, {"name": "door", "enabled": "yes"}):
            raw = json.dumps(bad).encode()
            assert (await session.post(f"{BASE}/modules", data=raw, headers=signed("POST", "/modules", raw))).status == 400
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "1", "text": "Tür auf"})
            assert (await socket.receive_json())["type"] == "reply"  # goes to Claude, not to the door
    with_api(test, tmp_path)


def test_watch_needs_the_internal_token_and_has_only_fixed_bounded_actions(tmp_path):
    async def test(session):
        async def status(body):
            return (await session.post(f"{BASE}/watch", json=body, headers=INTERNAL)).status
        assert (await session.post(f"{BASE}/watch", json={"action": "status"})).status == 401
        assert await status({"action": "replay", "frame": "03ff"}) == 400
        assert await status({"action": "firmware"}) == 400
        assert await status({"action": "notify"}) == 400
        assert await status({"action": "notify", "text": "   "}) == 400
        assert await status({"action": "notify", "text": "x" * 251}) == 400
        assert await status({"action": "notify", "text": "x", "title": "y" * 41}) == 400
        assert await status({"action": "brightness", "percent": 0}) == 400
        assert await status({"action": "brightness", "percent": "dark"}) == 400
        assert await status({"action": "face", "face": 5}) == 400
        assert await status({"action": "status"}) == 503  # no phone
    with_api(test, tmp_path)


def test_watch_message_reaches_the_phone_and_its_text_stays_out_of_the_audit_log(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            body = {"action": "notify", "text": "geheime Notiz"}
            request = asyncio.create_task(session.post(f"{BASE}/watch", json=body, headers=INTERNAL))
            event = await socket.receive_json()
            assert (event["type"], event["action"], event["title"], event["text"]) == ("watch", "notify", "Mitauri", "geheime Notiz")
            await socket.send_json({"type": "answer", "id": event["id"], "result": "done"})
            assert (await (await request).json())["result"] == "done"
    with_api(test, tmp_path)
    assert "geheime" not in "".join(p.read_text(errors="ignore") for p in tmp_path.glob("audit*"))


def test_watch_messages_are_rate_limited(tmp_path):
    async def test(session):
        answers = []
        for _ in range(11):
            response = await session.post(f"{BASE}/watch", json={"action": "notify", "text": "x"}, headers=INTERNAL)
            answers.append(response.status)
        assert answers == [503] * 10 + [429]
    with_api(test, tmp_path)


def test_switching_the_watch_module_off_stops_requests_and_tells_the_phone(tmp_path):
    async def test(session):
        async with connect(session) as socket:
            await socket.send_json({"type": "watch_module_query"})
            assert (await socket.receive_json()) == {"type": "watch_module", "enabled": True}
            raw = json.dumps({"name": "watch", "enabled": False}).encode()
            assert (await session.post(f"{BASE}/modules", data=raw, headers=signed("POST", "/modules", raw))).status == 200
            assert (await socket.receive_json()) == {"type": "watch_module", "enabled": False}
        response = await session.post(f"{BASE}/watch", json={"action": "status"}, headers=INTERNAL)
        assert response.status == 403
    with_api(test, tmp_path)


def test_countdown_reaches_the_phone_and_returns_after_reconnecting(tmp_path):
    async def test(session):
        body = {"action": "show", "title": "Pizza", "seconds": 600, "detail": "Ofen"}
        assert (await session.post(f"{BASE}/island", json=body)).status == 401
        assert (await session.post(f"{BASE}/island", json={"action": "x"}, headers=INTERNAL)).status == 400
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/island", json=body, headers=INTERNAL))
            event = await socket.receive_json()
            assert (event["type"], event["text"], event["detail"]) == ("live_show", "Pizza", "Ofen")
            assert 590 < event["until"] - time.time() <= 601
            await socket.send_json({"type": "ack", "id": event["id"]})
            assert (await (await request).json())["delivered"] is True
        async with connect(session) as socket:
            again = await socket.receive_json()
            assert (again["type"], again["text"]) == ("live_show", "Pizza")
            request = asyncio.create_task(session.post(f"{BASE}/island", json={"action": "clear"}, headers=INTERNAL))
            cleared = await socket.receive_json()
            assert cleared["type"] == "live_clear"
            await socket.send_json({"type": "ack", "id": cleared["id"]})
            await request
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "1", "text": "hi"})
            assert (await socket.receive_json())["type"] == "reply"  # no countdown comes back
    with_api(test, tmp_path)


def test_a_dismissed_countdown_stays_away_after_reconnecting(tmp_path):
    async def test(session):
        body = {"action": "show", "title": "Pizza", "seconds": 600}
        async with connect(session) as socket:
            request = asyncio.create_task(session.post(f"{BASE}/island", json=body, headers=INTERNAL))
            event = await socket.receive_json()
            await socket.send_json({"type": "ack", "id": event["id"]})
            await request
            await socket.send_json({"type": "live_dismissed"})
            await socket.send_json({"type": "message", "id": "1", "text": "hi"})
            assert (await socket.receive_json())["type"] == "reply"
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "2", "text": "hi"})
            assert (await socket.receive_json())["type"] == "reply"  # no countdown comes back
    with_api(test, tmp_path)


def test_only_a_typed_command_sends_directly_and_only_once(tmp_path):
    async def answer(socket):
        event = await socket.receive_json()
        await socket.send_json({"type": "answer", "id": event["id"], "result": "x"})
        return event["type"]

    async def test(session):
        body = {"notification": "n1", "text": "ok"}
        async with connect(session) as socket:
            await socket.send_json({"type": "message", "id": "1", "text": "antworte Max: ok usetool"})
            assert await answer(socket) == "send_reply"
            assert await answer(socket) == "ask_to_reply"  # the second one needs confirming
            assert (await socket.receive_json())["type"] == "reply"
            request = asyncio.create_task(session.post(f"{BASE}/reply", json=body, headers=INTERNAL))
            assert await answer(socket) == "ask_to_reply"  # the permission ended with the message
            await request
            await socket.send_json({"type": "message", "id": "2", "text": "was steht da usetool"})
            assert await answer(socket) == "ask_to_reply"  # no command, so outside text can't cause a send
            assert await answer(socket) == "ask_to_reply"
            assert (await socket.receive_json())["type"] == "reply"
    with_api(test, tmp_path)


def test_a_send_permission_cannot_leak_into_an_earlier_turn_still_running(tmp_path):
    release = asyncio.Event()
    started = asyncio.Event()

    async def test(session):
        original = FakeBrain.ask

        async def slow(self, text, image=None):
            if text == "first":
                started.set()
                await release.wait()
                async with ClientSession() as tool:
                    await tool.post(f"{BASE}/reply", json={"notification": "n1", "text": "ok"}, headers=INTERNAL)
                return "done"
            return await original(self, text, image)

        FakeBrain.ask = slow
        try:
            async with connect(session) as socket:
                await socket.send_json({"type": "message", "id": "1", "text": "first"})
                await started.wait()
                await socket.send_json({"type": "message", "id": "2", "text": "antworte Max: ok"})
                await asyncio.sleep(0.05)
                release.set()
                event = await socket.receive_json()
                assert event["type"] == "ask_to_reply"  # the first turn stays without permission
                await socket.send_json({"type": "answer", "id": event["id"], "result": "x"})
                assert (await socket.receive_json())["type"] == "reply"
                assert (await socket.receive_json())["type"] == "reply"
        finally:
            FakeBrain.ask = original
    with_api(test, tmp_path)


def test_send_commands_are_recognised():
    from mitauri.api import asks_to_send
    for command in ("Antworte Max: bin da", "schreib ihm ok", "sag ihr ich komme", "reply to him",
                    "kannst du ihm antworten?", "can you respond", "Ja!", "mach das"):
        assert asks_to_send(command)
    for question in ("Was schreibt Max?", "Was sagt sie?", "lies meine Nachrichten", "wer hat geantwortet",
                     "sag mir, was er schreibt", "tell me what he wrote", "was steht im text",
                     "was soll ich antworten?", "ja das stimmt"):
        assert not asks_to_send(question)


def test_writing_style_lines_can_be_imported_seen_and_deleted(tmp_path, monkeypatch):
    from mitauri import style
    monkeypatch.setattr(style, "_ask_claude", lambda prompt, cwd: "short, lowercase")

    async def test(session):
        lines = json.dumps({"lines": [f"jo {word}" for word in "abcdefghijkl"]}).encode()
        assert (await session.post(f"{BASE}/style-lines", data=lines)).status == 401
        assert (await session.post(f"{BASE}/style-lines", data=lines, headers=signed("POST", "/style-lines", lines))).status == 200
        for _ in range(50):
            notes = await (await session.get(f"{BASE}/style", headers=signed("GET", "/style"))).json()
            if notes["friends"]:
                break
            await asyncio.sleep(0.05)
        assert notes == {"friends": "short, lowercase", "mitauri": ""}
        assert (await session.delete(f"{BASE}/style", headers=signed("DELETE", "/style"))).status == 200
        notes = await (await session.get(f"{BASE}/style", headers=signed("GET", "/style"))).json()
        assert notes == {"friends": "", "mitauri": ""}
    with_api(test, tmp_path)


def test_voice_returns_transcript_and_reply(tmp_path):
    async def test(session):
        response = await session.post(f"{BASE}/voice", data=b"audio", headers=signed("POST", "/voice", b"audio"))
        events = [json.loads(line) for line in (await response.read()).splitlines()]
        assert events[0] == {"transcript": "hallo", "language": "de"}
        spoken = [e for e in events if "sentence" in e]
        assert [e["sentence"] for e in spoken] == ["Um 9.15 gibt es Tee.", "Hier: Ende"]
        assert [base64.b64decode(e["audio"]).decode() for e in spoken] == ["de:Um 9.15 gibt es Tee.", "de:Hier: Ende"]
        assert events[-1] == {"reply": "Um 9.15 gibt es Tee. Hier: https://x.de/a Ende"}
    with_api(test, tmp_path)


def voice_events_for(tmp_path, pieces):
    class Brain(FakeBrain):
        async def stream(self, text, spoken=False):
            for piece in pieces:
                yield piece

    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
        api = Api(Brain(), "secret", tmp_path, FakeSpeech())
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                response = await session.post(f"{BASE}/voice", data=b"a", headers=signed("POST", "/voice", b"a"))
                return [json.loads(line) for line in (await response.read()).splitlines()]
        finally:
            await api.stop()
    return asyncio.run(run())


def test_a_silent_tool_call_is_filled_once_and_stays_out_of_the_reply(tmp_path):
    events = voice_events_for(tmp_path, [TOOL_CALL, TOOL_CALL, "Um neun. "])
    assert [e["sentence"] for e in events if "sentence" in e] == ["Moment.", "Um neun."]
    assert events[-1]["reply"] == "Um neun."


def test_nothing_is_added_when_claude_speaks_before_its_tool(tmp_path):
    events = voice_events_for(tmp_path, ["Ich schau mal. ", TOOL_CALL, "Um neun. "])
    assert [e["sentence"] for e in events if "sentence" in e] == ["Ich schau mal.", "Um neun."]


def test_first_long_sentence_is_cut_at_a_comma_so_the_voice_starts_sooner():
    splitter = SentenceSplitter()
    assert splitter.feed("Jo, 28 Tage. ") == ["Jo, 28 Tage."]
    splitter = SentenceSplitter()
    assert splitter.feed("Morgen wird es in Dortmund regnen, ") == ["Morgen wird es in Dortmund regnen,"]
    assert splitter.feed("also nimm einen Schirm, wirklich mit") == []
    assert splitter.flush() == ["also nimm einen Schirm, wirklich mit"]
    splitter = SentenceSplitter()
    assert splitter.feed("Das kostet ungefähr 3,50 Euro pro Stück") == []


def test_voice_failure_still_sends_the_text_so_the_phone_can_fall_back(tmp_path):
    class NoVoice(FakeSpeech):
        def synthesize(self, text, language):
            raise RuntimeError("voice broke")

    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
        api = Api(CountingBrain(), "secret", tmp_path, NoVoice())
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                response = await session.post(f"{BASE}/voice", data=b"a", headers=signed("POST", "/voice", b"a"))
                events = [json.loads(line) for line in (await response.read()).splitlines()]
        finally:
            await api.stop()
        spoken = [e for e in events if "sentence" in e]
        assert [e["sentence"] for e in spoken] == ["answer"] and "audio" not in spoken[0]
        assert events[-1] == {"reply": "answer"}
    asyncio.run(run())


def test_silence_is_not_heard_as_words():
    assert only_speech(np.zeros(16_000, dtype=np.float32)).size == 0
    assert only_speech(np.zeros(0, dtype=np.float32)).size == 0


def test_short_clips_go_to_whisper_and_longer_ones_to_parakeet(tmp_path, monkeypatch):
    class Segment:
        text = "whisper"

    class Whisper:
        def transcribe(self, audio, **_):
            return [Segment()], None

    class Parakeet:
        def recognize(self, audio, sample_rate):
            return "parakeet"

    speech = Speech(tmp_path)
    speech._whisper, speech._parakeet = Whisper(), Parakeet()
    monkeypatch.setattr("mitauri.speech.only_speech", lambda audio: audio)
    assert speech.transcribe(b"\0\0" * 16_000)[0] == "whisper"
    assert speech.transcribe(b"\0\0" * 48_000)[0] == "parakeet"
    monkeypatch.setattr("mitauri.speech.only_speech", lambda audio: audio[:0])
    assert speech.transcribe(b"\0\0" * 48_000)[0] == ""


def test_whisper_takes_over_when_parakeet_cant_load(tmp_path, monkeypatch):
    class Segment:
        text = " hallo "

    class Whisper:
        def transcribe(self, audio, **_):
            return [Segment()], None

    speech = Speech(tmp_path)
    speech._whisper = Whisper()
    speech._parakeet_failed = False
    monkeypatch.setitem(sys.modules, "onnx_asr", None)  # importing it now fails
    assert speech._load_parakeet() is None
    assert speech.transcribe(b"\0\0" * 100) == ("hallo", "de")


def test_silence_skips_claude(tmp_path):
    async def test(session):
        response = await session.post(f"{BASE}/voice", data=b"", headers=signed("POST", "/voice"))
        events = [json.loads(line) for line in (await response.read()).splitlines()]
        assert events == [{"transcript": "", "language": "de"}, {"reply": ""}]
    with_api(test, tmp_path)


def test_speak_returns_audio_and_limits_length(tmp_path):
    async def test(session):
        body = json.dumps({"text": "hi", "language": "de"}).encode()
        assert (await session.post(f"{BASE}/speak", data=body)).status == 401
        response = await session.post(f"{BASE}/speak", data=body, headers=signed("POST", "/speak", body))
        assert await response.read() == b"de:hi"
        long = json.dumps({"text": "x" * 2001}).encode()
        assert (await session.post(f"{BASE}/speak", data=long, headers=signed("POST", "/speak", long))).status == 413
        bad = b"not json"
        assert (await session.post(f"{BASE}/speak", data=bad, headers=signed("POST", "/speak", bad))).status == 400
    with_api(test, tmp_path)


def test_choose_language_prefers_known_voices_unless_sure():
    assert choose_language("fr", 0.95, {"fr": 0.95}) == "fr"
    assert choose_language("nl", 0.4, {"nl": 0.4, "de": 0.3, "en": 0.1}) == "de"
    assert choose_language("nl", 0.4, {"nl": 0.4, "de": 0.1, "en": 0.3}) == "en"


def test_token_is_private_and_stable(tmp_path):
    token = load_token(tmp_path)
    assert len(token) == 64
    assert load_token(tmp_path) == token
    assert (tmp_path / "internal_token").stat().st_mode & 0o077 == 0


def make_build(folder, code, name="1.0", content=b"apk"):
    folder.mkdir(exist_ok=True)
    (folder / "app.apk").write_bytes(content)
    (folder / "output-metadata.json").write_text(json.dumps(
        {"elements": [{"versionCode": code, "versionName": name, "outputFile": "app.apk"}]}
    ))
    return folder


def test_publishing_only_goes_forward(tmp_path):
    assert app_update.latest(tmp_path) is None
    build = make_build(tmp_path / "build", 5)
    info = app_update.publish(tmp_path, build)
    assert info == {"version_code": 5, "version_name": "1.0", "sha256": hashlib.sha256(b"apk").hexdigest(), "size": 3}
    assert app_update.latest(tmp_path) == info
    for code in (5, 4):
        with pytest.raises(app_update.PublishError):
            app_update.publish(tmp_path, make_build(tmp_path / "build", code))
    with pytest.raises(app_update.PublishError):
        app_update.publish(tmp_path, tmp_path / "nothing-here")
    assert app_update.latest(tmp_path) == info


def test_only_a_paired_phone_gets_the_update(tmp_path):
    app_update.publish(tmp_path, make_build(tmp_path / "build", 7, content=b"the app"))

    async def test(session):
        for path in ("/app-update", "/app-update/apk"):
            assert (await session.get(f"{BASE}{path}")).status == 401
        info = await (await session.get(f"{BASE}/app-update", headers=signed("GET", "/app-update"))).json()
        assert info["version_code"] == 7
        apk = await session.get(f"{BASE}/app-update/apk", headers=signed("GET", "/app-update/apk"))
        assert await apk.read() == b"the app"
    with_api(test, tmp_path)


def test_no_update_published_is_an_empty_answer(tmp_path):
    async def test(session):
        assert await (await session.get(f"{BASE}/app-update", headers=signed("GET", "/app-update"))).json() == {}
        assert (await session.get(f"{BASE}/app-update/apk", headers=signed("GET", "/app-update/apk"))).status == 404
    with_api(test, tmp_path)


def test_update_notice_needs_the_internal_token(tmp_path):
    async def test(session):
        assert (await session.post(f"{BASE}/app-update-notice")).status == 401
        told = await session.post(f"{BASE}/app-update-notice", headers={"Authorization": "Bearer secret"})
        assert await told.json() == {"told_phone": False}
    with_api(test, tmp_path)


def test_a_second_copy_notices_the_port_is_taken():
    from mitauri.__main__ import port_in_use
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        server.listen()
        port = server.getsockname()[1]
        assert port_in_use(port)
    assert not port_in_use(port)


def groq_speech(tmp_path, monkeypatch, handler):
    monkeypatch.setenv("MITAURI_GROQ_API_KEY", "key-value")
    monkeypatch.delenv("MITAURI_SPEECH_ENGINE", raising=False)
    monkeypatch.setattr("mitauri.speech.only_speech", lambda audio: audio)
    speech = Speech(tmp_path)
    speech._groq_client = httpx.Client(transport=httpx.MockTransport(handler))
    return speech


def test_groq_hears_and_its_key_leaves_the_environment(tmp_path, monkeypatch):
    seen = {}

    def handler(request):
        seen["auth"], seen["body"] = request.headers["authorization"], request.content
        return httpx.Response(200, json={"text": " hallo "})

    speech = groq_speech(tmp_path, monkeypatch, handler)
    assert "MITAURI_GROQ_API_KEY" not in os.environ
    assert speech.transcribe(b"\0\0" * 100) == ("hallo", "de")
    assert seen["auth"] == "Bearer key-value"
    assert b'name="language"' in seen["body"] and b"RIFF" in seen["body"]


def test_silence_never_goes_to_groq(tmp_path, monkeypatch):
    def handler(request):
        raise AssertionError("nothing should be sent")

    speech = groq_speech(tmp_path, monkeypatch, handler)
    monkeypatch.setattr("mitauri.speech.only_speech", lambda audio: audio[:0])
    assert speech.transcribe(b"\0\0" * 100)[0] == ""


@pytest.mark.parametrize("failure", [
    lambda request: httpx.Response(429, json={"error": "secret words"}),
    lambda request: httpx.Response(200, text="not json"),
    lambda request: (_ for _ in ()).throw(httpx.ConnectTimeout("slow")),
])
def test_local_recognition_takes_over_when_groq_fails_then_groq_rests(tmp_path, monkeypatch, caplog, failure):
    class Segment:
        text = "lokal"

    class Whisper:
        def transcribe(self, audio, **_):
            return [Segment()], None

    calls = []

    def handler(request):
        calls.append(1)
        return failure(request)

    speech = groq_speech(tmp_path, monkeypatch, handler)
    speech._whisper = Whisper()
    assert speech.transcribe(b"\0\0" * 100)[0] == "lokal"
    assert speech.transcribe(b"\0\0" * 100)[0] == "lokal"
    assert len(calls) == 1
    assert "key-value" not in caplog.text and "secret words" not in caplog.text


def test_naming_a_local_engine_means_groq_gets_nothing(tmp_path, monkeypatch):
    monkeypatch.setenv("MITAURI_GROQ_API_KEY", "key-value")
    monkeypatch.setenv("MITAURI_SPEECH_ENGINE", "whisper")
    assert Speech(tmp_path)._groq_key == ""


class CountingSpeech(FakeSpeech):
    def __init__(self):
        self.heard = []

    def transcribe(self, pcm):
        self.heard.append(pcm)
        return f"heard {len(pcm)}", "de"


def with_counting_speech(test, tmp_path):
    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
        speech, brain = CountingSpeech(), CountingBrain()
        api = Api(brain, "secret", tmp_path, speech)
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                async def post(path, body):
                    response = await session.post(f"{BASE}{path}", data=body, headers=signed("POST", path, body))
                    return response.status, await response.read()
                await test(post, speech, brain)
        finally:
            await api.stop()
    asyncio.run(run())


def first_event(body):
    return json.loads(body.splitlines()[0])


def test_prepared_hearing_is_used_for_the_same_audio_plus_a_quiet_end(tmp_path):
    async def test(post, speech, brain):
        assert (await post("/voice-prepare", b"a" * 1000))[0] == 202
        _, body = await post("/voice", b"a" * 1000 + b"\0" * 500)
        assert first_event(body)["transcript"] == "heard 1000"
        assert speech.heard == [b"a" * 1000]
        assert brain.asked == 1
    with_counting_speech(test, tmp_path)


@pytest.mark.parametrize("final", [
    b"b" * 1000 + b"\0" * 500,  # different audio
    b"a" * 900,  # shorter than what was prepared
    b"a" * 1000 + b"\0" * 32_001,  # kept talking for too long afterwards
])
def test_prepared_hearing_is_ignored_when_the_recording_differs(tmp_path, final):
    async def test(post, speech, brain):
        await post("/voice-prepare", b"a" * 1000)
        _, body = await post("/voice", final)
        assert first_event(body)["transcript"] == f"heard {len(final)}"
    with_counting_speech(test, tmp_path)


def test_prepared_hearing_is_only_good_for_one_recording(tmp_path):
    async def test(post, speech, brain):
        await post("/voice-prepare", b"a" * 1000)
        await post("/voice", b"a" * 1000)
        await post("/voice", b"a" * 1000)
        assert len(speech.heard) == 2
    with_counting_speech(test, tmp_path)


def test_preparing_never_acts_on_what_it_heard(tmp_path):
    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")

        class Door(FakeSpeech):
            def transcribe(self, pcm):
                return "öffne die tür", "de"
        brain = CountingBrain()
        api = Api(brain, "secret", tmp_path, Door())
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                response = await session.post(f"{BASE}/voice-prepare", data=b"a", headers=signed("POST", "/voice-prepare", b"a"))
                assert response.status == 202 and await response.read() == b""
                await asyncio.sleep(0.05)
        finally:
            await api.stop()
        assert brain.asked == 0
        assert "door_command" not in ((tmp_path / "audit.log").read_text() if (tmp_path / "audit.log").exists() else "")
    asyncio.run(run())


def test_preparing_needs_the_phones_signature_and_is_rate_limited(tmp_path):
    async def test(post, speech, brain):
        for index in range(api_module.MAX_PREPARES_PER_MINUTE + 3):
            assert (await post("/voice-prepare", bytes([index]) * 10))[0] == 202
        await asyncio.sleep(0.05)
        assert len(speech.heard) == api_module.MAX_PREPARES_PER_MINUTE
    with_counting_speech(test, tmp_path)

    async def unsigned(session):
        assert (await session.post(f"{BASE}/voice-prepare", data=b"a")).status == 401
    with_api(unsigned, tmp_path)


def test_a_slow_groq_answer_gets_raced_and_the_first_answer_wins(tmp_path, monkeypatch):
    calls = []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            time.sleep(0.5)
            return httpx.Response(200, json={"text": "slow"})
        return httpx.Response(200, json={"text": "fast"})

    monkeypatch.setattr("mitauri.speech.GROQ_SECOND_TRY_SECONDS", 0.05)
    speech = groq_speech(tmp_path, monkeypatch, handler)
    assert speech.transcribe(b"\0\0" * 100)[0] == "fast"
    assert len(calls) == 2


def test_groq_that_never_answers_gives_way_to_local_hearing(tmp_path, monkeypatch):
    class Segment:
        text = "lokal"

    class Whisper:
        def transcribe(self, audio, **_):
            return [Segment()], None

    def handler(request):
        time.sleep(0.4)
        return httpx.Response(200, json={"text": "too late"})

    monkeypatch.setattr("mitauri.speech.GROQ_SECOND_TRY_SECONDS", 0.02)
    monkeypatch.setattr("mitauri.speech.GROQ_TIMEOUT_SECONDS", 0.05)
    speech = groq_speech(tmp_path, monkeypatch, handler)
    speech._whisper = Whisper()
    assert speech.transcribe(b"\0\0" * 100)[0] == "lokal"
    assert speech._groq_rests_until > time.monotonic()


def test_a_single_failed_try_still_lets_the_other_one_answer(tmp_path, monkeypatch):
    calls = []

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            time.sleep(0.1)
            return httpx.Response(500)
        time.sleep(0.2)
        return httpx.Response(200, json={"text": "fine"})

    monkeypatch.setattr("mitauri.speech.GROQ_SECOND_TRY_SECONDS", 0.02)
    speech = groq_speech(tmp_path, monkeypatch, handler)
    assert speech.transcribe(b"\0\0" * 100)[0] == "fine"
    assert speech._groq_rests_until == 0.0


def test_short_phrases_are_only_generated_once(tmp_path):
    made = []

    class Voice:
        def synthesize_wav(self, text, output):
            made.append(text)
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(22_050)
            output.writeframes(b"\0\0")

    speech = Speech(tmp_path)
    speech._voices = {"de": Voice(), "en": Voice()}
    first = speech.synthesize("Moment.", "de")
    assert speech.synthesize("Moment.", "de") == first
    assert made == ["Moment."]
    speech.synthesize("Moment.", "en")
    long = "x" * 61
    speech.synthesize(long, "de")
    speech.synthesize(long, "de")
    assert made == ["Moment.", "Moment.", long, long]
    for index in range(70):
        speech.synthesize(str(index), "de")
    assert len(speech._voice_cache) == 64


@pytest.mark.parametrize("text, kind, seconds", [
    ("Wie spät ist es?", "time", 0),
    ("Hey Mitauri, wie viel Uhr ist es bitte?", "time", 0),
    ("What time is it?", "time", 0),
    ("Welcher Tag ist heute?", "date", 0),
    ("Danke!", "thanks", 0),
    ("Stell einen Timer auf 5 Minuten.", "timer", 300),
    ("Stell mir mal einen Timer für fünf Minuten", "timer", 300),
    ("Starte einen 10-Minuten-Timer", "timer", 600),
    ("Timer 30 Sekunden", "timer", 30),
    ("Timer eine Stunde", "timer", 3600),
    ("Stell einen Timer auf eine halbe Stunde", "timer", 1800),
    ("Set a timer for ten minutes", "timer", 600),
    ("Timer aus", "timer_off", 0),
    ("Lösch den Timer.", "timer_off", 0),
    ("Erinner mich in 10 Minuten an den Ofen.", "reminder", 600),
    ("Erinnere mich bitte in einer Stunde daran, den Müll rauszubringen", "reminder", 3600),
    ("Remind me in five minutes to call Max", "reminder", 300),
])
def test_instant_requests_are_recognised(text, kind, seconds):
    found = instant.parse(text)
    assert found and found.kind == kind and found.seconds == seconds


@pytest.mark.parametrize("text", [
    "Wie spät ist es in Tokio?",
    "Wie spät ist es und wie wird das Wetter?",
    "Danke, aber was ist mit morgen?",
    "Fünf Minuten",
    "Wie lange läuft der Timer noch?",
    "Stell den Timer auf später",
    "Erinner mich morgen an den Zahnarzt",
    "Erinner mich in 0 Minuten an den Ofen",
    "Erinner mich in 9999 Stunden an den Ofen",
    "Öffne die Tür",
    "",
])
def test_everything_else_goes_to_claude(text):
    assert instant.parse(text) is None


def test_clock_replies_and_lengths():
    now = datetime(2026, 11, 1, 18, 42)
    assert instant.clock_reply("time", "de", now) == "Es ist 18 Uhr 42."
    assert instant.clock_reply("time", "de", now.replace(minute=0)) == "Es ist 18 Uhr."
    assert instant.clock_reply("date", "de", now) == "Heute ist Sonntag, der 1. November."
    assert instant.duration_words(300, "de") == "5 Minuten"
    assert instant.duration_words(3600, "de") == "eine Stunde"
    assert instant.duration_words(90, "en") == "90 seconds"


class SayingSpeech(FakeSpeech):
    said = "Wie spät ist es?"

    def transcribe(self, pcm):
        return self.said, "de"


class NoteBrain(CountingBrain):
    def __init__(self):
        super().__init__()
        self.seen = []

    async def stream(self, text, spoken=False):
        self.asked += 1
        self.seen.append(text)
        yield "answer"


def with_saying(test, tmp_path):
    async def run():
        devices = Devices(tmp_path)
        assert devices.pair(devices.new_code(), PUBLIC_B64, "test")
        speech, brain = SayingSpeech(), NoteBrain()
        api = Api(brain, "secret", tmp_path, speech)
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                async def say(text):
                    speech.said = text
                    response = await session.post(f"{BASE}/voice", data=b"a", headers=signed("POST", "/voice", b"a"))
                    return [json.loads(line) for line in (await response.read()).splitlines()]
                await test(say, brain, api)
        finally:
            await api.stop()
    asyncio.run(run())


def test_instant_answers_skip_claude_but_claude_hears_about_them_later(tmp_path):
    async def test(say, brain, api):
        events = await say("Wie spät ist es?")
        assert brain.asked == 0
        spoken = [e for e in events if "sentence" in e]
        assert len(spoken) == 1 and spoken[0]["sentence"].startswith("Es ist") and spoken[0]["audio"]
        assert events[-1]["reply"] == spoken[0]["sentence"]
        await say("Und in Tokio?")
        assert "Wie spät ist es?" in brain.seen[0] and brain.seen[0].endswith("Und in Tokio?")
        await say("Und in Paris?")
        assert brain.seen[1] == "Und in Paris?"
    with_saying(test, tmp_path)


def test_instant_reminder_is_saved_for_the_right_time(tmp_path):
    async def test(say, brain, api):
        events = await say("Erinner mich in 10 Minuten an den Ofen")
        assert events[-1]["reply"] == "Alles klar, in 10 Minuten."
        pending = Reminders(tmp_path / "reminders.db").pending()
        assert len(pending) == 1 and pending[0][1] == "den Ofen"
        assert brain.asked == 0
    with_saying(test, tmp_path)


def test_timer_uses_the_island_and_a_failure_goes_to_claude(tmp_path, monkeypatch):
    from mitauri.modules import island
    started = []
    monkeypatch.setattr(island, "start_countdown", lambda title, minutes: started.append(minutes) or {"ok": True})

    async def test(say, brain, api):
        events = await say("Stell einen Timer auf 5 Minuten")
        assert started == [5.0] and brain.asked == 0
        assert events[-1]["reply"] == "Timer läuft, 5 Minuten."
        monkeypatch.setattr(island, "start_countdown", lambda title, minutes: {"error": "phone gone"})
        events = await say("Stell einen Timer auf 5 Minuten")
        assert brain.asked == 1 and events[-1]["reply"] == "answer"
    with_saying(test, tmp_path)


def test_switching_off_the_island_sends_timers_to_claude(tmp_path):
    modules.set_enabled(tmp_path, "island", False)

    async def test(say, brain, api):
        await say("Stell einen Timer auf 5 Minuten")
        assert brain.asked == 1
    with_saying(test, tmp_path)


def test_door_phrases_are_never_instant_and_still_go_to_the_phone(tmp_path):
    async def test(say, brain, api):
        events = await say("Öffne die Tür")
        assert {"door": True} in events and brain.asked == 0
    with_saying(test, tmp_path)


def test_tests_cannot_reach_the_real_data_folder():
    from mitauri.config import data_dir
    assert data_dir() != Path("~/.local/share/mitauri").expanduser()


def test_dashboard_shows_nothing_without_a_login(tmp_path):
    async def test(session):
        page = await session.get(f"{BASE}/dashboard")
        assert page.status == 200
        assert "script-src 'self'" in page.headers["Content-Security-Policy"]
        assert (await session.get(f"{BASE}/dashboard/page.js")).status == 200
        assert (await session.get(f"{BASE}/dashboard/status")).status == 401
        assert (await session.post(f"{BASE}/dashboard/logout")).status == 401
        # An old cookie isn't a lockout, or the page would lock out a phone on the same address.
        for _ in range(12):
            refused = await session.get(f"{BASE}/dashboard/status", headers={"Cookie": f"{dashboard.COOKIE}=old"})
            assert refused.status == 401
        assert (await session.get(f"{BASE}/dashboard")).status == 200
    with_api(test, tmp_path)


def test_dashboard_login_works_once_and_marks_the_cookie(tmp_path):
    code = dashboard.Access(tmp_path).new_code()

    async def test(session):
        assert (await session.post(f"{BASE}/dashboard/login", json={"code": "wrong"})).status == 401
        login = await session.post(f"{BASE}/dashboard/login", json={"code": code})
        assert login.status == 200
        cookie = login.cookies[dashboard.COOKIE]
        assert cookie["httponly"] and cookie["samesite"] == "Strict" and cookie["path"] == "/dashboard"
        header = {"Cookie": f"{dashboard.COOKIE}={cookie.value}"}
        assert (await session.post(f"{BASE}/dashboard/login", json={"code": code})).status == 401
        assert (await session.get(f"{BASE}/dashboard/status", headers=header)).status == 200
        assert (await session.post(f"{BASE}/dashboard/logout", headers=header)).status == 200
        assert (await session.get(f"{BASE}/dashboard/status", headers=header)).status == 401
    with_api(test, tmp_path)


def test_dashboard_session_opens_nothing_else(tmp_path):
    token = dashboard.Access(tmp_path).login(dashboard.Access(tmp_path).new_code())

    async def test(session):
        header = {"Cookie": f"{dashboard.COOKIE}={token}"}
        for method, path in (("POST", "/door"), ("GET", "/location"), ("GET", "/memories"), ("GET", "/presence-key")):
            assert (await session.request(method, f"{BASE}{path}", headers=header)).status == 401
    with_api(test, tmp_path)


def test_dashboard_code_dies_after_wrong_tries_and_expiry(tmp_path):
    access = dashboard.Access(tmp_path)
    code = access.new_code()
    for _ in range(dashboard.CODE_ATTEMPTS):
        assert access.login("wrong") is None
    assert access.login(code) is None
    code = access.new_code()
    state = json.loads((tmp_path / "dashboard_code.json").read_text())
    assert code not in json.dumps(state)
    (tmp_path / "dashboard_code.json").write_text(json.dumps({**state, "expires": 0}))
    assert access.login(code) is None
    token = access.login(access.new_code())
    assert token not in (tmp_path / "dashboard_sessions.json").read_text()
    assert access.is_valid(token)
    access.revoke_all()
    assert not access.is_valid(token)


class FakeDashboardPhone:
    closed = False

    def __init__(self, api):
        self.api = api
        self.asked = []

    async def send_json(self, message):
        self.asked.append(message["type"])
        answer = (
            {"latitude": 48.18123, "longitude": 11.71654, "accuracy": 12.0}
            if message["type"] == "get_location" else {"linked": True, "connected": True, "battery_percent": 11}
        )
        self.api._pending[message["id"]].set_result({"type": "x", "id": message["id"], **answer})


def test_dashboard_asks_the_phone_while_viewed_and_forgets_after(tmp_path, monkeypatch):
    async def run():
        api = Api(FakeBrain(), "secret", tmp_path, FakeSpeech(), Memory(tmp_path / "memory.db"))
        api._phone = FakeDashboardPhone(api)
        monkeypatch.setattr(api_module, "DASHBOARD_REFRESH_SECONDS", 0.01)
        monkeypatch.setattr(api_module, "DASHBOARD_VIEWER_GONE_SECONDS", 0.05)
        token = dashboard.Access(tmp_path).login(dashboard.Access(tmp_path).new_code())
        header = {"Cookie": f"{dashboard.COOKIE}={token}"}
        await api.start(["127.0.0.1"], PORT)
        try:
            async with ClientSession() as session:
                await asyncio.sleep(0.03)
                assert api._phone.asked == []  # nobody is looking yet
                await (await session.get(f"{BASE}/dashboard/status", headers=header)).read()
                await asyncio.sleep(0.005)
                status = await (await session.get(f"{BASE}/dashboard/status", headers=header)).json()
                assert status["phone"]["location"]["latitude"] == 48.18123
                assert status["watch"]["battery_percent"] == 11
                assert "The watch battery is at 11%." in status["issues"]
                await asyncio.sleep(0.15)
                assert api._phone_facts == {}
                seen = len(api._phone.asked)
                await asyncio.sleep(0.05)
                assert len(api._phone.asked) == seen  # it stopped asking
        finally:
            await api.stop()
        saved = "".join(p.read_text(errors="replace") for p in tmp_path.iterdir() if p.suffix in (".log", ".json"))
        assert "48.18" not in saved  # no location history
    asyncio.run(run())


def test_dashboard_lists_problems_without_exception_text(tmp_path):
    record(tmp_path, "auth_rejected", address="100.1.2.3")
    record(tmp_path, "tool", name="get_weather", ok=False)
    record(tmp_path, "tool", name="get_time", ok=True)
    (tmp_path / "claude-errors.log").write_text("first\nusage limit reached\n")
    feed = dashboard.ErrorFeed()
    logger = logging.getLogger("mitauri.test_feed")
    logger.addHandler(feed)
    try:
        raise ValueError("private message text")
    except ValueError:
        logger.exception("Voice failed")
    finally:
        logger.removeHandler(feed)
    texts = [problem["text"] for problem in dashboard.recent_problems(tmp_path, feed, time.time())]
    assert "Refused a request from 100.1.2.3" in texts and "Tool get_weather failed" in texts
    assert "usage limit reached" in texts and "Voice failed (ValueError)" in texts
    assert not any("get_time" in text or "private" in text for text in texts)
    later = dashboard.audit_problems(tmp_path, time.time() + 2 * dashboard.PROBLEM_WINDOW_SECONDS)
    assert later == []


def test_dashboard_attention_ignores_away_but_not_silent_sensors():
    status = {
        "phone": {"connected": True, "location": {}}, "watch": {"linked": False},
        "server": {"disk_free_percent": 50, "discord": True},
        "sensors": {"home": {"phone": {"state": "away"}, "laptop": {"state": "near"}}},
    }
    assert dashboard.needs_attention(status) == []
    status["sensors"] = {"bag": {"laptop": {"state": "not_connected"}}}  # a laptop that's off is normal
    assert dashboard.needs_attention(status) == []
    status["sensors"] = {"home": {"state": "unknown", "reason": "This sensor isn't reporting."}}
    status["phone"]["connected"] = False
    assert dashboard.needs_attention(status) == [
        "The phone app isn't connected.", "Sensor home: This sensor isn't reporting.",
    ]


def test_dashboard_page_writes_outside_text_only_as_text():
    script = (Path(dashboard.__file__).parent / "page.js").read_text()
    assert "innerHTML" not in script and "insertAdjacentHTML" not in script and "document.write" not in script
