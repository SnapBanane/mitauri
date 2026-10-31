"""The connection to the phone app. Every request is signed, because a tailnet can include other people."""

import asyncio
import base64
import hmac
import json
import logging
import re
import time
import uuid

from aiohttp import WSMsgType, web

from . import modules, style, wake
from .audit import record
from .auth import Devices, FailureLimiter
from .brain import BrainError
from .presence import load_key
from .style import StyleLearner
from .config import API_PORT, LOCATION_TIMEOUT_SECONDS, load_token  # noqa: F401

PORT = API_PORT
LINK = re.compile(r"https?://\S+")
SENTENCE = re.compile(r"(.+?(?:[.!?…]+(?=\s)|\n))", re.DOTALL)
MAX_RECORDING_BYTES = 4 * 1024 * 1024  # two minutes of 16 kHz mono audio, or a large photo
DELIVERY_TIMEOUT_SECONDS = 15
STOP_COMMANDS = {"stop", "stopp", "halt", "aus", "ruhe", "alarm aus", "wecker aus", "stop alarm", "stopp den wecker", "ich bin wach"}
DOOR_COMMANDS = {
    "öffne die tür", "öffne tür", "tür öffnen", "die tür öffnen", "tür auf", "mach die tür auf", "mach tür auf",
    "open the door", "open door",
}
MAX_MESSAGE_CHARS = 20_000
MAX_STYLE_LINES = 2_000
SEND_COMMAND = re.compile(
    r"(?i)\b(antworte|antwort|schreib|schreibe|schick|schicke|sende|send|reply|respond|(?:sag|sage|tell)(?! mir| me))\b"
)
MAX_SPEECH_CHARS = 2_000
LOOPBACK = ("127.0.0.1", "::1")
DEVICE = web.RequestKey("device", str)
INTERNAL_PATHS = ("/location", "/door", "/notifications", "/reply", "/place-reminders", "/presence", "/alarm-notice", "/island")
MAX_REPLY_CHARS = 1_000
JPEG_START = b"\xff\xd8\xff"

log = logging.getLogger(__name__)


class _PhoneStream:
    """Writes to the phone, and ignores the phone having disconnected."""

    def __init__(self, response: web.StreamResponse):
        self._response = response
        self._open = True

    async def send(self, event: dict) -> None:
        if self._open:
            try:
                await self._response.write((json.dumps(event) + "\n").encode())
            except ConnectionError:
                self._open = False

    async def finish(self) -> None:
        if self._open:
            try:
                await self._response.write_eof()
            except ConnectionError:
                pass


def is_door_command(text: str) -> bool:
    """Only these exact phrases count. Opening is logged under the user's name, so nothing is guessed."""
    words = re.sub(r"[^\wäöüß ]", "", text.lower()).split()
    words = [w for w in words if w not in ("mitauri", "bitte", "hey", "please")]
    return " ".join(words) in DOOR_COMMANDS


def asks_to_send(text: str) -> bool:
    """Whether the user's own words tell Mitauri to send something. "Was schreibt Max?" doesn't, and only commands do."""
    return SEND_COMMAND.search(text) is not None


def is_stop_command(text: str) -> bool:
    return re.sub(r"[^\wäöüß ]", "", text.lower()).strip() in STOP_COMMANDS


def speakable(text: str) -> str:
    """Removes what shouldn't be read aloud: links and Markdown symbols."""
    return re.sub(r"\s+", " ", re.sub(r"[*_#`]", "", LINK.sub("", text))).strip()


class SentenceSplitter:
    """Cuts streamed text into sentences. A sentence only ends at whitespace, so 9.15 stays whole."""

    def __init__(self):
        self._buffer = ""

    def feed(self, text: str) -> list[str]:
        self._buffer += text
        sentences = []
        while match := SENTENCE.match(self._buffer):
            sentences.append(match.group(1))
            self._buffer = self._buffer[match.end():]
        return [s for s in map(speakable, sentences) if s]

    def flush(self) -> list[str]:
        rest, self._buffer = speakable(self._buffer), ""
        return [rest] if rest else []


