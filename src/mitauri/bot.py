import asyncio
import json
import logging
import time as time_module
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import discord

from . import proposals, research, transit, untis, wake, weather
from .audit import record
from .brain import Brain, BrainError
from .config import Config
from .reminders import CALL, TRIP, Reminders

MAX_MESSAGE_LENGTH = 2000
CHECK_INTERVAL_SECONDS = 5
MAX_CALL_LATENESS_SECONDS = 900
TIMETABLE_CHECK_SECONDS = 1800
PHONE_BACKUP_DELAY_SECONDS = 180
BRIEFING_WINDOW = timedelta(hours=3)  # a much later briefing is no use, so skip that day
BRIEFING_PROMPT = (
    "Give me my morning briefing: today's calendar events and my open tasks, especially any due "
    "today or overdue. Keep it short. If there is nothing, say so in one line."
)

log = logging.getLogger(__name__)


def split_message(text: str) -> list[str]:
    return [text[i:i + MAX_MESSAGE_LENGTH] for i in range(0, len(text), MAX_MESSAGE_LENGTH)] or ["(empty reply)"]


def is_from_owner(message: discord.Message, owner_id: int) -> bool:
    """Mitauri only answers direct messages from its owner, never server channels."""
    return message.author.id == owner_id and message.guild is None


def briefing_due(now: datetime, at: time, last_date: str | None) -> bool:
    scheduled = datetime.combine(now.date(), at, now.tzinfo)
    return last_date != now.date().isoformat() and scheduled <= now < scheduled + BRIEFING_WINDOW


