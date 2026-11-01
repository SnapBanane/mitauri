import asyncio
import json
import logging
import time as time_module
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import discord

from . import modules, proposals, research, transit, untis, wake, wake_planner, weather
from .audit import record
from .brain import Brain, BrainError
from .config import Config
from .reminders import CALL, TRIP, Reminders

MAX_MESSAGE_LENGTH = 2000
CHECK_INTERVAL_SECONDS = 5
MAX_CALL_LATENESS_SECONDS = 900
TIMETABLE_CHECK_SECONDS = 1800
PHONE_BACKUP_DELAY_SECONDS = 180
PLAN_DAYS_AHEAD = 2
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
    def __init__(self, owner_id: int, brain: Brain, reminders: Reminders, config: Config, phone=None, briefing_brain=None, speech=None, facts=None):
        # Direct messages to a bot include their text without any privileged intent.
        intents = discord.Intents.none()
        intents.dm_messages = True
        super().__init__(intents=intents)
        self._owner_id = owner_id
        self._brain = brain
        self._reminders = reminders
        self._phone = phone
        self._speech = speech
        self._facts = facts
        self._first_lessons = {}
        self._next_timetable_check = 0.0
        self._warned_timetable = False
        self._wake_task = None
        self.presence = None
        self.watched_labels: dict[str, str] = {}
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
        # The alarm goes first, and each step on its own: one broken reminder or a Discord outage must not stop the rest.
        steps = [self._check_school_wake(now), self._deliver_reminders(now), self._report_proposals(), self._check_left_behind(), self._report_research(), self._send_briefing(now)]
        for step in steps:
            try:
                await step
            except Exception:
                log.exception("A scheduled step failed")

    async def _deliver_reminders(self, now: datetime) -> None:
        for reminder_id, text, kind, due in self._reminders.due(now):
            try:
                await self._deliver_reminder(reminder_id, text, kind, now.timestamp() - due)
            except Exception:
                log.exception("A reminder failed")

    async def _deliver_reminder(self, reminder_id: int, text: str, kind: str, late_seconds: float) -> None:
        # Stays waiting until the phone confirms it, so nothing is lost while it's offline.
        if await self._deliver(text, kind, late_seconds):
            self._reminders.remove(reminder_id)
            self._told_unreachable.discard(reminder_id)
        elif reminder_id not in self._told_unreachable:
            # Discord isn't private, so this says that something is waiting, not what.
            self._told_unreachable.add(reminder_id)
            record(self._data_dir, "delivery_waiting", kind=kind)
            await self._send_to_owner("⏰ Ich habe etwas für dich, aber dein Handy ist nicht erreichbar. Öffne die Mitauri-App.")

    async def _send_briefing(self, now: datetime) -> None:
        if self._briefing_time and briefing_due(now, self._briefing_time, self._last_briefing()):
            # Recorded first, so a failure can't retry and drain the usage limit all morning.
            self._briefing_file.write_text(now.date().isoformat())
            # Your calendar doesn't belong on Discord, so the briefing only goes to the phone.
            if self._phone:
                await self._phone.deliver("notify", await self._ask_for_briefing())

    async def _check_school_wake(self, now: datetime) -> None:
        settings = wake.load_settings(self._data_dir)
        if not settings["enabled"] or not modules.is_enabled(self._data_dir, "school"):
            return
        if time_module.monotonic() >= self._next_timetable_check:
            self._next_timetable_check = time_module.monotonic() + TIMETABLE_CHECK_SECONDS
            try:
                lessons = await asyncio.to_thread(untis.upcoming, self._data_dir, now.date())
                self._first_lessons = untis.first_lessons(lessons)
                await self._plan_next_wake(lessons, now, settings)
            except (untis.UntisError, OSError, ValueError, KeyError, TypeError) as error:
                # An older timetable is still better than no alarm, and failing silently isn't an option.
                log.warning("Couldn't update the timetable: %s", error)
                if not self._warned_timetable and self._phone:
                    self._warned_timetable = await self._phone.deliver("notify", "Stundenplan konnte nicht geladen werden. Stell dir sicherheitshalber einen Wecker.")
            else:
                self._warned_timetable = False
        fired = now.date() if wake.already_fired(self._data_dir, now.date()) else None
        chosen = wake_planner.load_plans(self._data_dir)
        plan = wake.next_wake(self._first_lessons, now, settings["minutes_before"], fired, chosen)
        if plan and plan["day"] == now.date() and plan["wake"] <= now:
            wake.mark_fired(self._data_dir, now.date())
            record(self._data_dir, "school_wake")
            task = asyncio.create_task(self._sound_school_wake(plan, settings))
            self._wake_task = task

    async def _plan_next_wake(self, lessons: list[dict], now: datetime, settings: dict) -> None:
        """Asks only when something that matters changed, so it costs about one small request a day."""
        upcoming = [d for d in sorted(self._first_lessons) if d >= now.date() and (d - now.date()).days <= PLAN_DAYS_AHEAD]
        if not upcoming or self._facts is None:
            return
        day = upcoming[0]
        first = self._first_lessons[day]
        facts = [text for _, text in self._facts.all()]
        plan, changed = await asyncio.to_thread(
            wake_planner.ensure_plan, self._data_dir, lessons, day, first, facts, settings["minutes_before"],
        )
        if plan is None:  # keeps the plain setting, and tries again at the next check
            log.warning("Couldn't work out a wake-up time from the rules")
        elif changed and self._phone:
            await self._phone.deliver("notify", f"Wecker am {day.strftime('%d.%m.')}: {plan['wake']} Uhr. {plan['why']}")

    async def _sound_school_wake(self, plan: dict, settings: dict) -> None:
        text = wake.alarm_text(plan["lesson"])
        if self._phone:
            await self._phone.deliver("alarm", text)
        sound = asyncio.create_task(self._play_alarm(text, settings))
        try:
            await asyncio.wait_for(asyncio.shield(sound), PHONE_BACKUP_DELAY_SECONDS if settings["phone_backup"] else None)
        except TimeoutError:
            # Still ringing after a few minutes, so the phone joins in. Answering or declining stops both.
            if self._phone:
                await self._phone.deliver("ring", text)
        stopped = await sound
        if stopped is None and self._phone:  # the speaker failed, so the phone has to do it alone
            await self._phone.deliver("ring", text)
        if self._phone:
            await self._phone.deliver("alarm_over", "")

    async def _play_alarm(self, text: str, settings: dict) -> bool | None:
        try:
            audio = wake.with_tone(await asyncio.to_thread(self._speech.synthesize, text, "de"))
            return await asyncio.to_thread(
                wake.play, settings["device"], audio, wake.wav_seconds(audio), settings["volume"], self._data_dir,
            )
        except Exception:  # whatever went wrong with the speaker, the phone still has to ring
            log.exception("The speaker alarm failed")
            return None

    async def _check_left_behind(self) -> None:
        if self.presence is None or not modules.is_enabled(self._data_dir, "presence"):
            return
        for name in self.presence.newly_left_behind():
            label = self.watched_labels.get(name, name)
            text = (f"{label} zurückgelassen? Seit 2 Minuten nicht mehr in Reichweite von Handy und Laptop. "
                    "Oder sie hat keinen Strom mehr.")
            record(self._data_dir, "left_behind_alert")  # no name or place, as nothing about location is kept
            if not (self._phone and await self._phone.deliver("notify", text)):
                await self._send_to_owner(text)

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
        if self._phone and modules.is_enabled(self._data_dir, "island"):
            await self._phone.show_live("Losgehen", leave.timestamp(), trip)
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