class Api:
    def __init__(self, brain, internal_token: str, data_dir, speech=None, memory=None, presence=None, history=None):
        self._history = history
        self._presence = presence
        self._live = None
        self._brain = brain
        self._internal_token = internal_token
        self._data_dir = data_dir
        self._devices = Devices(data_dir)
        self._limiter = FailureLimiter()
        self._speech = speech
        self._memory = memory
        self._style = StyleLearner(data_dir)
        self._may_send_once = False
        self._phone: web.WebSocketResponse | None = None
        self._pending: dict[str, asyncio.Future] = {}
        self._tasks: set[asyncio.Task] = set()
        self._runner: web.AppRunner | None = None

    async def start(self, hosts: list[str], port: int = PORT) -> None:
        app = web.Application(middlewares=[self._guard], client_max_size=MAX_RECORDING_BYTES)
        app.router.add_get("/ws", self._on_phone)
        app.router.add_post("/pair", self._on_pair)
        app.router.add_delete("/device", self._on_forget_device)
        app.router.add_get("/location", self._on_location_request)
        app.router.add_post("/door", self._on_open_door)
        app.router.add_get("/notifications", self._on_read_notifications)
        app.router.add_post("/reply", self._on_ask_to_reply)
        app.router.add_get("/style", self._on_get_style)
        app.router.add_delete("/style", self._on_clear_style)
        app.router.add_post("/style-lines", self._on_style_lines)
        app.router.add_post("/place-reminders", self._on_place_reminder)
        app.router.add_post("/alarm-notice", self._on_alarm_notice)
        app.router.add_post("/island", self._on_island)
        app.router.add_get("/presence", self._on_presence)
        app.router.add_get("/presence-key", self._on_presence_key)
        app.router.add_post("/photo", self._on_photo)
        app.router.add_post("/voice", self._on_voice)
        app.router.add_post("/speak", self._on_speak)
        app.router.add_get("/memories", self._on_list_memories)
        app.router.add_delete("/memories/{id:[0-9]+}", self._on_delete_memory)
        app.router.add_post("/conversation/reset", self._on_reset_conversation)
        app.router.add_delete("/long-term-memory", self._on_clear_history)
        app.router.add_get("/modules", self._on_list_modules)
        app.router.add_post("/modules", self._on_set_module)
        self._runner = web.AppRunner(app, access_log=None)  # who asked what is nobody's log's business
        await self._runner.setup()
        for host in hosts:
            await web.TCPSite(self._runner, host, port).start()
        log.info("Phone connection listening on %s, port %s", ", ".join(hosts), port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()

    @web.middleware
    async def _guard(self, request: web.Request, handler):
        address = request.remote or "unknown"
        if self._limiter.is_locked(address):
            raise web.HTTPTooManyRequests()
        if request.path == "/pair":
            return await handler(request)
        if request.path in INTERNAL_PATHS:
            # Only Mitauri's own tool process, on this machine, may ask the phone to do things.
            supplied = request.headers.get("Authorization", "")
            if address in LOOPBACK and hmac.compare_digest(supplied.encode(), f"Bearer {self._internal_token}".encode()):
                return await handler(request)
            self._reject(address)
        device = self._devices.verify(
            request.headers.get("X-Mitauri-Auth", ""), request.method, request.path, await request.read()
        )
        if device is None:
            self._reject(address)
        request[DEVICE] = device
        return await handler(request)

    def _reject(self, address: str) -> None:
        self._limiter.record_failure(address)
        record(self._data_dir, "auth_rejected", address=address)
        raise web.HTTPUnauthorized()

    async def _on_pair(self, request: web.Request) -> web.Response:
        address = request.remote or "unknown"
        try:
            data = await request.json()
            device = self._devices.pair(data["code"], data["public_key"], str(data.get("name", "phone")))
        except (ValueError, KeyError, TypeError, AttributeError):
            device = None
        if device is None:
            self._reject(address)
        record(self._data_dir, "device_paired", device=device, address=address)
        return web.json_response({"device": device})

    async def _on_forget_device(self, request: web.Request) -> web.Response:
        self._devices.remove(request[DEVICE])
        record(self._data_dir, "device_removed", device=request[DEVICE])
        return web.json_response({"removed": True})

    async def _on_list_memories(self, _: web.Request) -> web.Response:
        return web.json_response([{"id": i, "text": t} for i, t in self._memory.all()])

    async def _on_delete_memory(self, request: web.Request) -> web.Response:
        removed = self._memory.remove(int(request.match_info["id"]))
        record(self._data_dir, "memory_deleted", removed=removed)
        return web.json_response({"removed": removed})

    async def _on_list_modules(self, _: web.Request) -> web.Response:
        return web.json_response(modules.listing(self._data_dir))

    async def _on_set_module(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            name, enabled = str(data["name"]), data["enabled"]
            if not isinstance(enabled, bool):
                raise TypeError
            modules.set_enabled(self._data_dir, name, enabled)
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        record(self._data_dir, "module_changed", name=name, enabled=enabled)
        return web.json_response(modules.listing(self._data_dir))

    async def _on_clear_history(self, _: web.Request) -> web.Response:
        if self._history:
            await asyncio.to_thread(self._history.clear)
        record(self._data_dir, "long_term_memory_cleared")
        return web.json_response({"cleared": True})

    def _remember(self, you: str, reply: str) -> None:
        if self._history and reply and modules.is_enabled(self._data_dir, "history"):
            task = asyncio.create_task(asyncio.to_thread(self._history.add, you, reply))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)

    async def _on_reset_conversation(self, _: web.Request) -> web.Response:
        self._brain.reset()
        record(self._data_dir, "conversation_reset")
        return web.json_response({"reset": True})

    async def _on_phone(self, request: web.Request) -> web.WebSocketResponse:
        socket = web.WebSocketResponse(max_msg_size=MAX_MESSAGE_CHARS * 4)  # the app pings rarely, to save battery
        await socket.prepare(request)
        self._phone = socket
        if self._live and self._live["until"] > time.time():
            await socket.send_json({
                "type": "live_show", "id": "resend", "text": self._live["title"],
                "until": self._live["until"], "detail": self._live["detail"],
            })
        log.info("Phone connected")
        async for message in socket:
            if message.type != WSMsgType.TEXT:
                continue
            try:
                self._on_phone_message(socket, json.loads(message.data))
            except (ValueError, KeyError, TypeError):
                log.warning("Ignored a malformed message from the phone")
        if self._phone is socket:
            self._phone = None
        log.info("Phone disconnected")
        return socket

    def _on_phone_message(self, socket: web.WebSocketResponse, data: dict) -> None:
        if data["type"] == "message":
            if len(data["text"]) > MAX_MESSAGE_CHARS:
                raise ValueError("message too long")
            if wake.is_ringing(self._data_dir) and is_stop_command(data["text"]):
                wake.request_stop(self._data_dir)
                data = {**data, "text": ""}
                asyncio.create_task(socket.send_json({"type": "reply", "id": data["id"], "text": "Wecker aus. Guten Morgen!"}))
                return
            # A separate task, because answering may need this socket to fetch the location.
            task = asyncio.create_task(self._answer(socket, data["id"], data["text"]))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
        elif data["type"] == "stop_alarm":
            wake.request_stop(self._data_dir)
        elif data["type"] == "live_dismissed":
            self._live = None
        elif data["type"] == "style_lines":
            self._keep_style_lines(style.FRIENDS, [str(line) for line in data["lines"]][:MAX_STYLE_LINES])
        elif data["type"] in ("location", "ack", "answer"):
            future = self._pending.get(data["id"])
            if future and not future.done():
                future.set_result(data)

    async def _answer(self, socket: web.WebSocketResponse, message_id: str, text: str) -> None:
        if is_door_command(text) and modules.is_enabled(self._data_dir, "door"):
            # Goes straight to the phone. Claude isn't involved, so nothing it reads can trigger this.
            record(self._data_dir, "door_command", via="text")
            if not socket.closed:
                await socket.send_json({"type": "door_command"})
                await socket.send_json({"type": "reply", "id": message_id, "text": "Tür wird geöffnet."})
            return
        self._keep_style_lines(style.MITAURI, [text])
        # Decided here from the typed words, which outside text can't change. It's good for one send, for this message.
        self._may_send_once = asks_to_send(text)
        try:
            reply = await self._brain.ask(text)
            self._remember(text, reply)
        except BrainError as error:
            reply = f"Something went wrong: {error}"
        finally:
            self._may_send_once = False
        if not socket.closed:
            await socket.send_json({"type": "reply", "id": message_id, "text": reply})

    def _keep_style_lines(self, source: str, lines: list[str], learn_now: bool = False) -> None:
        self._style.add(source, lines)
        if learn_now or self._style.is_due():
            task = asyncio.create_task(asyncio.to_thread(self._style.learn))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)

    async def _on_get_style(self, _: web.Request) -> web.Response:
        return web.json_response(style.notes(self._data_dir))

    async def _on_clear_style(self, _: web.Request) -> web.Response:
        self._style.clear()
        record(self._data_dir, "style_cleared")
        return web.json_response({"cleared": True})

    async def _on_style_lines(self, request: web.Request) -> web.Response:
        """Lines from a chat the user chose to share. The phone has already left out everyone else's."""
        try:
            data = await request.json()
            lines = [str(line) for line in data["lines"]]
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        self._keep_style_lines(style.FRIENDS, lines[-MAX_STYLE_LINES:], learn_now=True)
        record(self._data_dir, "style_imported", lines=len(lines))
        return web.json_response({"received": len(lines)})

    async def _on_photo(self, request: web.Request) -> web.Response:
        """The photo is only passed on to Claude, never saved here."""
        try:
            data = await request.json()
            text, encoded = str(data.get("text", "")), str(data["image"])
            image = base64.b64decode(encoded, validate=True)
        except (ValueError, KeyError, TypeError, AttributeError):
            raise web.HTTPBadRequest() from None
        if not image.startswith(JPEG_START) or len(text) > MAX_MESSAGE_CHARS:
            raise web.HTTPBadRequest()
        record(self._data_dir, "photo_sent")
        try:
            reply = await self._brain.ask(text or "Was siehst du auf diesem Bild?", image=("image/jpeg", encoded))
        except BrainError as error:
            reply = f"Something went wrong: {error}"
        return web.json_response({"reply": reply})

    async def deliver(self, kind: str, text: str, **fields) -> bool:
        """Sends a call or reminder to the phone. Returns whether the phone confirmed it arrived."""
        phone = self._phone
        if phone is None or phone.closed:
            return False
        delivery_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self._pending[delivery_id] = future
        try:
            await phone.send_json({"type": kind, "id": delivery_id, "text": text, **fields})
            await asyncio.wait_for(future, DELIVERY_TIMEOUT_SECONDS)
            return True
        except (TimeoutError, ConnectionError):
            return False
        finally:
            del self._pending[delivery_id]

    async def _on_voice(self, request: web.Request) -> web.StreamResponse:
        """Streams one JSON line per event, so the phone can speak before the reply is complete."""
        # Speech can't send directly until voices are recognised, so anyone nearby can't send in the user's name.
        self._may_send_once = False
        transcript, language = await asyncio.to_thread(self._speech.transcribe, await request.read())
        response = web.StreamResponse(headers={"Content-Type": "application/x-ndjson"})
        await response.prepare(request)
        phone = _PhoneStream(response)
        await phone.send({"transcript": transcript, "language": language})
        reply = ""
        if wake.is_ringing(self._data_dir) and is_stop_command(transcript):
            wake.request_stop(self._data_dir)
            reply = "Wecker aus."
        elif is_door_command(transcript) and modules.is_enabled(self._data_dir, "door"):
            record(self._data_dir, "door_command", via="voice")
            await phone.send({"door": True})
        elif transcript:
            splitter = SentenceSplitter()
            try:
                # Runs to the end even if the phone hangs up, so the conversation stays consistent.
                async for piece in self._brain.stream(transcript, spoken=True):
                    reply += piece
                    for sentence in splitter.feed(piece):
                        await phone.send({"sentence": sentence})
                for sentence in splitter.flush():
                    await phone.send({"sentence": sentence})
                self._remember(transcript, reply.strip())
            except BrainError as error:
                reply = f"Something went wrong: {error}"
                await phone.send({"sentence": reply})
        await phone.send({"reply": reply.strip()})
        await phone.finish()
        return response

    async def _on_speak(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            text, language = str(data["text"]), str(data.get("language", "en"))
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        if len(text) > MAX_SPEECH_CHARS:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_SPEECH_CHARS, actual_size=len(text))
        audio = await asyncio.to_thread(self._speech.synthesize, text, language)
        return web.Response(body=audio, content_type="audio/wav")

    async def _ask_phone(self, kind: str, **fields) -> web.Response:
        phone = self._phone
        if phone is None or phone.closed:
            return web.json_response({"error": "The Mitauri app is not connected."}, status=503)
        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await phone.send_json({"type": kind, "id": request_id, **fields})
            answer = await asyncio.wait_for(future, DELIVERY_TIMEOUT_SECONDS)
        except (TimeoutError, ConnectionError):
            return web.json_response({"error": "The phone didn't answer in time."}, status=504)
        finally:
            del self._pending[request_id]
        answer.pop("id", None)
        answer.pop("type", None)
        record(self._data_dir, "phone_request", kind=kind, ok="error" not in answer)
        return web.json_response(answer)

    async def show_live(self, title: str, until: float, detail: str = "") -> bool:
        """Shows a countdown in the island. It's kept, so it comes back if the phone reconnects."""
        self._live = {"title": title, "until": int(until), "detail": detail}
        return await self.deliver("live_show", title, until=int(until), detail=detail)

    async def clear_live(self) -> bool:
        self._live = None
        return await self.deliver("live_clear", "")

    async def _on_island(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            if data["action"] == "clear":
                return web.json_response({"delivered": await self.clear_live()})
            if data["action"] != "show":
                raise ValueError
            until = time.time() + float(data["seconds"])
            title, detail = str(data["title"]), str(data.get("detail", ""))
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        return web.json_response({"delivered": await self.show_live(title, until, detail)})

    async def _on_alarm_notice(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            over = bool(data["over"])
            text = str(data.get("text", ""))[:MAX_SPEECH_CHARS]
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        return web.json_response({"delivered": await self.deliver("alarm_over" if over else "alarm", text)})

    async def _on_presence(self, _: web.Request) -> web.Response:
        if self._presence is None:
            return web.json_response({"state": "unknown", "reason": "No presence sensor is set up."})
        return web.json_response(self._presence.status())

    async def _on_presence_key(self, request: web.Request) -> web.Response:
        record(self._data_dir, "presence_key_sent", device=request[DEVICE])
        return web.json_response({"key": load_key(self._data_dir).hex()})

    async def _on_place_reminder(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            action = str(data["action"])
            if action == "add":
                return await self._ask_phone(
                    "add_place_reminder", text=str(data["text"]), latitude=float(data["latitude"]),
                    longitude=float(data["longitude"]), radius=float(data["radius"]),
                )
            if action == "list":
                return await self._ask_phone("list_place_reminders")
            if action == "remove":
                return await self._ask_phone("remove_place_reminder", reminder=str(data["reminder"]))
        except (ValueError, KeyError, TypeError):
            pass
        raise web.HTTPBadRequest()

    async def _on_open_door(self, _: web.Request) -> web.Response:
        return await self._ask_phone("open_door")

    async def _on_read_notifications(self, _: web.Request) -> web.Response:
        return await self._ask_phone("read_notifications")

    async def _on_ask_to_reply(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            notification, text = str(data["notification"]), str(data["text"])
        except (ValueError, KeyError, TypeError):
            raise web.HTTPBadRequest() from None
        if len(text) > MAX_REPLY_CHARS:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_REPLY_CHARS, actual_size=len(text))
        # Sending straight away needs the user's own typed command. Anything else goes to the confirmation screen.
        direct, self._may_send_once = self._may_send_once, False
        record(self._data_dir, "reply_requested", direct=direct)
        return await self._ask_phone("send_reply" if direct else "ask_to_reply", notification=notification, text=text)

    async def _on_location_request(self, _: web.Request) -> web.Response:
        phone = self._phone
        if phone is None or phone.closed:
            return web.json_response({"error": "The Mitauri app is not connected."}, status=503)
        record(self._data_dir, "location_requested")
        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await phone.send_json({"type": "get_location", "id": request_id})
            data = await asyncio.wait_for(future, LOCATION_TIMEOUT_SECONDS)
        except TimeoutError:
            return web.json_response({"error": "The phone didn't send a location in time."}, status=504)
        finally:
            del self._pending[request_id]
        if data.get("error"):
            return web.json_response({"error": str(data["error"])}, status=502)
        return web.json_response(
            {"latitude": data["latitude"], "longitude": data["longitude"], "accuracy_m": data.get("accuracy")}
        )