class MitauriBot(discord.Client):
    def __init__(self, owner_id: int, brain: Brain, reminders: Reminders, config: Config, phone=None, briefing_brain=None, speech=None):
        # Direct messages to a bot include their text without any privileged intent.
        intents = discord.Intents.none()
        intents.dm_messages = True
        super().__init__(intents=intents)
        self._owner_id = owner_id
        self._brain = brain
        self._reminders = reminders
        self._phone = phone
        self._speech = speech
        self._first_lessons = {}
        self._next_timetable_check = 0.0
        self._warned_timetable = False
        self._wake_task = None
        self._briefing_brain = briefing_brain or brain
        self._told_unreachable: set[int] = set()
        self._data_dir = config.data_dir
        self._timezone = ZoneInfo(config.timezone)
        self._briefing_time = time.fromisoformat(config.briefing_time) if config.briefing_time else None
        self._briefing_file = config.data_dir / "last_briefing.txt"
        self._scheduler = None

    async def setup_hook(self) -> None:
        self._scheduler = asyncio.create_task(self._run_schedule())

    async def on_ready(self) -> None:
        log.info("Connected to Discord as %s", self.user)

    async def on_message(self, message: discord.Message) -> None:
        if not is_from_owner(message, self._owner_id) or not message.content:
            return
        if message.content.strip() == "/new":
            self._brain.reset()
            await message.channel.send("Starting a fresh conversation. I still remember your saved facts.")
            return
        async with message.channel.typing():
            reply = await self._ask(message.content)
        for part in split_message(reply):
            await message.channel.send(part)

    async def _ask_for_briefing(self) -> str:
        try:
            return await self._briefing_brain.ask(BRIEFING_PROMPT)
        except BrainError as error:
            return f"Something went wrong: {error}"

    async def _ask(self, text: str) -> str:
        try:
            return await self._brain.ask(text)
        except BrainError as error:
            log.warning("Brain error: %s", error)
            return f"Something went wrong: {error}"

    async def _run_schedule(self) -> None:
        await self.wait_until_ready()
        while not self.is_closed():
            try:
                await self._tick()
            except Exception:  # a Discord hiccup must not stop reminders for good
                log.exception("Scheduled check failed")
            await asyncio.sleep(CHECK_INTERVAL_SECONDS)

    async def _tick(self) -> None:
        now = datetime.now(self._timezone)
        for reminder_id, text, kind, due in self._reminders.due(now):
            # Stays waiting until the phone confirms it, so nothing is lost while it's offline.
            if await self._deliver(text, kind, now.timestamp() - due):
                self._reminders.remove(reminder_id)
                self._told_unreachable.discard(reminder_id)
            elif reminder_id not in self._told_unreachable:
                # Discord isn't private, so this says that something is waiting, not what.
                await self._send_to_owner("⏰ Ich habe etwas für dich, aber dein Handy ist nicht erreichbar. Öffne die Mitauri-App.")
                self._told_unreachable.add(reminder_id)
                record(self._data_dir, "delivery_waiting", kind=kind)
        await self._check_school_wake(now)
        await self._report_proposals()
        await self._report_research()
        if self._briefing_time and briefing_due(now, self._briefing_time, self._last_briefing()):
            # Recorded first, so a failure can't retry and drain the usage limit all morning.
            self._briefing_file.write_text(now.date().isoformat())
            # Your calendar doesn't belong on Discord, so the briefing only goes to the phone.
            if self._phone:
                await self._phone.deliver("notify", await self._ask_for_briefing())

    async def _check_school_wake(self, now: datetime) -> None:
        settings = wake.load_settings(self._data_dir)
        if not settings["enabled"]:
            return
        if time_module.monotonic() >= self._next_timetable_check:
            self._next_timetable_check = time_module.monotonic() + TIMETABLE_CHECK_SECONDS
            try:
                lessons = await asyncio.to_thread(untis.upcoming, self._data_dir, now.date())
                self._first_lessons = untis.first_lessons(lessons)
            except (untis.UntisError, OSError, ValueError, KeyError, TypeError) as error:
                # An older timetable is still better than no alarm, and failing silently isn't an option.
                log.warning("Couldn't update the timetable: %s", error)
                if not self._warned_timetable and self._phone:
                    self._warned_timetable = await self._phone.deliver("notify", "Stundenplan konnte nicht geladen werden. Stell dir sicherheitshalber einen Wecker.")
            else:
                self._warned_timetable = False
        fired = now.date() if wake.already_fired(self._data_dir, now.date()) else None
        plan = wake.next_wake(self._first_lessons, now, settings["minutes_before"], fired)
        if plan and plan["day"] == now.date() and plan["wake"] <= now:
            wake.mark_fired(self._data_dir, now.date())
            record(self._data_dir, "school_wake")
            task = asyncio.create_task(self._sound_school_wake(plan, settings))
            self._wake_task = task

    async def _sound_school_wake(self, plan: dict, settings: dict) -> None:
        text = wake.alarm_text(plan["lesson"])
        ring_soon = settings["phone_backup"]
        try:
            audio = await asyncio.to_thread(self._speech.synthesize, text, "de")
            await asyncio.to_thread(wake.play, settings["device"], audio, wake.wav_seconds(audio), settings["volume"])
        except Exception:  # whatever went wrong with the speaker, the phone still has to ring
            log.exception("The speaker alarm failed")
            ring_soon = True
        else:
            await asyncio.sleep(PHONE_BACKUP_DELAY_SECONDS)
        if ring_soon and self._phone:
            await self._phone.deliver("ring", text)

    async def _report_research(self) -> None:
        for job in research.unreported(self._data_dir):
            text = (f"Deine Recherche ist fertig: {job['question'][:80]}. Frag mich danach."
                    if job["status"] == "ready" else f"Die Recherche hat nicht geklappt: {job['question'][:80]}")
            if self._phone and await self._phone.deliver("notify", text):
                research.mark_told(job["id"], self._data_dir)

    async def _report_proposals(self) -> None:
        for proposal in proposals.unreported(self._data_dir):
            text = (f"Vorschlag {proposal['id']} ist fertig. Prüfe ihn am Computer: mitauri proposal show {proposal['id']}"
                    if proposal["status"] == "ready" else f"Vorschlag {proposal['id']} hat nicht geklappt.")
            if self._phone and await self._phone.deliver("notify", text):
                proposals.mark_told(proposal["id"], self._data_dir)

    def _rain_tip(self, stop_name: str) -> str | None:
        """A bonus only, so any failure just leaves it out."""
        try:
            stop = transit.find_stop(stop_name)
            now_hour = datetime.now(self._timezone).strftime("%Y-%m-%dT%H:00")
            return weather.rain_tip(weather.forecast(*stop["coord"], now_hour))
        except (OSError, ValueError, TypeError, KeyError):
            return None

    async def _check_trip(self, text: str) -> bool:
        """Looks up live data shortly before leaving, then schedules the alert for the real time."""
        alert = json.loads(text)
        try:
            plan = await asyncio.to_thread(
                transit.leave_plan, alert["origin"], alert["destination"],
                datetime.fromisoformat(alert["arrive_by"]), alert["walk"],
            )
        except (OSError, ValueError):
            plan = None
        leave = plan["leave"] if plan else datetime.fromisoformat(alert["leave"])
        trip = plan["trip"] if plan else f"{alert['trip']} (Fahrplan nicht geprüft)"
        tip = await asyncio.to_thread(self._rain_tip, alert["origin"])
        message = f"Zeit loszugehen: {trip}" + (f" {tip}" if tip else "")
        if leave <= datetime.now(timezone.utc):
            return bool(self._phone) and await self._phone.deliver("notify", message)
        self._reminders.add(message, leave)
        record(self._data_dir, "trip_rechecked", changed=bool(plan) and trip != alert["trip"])
        return True

    async def _deliver(self, text: str, kind: str, late_seconds: float) -> bool:
        if kind == TRIP:
            return await self._check_trip(text)
        # A call that's long overdue, say after the server was off, shouldn't wake anyone.
        ring = kind == CALL and late_seconds < MAX_CALL_LATENESS_SECONDS
        delivered = bool(self._phone) and await self._phone.deliver("ring" if ring else "notify", text)
        if delivered:
            record(self._data_dir, "delivered", kind="call" if ring else "reminder")
        return delivered

    def _last_briefing(self) -> str | None:
        return self._briefing_file.read_text() if self._briefing_file.exists() else None

    async def _send_to_owner(self, text: str) -> None:
        owner = await self.fetch_user(self._owner_id)
        for part in split_message(text):
            await owner.send(part)
