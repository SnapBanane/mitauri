import asyncio
import base64
import json
import secrets
import time
from pathlib import Path

from aiohttp import ClientSession
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from mitauri import api as api_module
from mitauri import tools
from mitauri.api import Api, is_door_command, load_token
from mitauri.auth import Devices, device_id, signed_message
from mitauri.brain import BrainError
from mitauri.memory import Memory
from mitauri.speech import choose_language

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
        assert [e["sentence"] for e in events if "sentence" in e] == ["Um 9.15 gibt es Tee.", "Hier: Ende"]
        assert events[-1] == {"reply": "Um 9.15 gibt es Tee. Hier: https://x.de/a Ende"}
    with_api(test, tmp_path)


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
