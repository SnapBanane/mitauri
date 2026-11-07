"""The connection to the phone app. Every request is signed, because a tailnet can include other people."""

import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from aiohttp import WSMsgType, web

from . import app_update, dashboard, instant, modules, style, wake
from .audit import record
from .auth import Devices, FailureLimiter
from .brain import TOOL_CALL, BrainError
from .presence import load_key
from .reminders import Reminders
from .style import StyleLearner
from .config import API_PORT, LOCATION_TIMEOUT_SECONDS, load_token  # noqa: F401
from .speech import WAITING

PORT = API_PORT
LINK = re.compile(r"https?://\S+")
SENTENCE = re.compile(r"(.+?(?:[.!?…]+(?=\s)|\n))", re.DOTALL)
FIRST_CLAUSE = re.compile(r"(.{25,}?[,;])\s+", re.DOTALL)
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
    r"(?i)\b(antworte|antworten|antwort|zurückschreiben|schreib|schreibe|schreiben|schick|schicke|schicken|sende|senden|"
    r"send|reply|respond|answer|write back|(?:sag|sage|sagen|tell)(?! mir| me))\b"
)
ASKING_FOR_ADVICE = re.compile(r"(?i)\b(soll ich|sollte ich|should i|what would)\b")
GOING_AHEAD = {"ja", "jo", "jap", "ja bitte", "yes", "yep", "ok", "okay", "mach", "mach das", "mach mal", "schick", "schick es", "send it"}
MAX_SPEECH_CHARS = 2_000
PREPARED_MAX_TAIL_BYTES = 32_000  # 1 s of audio: what the phone may record after preparing, all of it quiet
PREPARED_MAX_AGE_SECONDS = 30
MAX_INSTANT_NOTES = 10
MAX_PENDING_REMINDERS = 100
MAX_PREPARES_PER_MINUTE = 8  # each one can reach Groq, which allows 20 requests a minute
LOOPBACK = ("127.0.0.1", "::1")
DEVICE = web.RequestKey("device", str)
INTERNAL_PATHS = ("/location", "/door", "/notifications", "/reply", "/place-reminders", "/quiet-places", "/presence", "/alarm-notice", "/island", "/watch", "/app-update-notice")
MAX_REPLY_CHARS = 1_000
MAX_WATCH_TITLE_CHARS = 40
MAX_WATCH_TEXT_CHARS = 250
MAX_WATCH_MESSAGES_PER_MINUTE = 10
WATCH_BRIGHTNESS = (20, 100)
WATCH_FACES = (1, 4)  # the faces seen on the wire
JPEG_START = b"\xff\xd8\xff"
DASHBOARD_FOLDER = Path(dashboard.__file__).parent
DASHBOARD_REFRESH_SECONDS = 60  # each refresh wakes the phone's GPS and the watch's Bluetooth
DASHBOARD_VIEWER_GONE_SECONDS = 30

log = logging.getLogger(__name__)


class _PhoneStream:
    """Writes to the phone, and ignores the phone having disconnected."""

    def __init__(self, response: web.StreamResponse):
        self._response = response
        self._open = True

    @property
    def is_open(self) -> bool:
        return self._open

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
    """Whether the user's own words ask Mitauri to send something, or say yes to doing so. "Was schreibt Max?" doesn't."""
    if re.sub(r"[^\wäöüß ]", "", text.lower()).strip() in GOING_AHEAD:
        return True
    return SEND_COMMAND.search(text) is not None and ASKING_FOR_ADVICE.search(text) is None


def is_stop_command(text: str) -> bool:
    return re.sub(r"[^\wäöüß ]", "", text.lower()).strip() in STOP_COMMANDS


def speakable(text: str) -> str:
    """Removes what shouldn't be read aloud: links and Markdown symbols."""
    return re.sub(r"\s+", " ", re.sub(r"[*_#`]", "", LINK.sub("", text))).strip()


class SentenceSplitter:
    """Cuts streamed text into sentences. A sentence only ends at whitespace, so 9.15 stays whole.

    A long first sentence is also cut at a comma, because the voice starts sooner on a shorter piece.
    """

    def __init__(self):
        self._buffer = ""
        self._started = False

    def feed(self, text: str) -> list[str]:
        self._buffer += text
        sentences = []
        while match := SENTENCE.match(self._buffer):
            sentences.append(match.group(1))
            self._buffer = self._buffer[match.end():]
        if not sentences and not self._started and (clause := FIRST_CLAUSE.match(self._buffer)):
            sentences.append(clause.group(1))
            self._buffer = self._buffer[clause.end():]
        pieces = [s for s in map(speakable, sentences) if s]
        self._started = self._started or bool(pieces)
        return pieces

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
        self._watch_sends: list[float] = []
        self._learning = False
        self._instant_notes: list[str] = []
        self._prepared: tuple[bytes, int, float, asyncio.Task] | None = None
        self._prepare_times: list[float] = []
        self._turn = asyncio.Lock()  # one conversation turn at a time, so a send permission can't reach another turn
        self._phone: web.WebSocketResponse | None = None
        self._pending: dict[str, asyncio.Future] = {}
        self._tasks: set[asyncio.Task] = set()
        self._runner: web.AppRunner | None = None
        self._dashboard = dashboard.Access(data_dir)
        self._errors = dashboard.ErrorFeed()
        self._started = time.time()
        self._phone_since: float | None = None
        self._phone_gone_at: float | None = None
        self._phone_facts: dict[str, dict] = {}  # memory only, and emptied once nobody is looking
        self._viewed_at = 0.0
        self._refresher: asyncio.Task | None = None
        self.discord_ready = None  # set by whoever runs the bot

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
        app.router.add_post("/quiet-places", self._on_quiet_place)
        app.router.add_post("/alarm-notice", self._on_alarm_notice)
        app.router.add_post("/island", self._on_island)
        app.router.add_post("/watch", self._on_watch)
        app.router.add_get("/presence", self._on_presence)
        app.router.add_get("/presence-key", self._on_presence_key)
        app.router.add_post("/presence-report", self._on_presence_report)
        app.router.add_post("/photo", self._on_photo)
        app.router.add_post("/voice", self._on_voice)
        app.router.add_post("/voice-prepare", self._on_voice_prepare)
        app.router.add_post("/speak", self._on_speak)
        app.router.add_get("/app-update", self._on_app_update)
        app.router.add_get("/app-update/apk", self._on_app_update_apk)
        app.router.add_post("/app-update-notice", self._on_app_update_notice)
        app.router.add_get("/memories", self._on_list_memories)
        app.router.add_delete("/memories/{id:[0-9]+}", self._on_delete_memory)
        app.router.add_post("/conversation/reset", self._on_reset_conversation)
        app.router.add_delete("/long-term-memory", self._on_clear_history)
        app.router.add_get("/modules", self._on_list_modules)
        app.router.add_post("/modules", self._on_set_module)
        app.router.add_get("/dashboard", self._on_dashboard_file)
        app.router.add_get("/dashboard/page.css", self._on_dashboard_file)
        app.router.add_get("/dashboard/page.js", self._on_dashboard_file)
        app.router.add_post("/dashboard/login", self._on_dashboard_login)
        app.router.add_post("/dashboard/logout", self._on_dashboard_logout)
        app.router.add_get("/dashboard/status", self._on_dashboard_status)
        logging.getLogger().addHandler(self._errors)
        self._runner = web.AppRunner(app, access_log=None)  # who asked what is nobody's log's business
        await self._runner.setup()
        for host in hosts:
            await web.TCPSite(self._runner, host, port).start()
        log.info("Phone connection listening on %s, port %s", ", ".join(hosts), port)

    async def stop(self) -> None:
        logging.getLogger().removeHandler(self._errors)
        if self._refresher:
            self._refresher.cancel()
        if self._runner:
            await self._runner.cleanup()

    @web.middleware
    async def _guard(self, request: web.Request, handler):
        address = request.remote or "unknown"
        if self._limiter.is_locked(address):
            raise web.HTTPTooManyRequests()
        if request.path == "/pair":
            return await handler(request)
        if request.path in dashboard.FILES or request.path == "/dashboard/login":
            return await handler(request)
        if request.path.startswith("/dashboard/"):
            # Not counted as a failure: an old cookie is normal, and a session token is too long to guess.
            if not self._dashboard.is_valid(request.cookies.get(dashboard.COOKIE, "")):
                raise web.HTTPUnauthorized(headers=dashboard.HEADERS)
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
        if self._devices.listener_name(device) and request.path != "/presence-report":
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
        if name == "watch" and self._phone is not None and not self._phone.closed:
            await self._phone.send_json({"type": "watch_module", "enabled": enabled})
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
        self._phone_since = time.time()
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
            self._phone_gone_at = time.time()
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
        elif data["type"] == "watch_module_query":
            asyncio.create_task(socket.send_json({"type": "watch_module", "enabled": modules.is_enabled(self._data_dir, "watch")}))
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
                # The phone reports what really happened, as it can still refuse or be cancelled.
                await socket.send_json({"type": "reply", "id": message_id, "text": "Einen Moment, die Tür."})
            return
        self._keep_style_lines(style.MITAURI, [text])
        if answer := await self._instant_reply(text):
            if not socket.closed:
                await socket.send_json({"type": "reply", "id": message_id, "text": answer[0]})
            return
        async with self._turn:
            # Decided from the typed words, which outside text can't change. It's good for one send, for this message.
            self._may_send_once = asks_to_send(text)
            try:
                reply = await self._brain.ask(self._with_instant_notes(text))
                self._remember(text, reply)
            except BrainError as error:
                log.warning("A chat reply failed")
                reply = f"Something went wrong: {error}"
            finally:
                self._may_send_once = False
        if not socket.closed:
            await socket.send_json({"type": "reply", "id": message_id, "text": reply})

    def _keep_style_lines(self, source: str, lines: list[str], learn_now: bool = False) -> None:
        self._style.add(source, lines)
        # Summing up takes a while, and each message in that time would otherwise start another one.
        if not self._learning and (learn_now or self._style.is_due()):
            self._learning = True
            task = asyncio.create_task(asyncio.to_thread(self._style.learn))
            self._tasks.add(task)
            task.add_done_callback(self._finished_learning)

    def _finished_learning(self, task: asyncio.Task) -> None:
        self._learning = False
        self._tasks.discard(task)

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
        async with self._turn:
            try:
                reply = await self._brain.ask(text or "Was siehst du auf diesem Bild?", image=("image/jpeg", encoded))
            except BrainError as error:
                log.warning("A chat reply failed")
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

    async def _on_app_update(self, _: web.Request) -> web.Response:
        return web.json_response(app_update.latest(self._data_dir) or {})

    async def _on_app_update_apk(self, request: web.Request) -> web.StreamResponse:
        path = app_update.apk_path(self._data_dir)
        if not path.exists():
            raise web.HTTPNotFound()
        record(self._data_dir, "app_update_downloaded", device=request[DEVICE])
        return web.FileResponse(path, headers={"Content-Type": "application/vnd.android.package-archive"})

    async def _on_app_update_notice(self, _: web.Request) -> web.Response:
        """Tells a connected phone to look now. It only ever fetches from here, so this can't point it elsewhere."""
        phone = self._phone
        if phone is None or phone.closed:
            return web.json_response({"told_phone": False})
        await phone.send_json({"type": "update_available"})
        return web.json_response({"told_phone": True})

    async def _instant_reply(self, text: str) -> tuple[str, str] | None:
        """Answers without Claude if it can, as (reply, language). None means Claude handles it, also after a failure."""
        request = instant.parse(text)
        if request is None:
            return None
        german = request.language == "de"
        if request.kind in ("time", "date"):
            now = datetime.now(ZoneInfo(os.environ.get("MITAURI_TIMEZONE", "Europe/Berlin")))
            reply = instant.clock_reply(request.kind, request.language, now)
        elif request.kind == "thanks":
            reply = "Gerne." if german else "You're welcome."
        else:
            reply = await asyncio.to_thread(self._do_instant, request)
            if reply is None:
                return None
        # Claude didn't see this exchange, so it hears about it with the next message.
        self._instant_notes = [*self._instant_notes, f"{text} -> {reply}"][-MAX_INSTANT_NOTES:]
        self._remember(text, reply)
        return reply, request.language

    def _do_instant(self, request: instant.Instant) -> str | None:
        german = request.language == "de"
        length = instant.duration_words(request.seconds, request.language)
        try:
            if request.kind == "reminder":
                reminders = Reminders(self._data_dir / "reminders.db")
                if len(reminders.pending()) >= MAX_PENDING_REMINDERS:
                    return None
                reminders.add(request.text, datetime.now(timezone.utc) + timedelta(seconds=request.seconds))
                return f"Alles klar, in {length}." if german else f"Okay, in {length}."
            if not modules.is_enabled(self._data_dir, "island"):
                return None
            from .modules import island
            if request.kind == "timer_off":
                answer = island.clear_countdown()
                return None if "error" in answer else ("Timer aus." if german else "Timer off.")
            answer = island.start_countdown("Timer", request.seconds / 60)
            return None if "error" in answer else (f"Timer läuft, {length}." if german else f"Timer set, {length}.")
        except Exception:
            log.exception("An instant request failed, so Claude takes it")
            return None

    def _with_instant_notes(self, text: str) -> str:
        notes, self._instant_notes = self._instant_notes, []
        if not notes:
            return text
        return "[Answered directly before this message, for context only:\n" + "\n".join(notes) + "]\n" + text

    async def _voice_event(self, phrase: str, language: str) -> dict:
        event = {"sentence": phrase}
        try:
            audio = await asyncio.to_thread(self._speech.synthesize, phrase, language)
            event["audio"] = base64.b64encode(audio).decode()
        except Exception:
            log.exception("Couldn't make the voice, so the phone will ask for it")  # it uses /speak then
        return event

    async def _on_voice_prepare(self, request: web.Request) -> web.Response:
        """Starts hearing during the pause at the end of a sentence. Only /voice ever acts on the result."""
        pcm = await request.read()
        now = time.monotonic()
        self._prepare_times = [t for t in self._prepare_times if now - t < 60]
        if pcm and len(self._prepare_times) < MAX_PREPARES_PER_MINUTE:
            self._prepare_times.append(now)
            task = asyncio.create_task(asyncio.to_thread(self._speech.transcribe, pcm))
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
            task.add_done_callback(lambda done: done.cancelled() or done.exception())  # /voice hears again if it failed
            self._prepared = (hashlib.sha256(pcm).digest(), len(pcm), now, task)
        return web.Response(status=202)

    async def _hear(self, pcm: bytes) -> tuple[str, str]:
        """Uses what was prepared, if this recording is that audio plus the short quiet end."""
        prepared, self._prepared = self._prepared, None
        if prepared:
            digest, size, when, task = prepared
            usable = (
                time.monotonic() - when < PREPARED_MAX_AGE_SECONDS
                and 0 <= len(pcm) - size <= PREPARED_MAX_TAIL_BYTES
                and hashlib.sha256(pcm[:size]).digest() == digest
            )
            if usable:
                try:
                    return await task
                except Exception:
                    log.warning("Preparing failed, so this recording is heard again")
        return await asyncio.to_thread(self._speech.transcribe, pcm)

    async def _on_voice(self, request: web.Request) -> web.StreamResponse:
        """Streams one JSON line per event, so the phone can speak before the reply is complete."""
        started = time.monotonic()
        transcript, language = await self._hear(await request.read())
        heard = time.monotonic() - started
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
        elif transcript and (answer := await self._instant_reply(transcript)):
            reply = answer[0]
            await phone.send(await self._voice_event(*answer))
            log.info("Voice timing: heard in %.2f s, answered instantly at %.2f s", heard, time.monotonic() - started)
        elif transcript:
            splitter = SentenceSplitter()
            phrases: asyncio.Queue[str | None] = asyncio.Queue()
            # Voices are made while Claude is still writing, and travel with the text to save a second request.
            speaker = asyncio.create_task(self._send_voices(phone, phrases, language))
            first_words = None
            # Speech never gets a send permission until voices are recognised, so nobody nearby can send as the user.
            async with self._turn:
                try:
                    # Runs to the end even if the phone hangs up, so the conversation stays consistent.
                    async for piece in self._brain.stream(self._with_instant_notes(transcript), spoken=True):
                        if piece is TOOL_CALL:
                            # Tools and the thinking after them take seconds, so the user hears something meanwhile.
                            # It's only spoken, because it isn't part of Claude's reply.
                            if first_words is None:
                                first_words = time.monotonic() - started
                                phrases.put_nowait(WAITING.get(language, WAITING["en"]))
                            continue
                        if first_words is None and piece.strip():
                            first_words = time.monotonic() - started
                        reply += piece
                        for sentence in splitter.feed(piece):
                            phrases.put_nowait(sentence)
                    for sentence in splitter.flush():
                        phrases.put_nowait(sentence)
                    self._remember(transcript, reply.strip())
                except BrainError as error:
                    log.warning("A chat reply failed")
                    reply = f"Something went wrong: {error}"
                    phrases.put_nowait(reply)
                finally:
                    phrases.put_nowait(None)
            await speaker
            log.info("Voice timing: heard in %.2f s, first words at %.2f s, all done at %.2f s",
                     heard, first_words or 0, time.monotonic() - started)
        await phone.send({"reply": reply.strip()})
        await phone.finish()
        return response

    async def _send_voices(self, phone: _PhoneStream, phrases: asyncio.Queue, language: str) -> None:
        while (phrase := await phrases.get()) is not None:
            if phone.is_open:
                await phone.send(await self._voice_event(phrase, language))

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

    async def _on_watch(self, request: web.Request) -> web.Response:
        """Only these actions exist: Claude can't reach the watch's raw commands, firmware or settings."""
        if not modules.is_enabled(self._data_dir, "watch"):
            return web.json_response({"error": "The watch module is switched off."}, status=403)
        try:
            data = await request.json()
            action = str(data["action"])
            if action == "status":
                return await self._ask_phone("watch", action=action)
            if action == "brightness":
                value = int(data["percent"])
                bounds = WATCH_BRIGHTNESS
            elif action == "face":
                value = int(data["face"])
                bounds = WATCH_FACES
            elif action == "notify":
                title, text = str(data.get("title", "Mitauri")), str(data["text"])
                if len(title) > MAX_WATCH_TITLE_CHARS or len(text) > MAX_WATCH_TEXT_CHARS or not text.strip():
                    return web.json_response({"error": "That doesn't fit on the watch."}, status=400)
                now = time.monotonic()
                self._watch_sends = [sent for sent in self._watch_sends if now - sent < 60]
                if len(self._watch_sends) >= MAX_WATCH_MESSAGES_PER_MINUTE:
                    return web.json_response({"error": "Too many watch messages. Try again in a minute."}, status=429)
                self._watch_sends.append(now)
                return await self._ask_phone("watch", action=action, title=title, text=text)
            else:
                raise ValueError
        except (ValueError, KeyError, TypeError, AttributeError):
            raise web.HTTPBadRequest() from None
        if not bounds[0] <= value <= bounds[1]:
            return web.json_response({"error": f"Must be {bounds[0]} to {bounds[1]}."}, status=400)
        return await self._ask_phone("watch", action=action, value=value)

    async def _on_presence(self, _: web.Request) -> web.Response:
        if self._presence is None:
            return web.json_response({"state": "unknown", "reason": "No presence sensor is set up."})
        return web.json_response(self._presence.status())

    async def _on_dashboard_file(self, request: web.Request) -> web.FileResponse:
        name, content_type = dashboard.FILES[request.path]
        return web.FileResponse(DASHBOARD_FOLDER / name, headers={**dashboard.HEADERS, "Content-Type": content_type})

    async def _on_dashboard_login(self, request: web.Request) -> web.Response:
        address = request.remote or "unknown"
        try:
            token = self._dashboard.login(str((await request.json())["code"]))
        except (ValueError, KeyError, TypeError):
            token = None
        if token is None:
            self._reject(address)
        record(self._data_dir, "dashboard_login", address=address)
        response = web.json_response({"ok": True}, headers=dashboard.HEADERS)
        # Not marked Secure: this is plain HTTP inside Tailscale, which encrypts it.
        response.set_cookie(
            dashboard.COOKIE, token, max_age=dashboard.SESSION_SECONDS, path="/dashboard", httponly=True, samesite="Strict",
        )
        return response

    async def _on_dashboard_logout(self, request: web.Request) -> web.Response:
        self._dashboard.logout(request.cookies[dashboard.COOKIE])
        response = web.json_response({"ok": True}, headers=dashboard.HEADERS)
        response.del_cookie(dashboard.COOKIE, path="/dashboard")
        return response

    async def _on_dashboard_status(self, _: web.Request) -> web.Response:
        self._viewed_at = time.monotonic()
        if self._refresher is None or self._refresher.done():
            self._refresher = asyncio.create_task(self._refresh_phone_facts())
        return web.json_response(self._dashboard_status(time.time()), headers=dashboard.HEADERS)

    def _phone_connected(self) -> bool:
        return self._phone is not None and not self._phone.closed

    async def _refresh_phone_facts(self) -> None:
        """Asks the phone only while a page is open. The answers are never written anywhere, and they go when it closes."""
        try:
            while time.monotonic() - self._viewed_at < DASHBOARD_VIEWER_GONE_SECONDS:
                if self._phone_connected():
                    await asyncio.gather(self._fetch_phone_location(), self._fetch_watch_status())
                else:
                    self._phone_facts = {}
                await asyncio.sleep(DASHBOARD_REFRESH_SECONDS)
        finally:
            self._phone_facts = {}

    async def _fetch_phone_location(self) -> None:
        answer = await self._on_location_request(None)
        self._phone_facts["location"] = {**json.loads(answer.text), "asked": time.time()}

    async def _fetch_watch_status(self) -> None:
        if modules.is_enabled(self._data_dir, "watch"):
            answer = json.loads((await self._ask_phone("watch", action="status")).text)
        else:
            answer = {"linked": False}
        self._phone_facts["watch"] = {**answer, "asked": time.time()}

    def _dashboard_status(self, now: float) -> dict:
        def fact(name: str) -> dict:
            found = dict(self._phone_facts.get(name, {}))
            if asked := found.pop("asked", None):
                found["age_seconds"] = round(now - asked)
            return found

        presence = self._presence.status(now) if self._presence else {"sensors": {}}
        status = {
            "now": now,
            "phone": {
                "connected": self._phone_connected(), "since": self._phone_since,
                "gone_at": self._phone_gone_at, "location": fact("location"),
            },
            "watch": fact("watch"),
            "sensors": presence["sensors"],
            "reporters": self._presence.reporters(now) if self._presence else {},
            "devices": [
                {"name": entry["name"], "listener": entry.get("listener")} for entry in self._devices.all().values()
            ],
            "server": {
                **dashboard.host_health(self._data_dir),
                "uptime_seconds": round(now - self._started),
                "modules_off": [item["name"] for item in modules.listing(self._data_dir) if not item["enabled"]],
            },
            "problems": dashboard.recent_problems(self._data_dir, self._errors, now),
        }
        if self.discord_ready:
            status["server"]["discord"] = self.discord_ready()
        if hasattr(self._brain, "running"):
            status["server"]["claude"] = self._brain.running
        status["issues"] = dashboard.needs_attention(status)
        return status

    async def _on_presence_key(self, request: web.Request) -> web.Response:
        record(self._data_dir, "presence_key_sent", device=request[DEVICE])
        return web.json_response({"key": load_key(self._data_dir).hex()})

    async def _on_presence_report(self, request: web.Request) -> web.Response:
        # Never logged or saved, so no location history builds up.
        if self._presence is None:
            return web.json_response({"ok": True})
        try:
            listener = self._devices.listener_name(request[DEVICE]) or "phone"
            self._presence.handle_report(listener, json.loads(await request.read()))
        except ValueError:
            raise web.HTTPBadRequest()
        return web.json_response({"ok": True})

    async def _on_place_reminder(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            action = str(data["action"])
            if action == "add":
                return await self._ask_phone(
                    "add_place_reminder", text=str(data["text"]), latitude=float(data["latitude"]),
                    longitude=float(data["longitude"]), radius=float(data["radius"]), leaving=bool(data.get("leaving", False)),
                )
            if action == "list":
                return await self._ask_phone("list_place_reminders")
            if action == "remove":
                return await self._ask_phone("remove_place_reminder", reminder=str(data["reminder"]))
        except (ValueError, KeyError, TypeError):
            pass
        raise web.HTTPBadRequest()

    async def _on_quiet_place(self, request: web.Request) -> web.Response:
        """Places where the bag check rests. Like place reminders, they live only on the phone."""
        try:
            data = await request.json()
            action = str(data["action"])
            if action == "add":
                return await self._ask_phone(
                    "add_quiet_place", name=str(data["name"])[:60], latitude=float(data["latitude"]),
                    longitude=float(data["longitude"]), radius=float(data["radius"]),
                )
            if action == "list":
                return await self._ask_phone("list_quiet_places")
            if action == "remove":
                return await self._ask_phone("remove_quiet_place", place=str(data["place"]))
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
        except (TimeoutError, ConnectionError):
            return web.json_response({"error": "The phone didn't send a location in time."}, status=504)
        finally:
            del self._pending[request_id]
        if data.get("error"):
            return web.json_response({"error": str(data["error"])}, status=502)
        return web.json_response(
            {"latitude": data["latitude"], "longitude": data["longitude"], "accuracy_m": data.get("accuracy")}
        )
