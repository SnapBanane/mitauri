"""Requests answered without Claude, because they don't need thinking and Claude takes over a second to start.

A request only counts if all of it matches. "Wie spät ist es in Tokio?" isn't one, and goes to Claude as usual.
"""

import re
from dataclasses import dataclass
from datetime import datetime

MAX_REMINDER_CHARS = 500
MAX_REMINDER_SECONDS = 7 * 86_400
FILLER = {"mitauri", "bitte", "hey", "please", "mal", "kurz", "doch", "mir", "me"}
NUMBERS = {
    "ein": 1, "eine": 1, "einen": 1, "einer": 1, "zwei": 2, "drei": 3, "vier": 4, "fünf": 5, "sechs": 6, "sieben": 7,
    "acht": 8, "neun": 9, "zehn": 10, "elf": 11, "zwölf": 12, "fünfzehn": 15, "zwanzig": 20, "fünfundzwanzig": 25,
    "dreißig": 30, "vierzig": 40, "fünfundvierzig": 45, "fünfzig": 50, "sechzig": 60, "neunzig": 90,
    "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
    "ten": 10, "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40, "forty five": 45, "fifty": 50, "sixty": 60,
}
UNITS = {
    "sekunde": 1, "sekunden": 1, "second": 1, "seconds": 1, "minute": 60, "minuten": 60, "minutes": 60,
    "stunde": 3600, "stunden": 3600, "hour": 3600, "hours": 3600,
}
AMOUNT = r"(\d{1,4}|" + "|".join(sorted(map(re.escape, NUMBERS), key=len, reverse=True)) + r")"
UNIT = "(" + "|".join(UNITS) + ")"
HALF_HOUR = r"(eine halbe stunde|halbe stunde|half an hour)"
LENGTH = rf"(?:{AMOUNT} {UNIT}|{HALF_HOUR})"
TIMER = re.compile(
    r"(?:(?:stell|stelle|starte|start|mach|setz|setze|set)(?: einen| nen| ein| a| the)? )?"
    rf"(?:timer (?:auf |für |von |for )?{LENGTH}(?: stellen| starten)?|{LENGTH} timer(?: stellen| starten)?)"
)
TIME = {"wie spät ist es", "wie spät", "wie viel uhr ist es", "wieviel uhr ist es", "wie viel uhr haben wir", "uhrzeit",
        "what time is it", "whats the time"}
DATE = {"welcher tag ist heute", "was für ein tag ist heute", "welches datum ist heute", "welches datum haben wir",
        "der wievielte ist heute", "den wievielten haben wir", "what day is it", "whats the date", "what is the date"}
TIMER_OFF = {"timer aus", "timer stopp", "timer stop", "timer stoppen", "timer abbrechen", "timer löschen", "timer beenden",
             "lösch den timer", "lösche den timer", "stopp den timer", "stoppe den timer", "stop the timer",
             "cancel the timer", "beende den timer", "brich den timer ab"}
THANKS = {"danke", "danke dir", "vielen dank", "thanks", "thank you"}
REMINDER = re.compile(
    rf"(?:erinner|erinnere) mich in {AMOUNT} {UNIT} (?:an|daran|dass),? (.+)|remind me in {AMOUNT} {UNIT} (?:to|about|that) (.+)",
    re.IGNORECASE | re.DOTALL,
)
WEEKDAYS = ("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
MONTHS = ("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")


@dataclass(frozen=True)
class Instant:
    kind: str  # time, date, thanks, timer, timer_off or reminder
    language: str = "de"
    seconds: int = 0
    text: str = ""


def _words(text: str) -> str:
    words = re.sub(r"[^\wäöüß ]", " ", text.lower().replace("what's", "whats")).split()
    return " ".join(w for w in words if w not in FILLER)


def _seconds(amount: str, unit: str) -> int:
    return (int(amount) if amount.isdigit() else NUMBERS[amount]) * UNITS[unit]


def _english(words: str) -> bool:
    return bool(re.search(r"\b(what|whats|thanks|thank|set|the|for|seconds?|minutes?|hours?|half an)\b", words)) \
        and not re.search(r"\b(minuten|stunden|sekunden|für|auf)\b", words)


def parse(text: str) -> Instant | None:
    words = _words(text)
    language = "en" if _english(words) else "de"
    if words in TIME:
        return Instant("time", language)
    if words in DATE:
        return Instant("date", language)
    if words in THANKS:
        return Instant("thanks", language)
    if words in TIMER_OFF:
        return Instant("timer_off", language)
    if match := TIMER.fullmatch(words):
        groups = match.groups()
        if groups[2] or groups[5]:
            return Instant("timer", language, seconds=1800)
        amount, unit = (groups[0], groups[1]) if groups[0] else (groups[3], groups[4])
        return Instant("timer", language, seconds=_seconds(amount, unit))
    return _reminder(text)


def _reminder(text: str) -> Instant | None:
    cleaned = re.sub(r"^(?:(?:hey|hallo) )?(?:mitauri[, ]+)?(?:bitte )?", "", text.strip(), flags=re.IGNORECASE)
    cleaned = re.sub(r"\b(mal|bitte)\b ?", "", cleaned, flags=re.IGNORECASE).strip()
    match = REMINDER.fullmatch(cleaned)
    if not match:
        return None
    german = match.group(1) is not None
    amount, unit, about = match.group(1, 2, 3) if german else match.group(4, 5, 6)
    amount, unit = amount.lower(), unit.lower()
    about = about.strip().rstrip(".!?")
    if not about or len(about) > MAX_REMINDER_CHARS or amount not in NUMBERS and not amount.isdigit() or unit not in UNITS:
        return None
    seconds = _seconds(amount, unit)
    if not 0 < seconds <= MAX_REMINDER_SECONDS:
        return None
    return Instant("reminder", "de" if german else "en", seconds, about)


def duration_words(seconds: int, language: str) -> str:
    names = {"de": (("Stunde", "Stunden"), ("Minute", "Minuten"), ("Sekunde", "Sekunden")),
             "en": (("hour", "hours"), ("minute", "minutes"), ("second", "seconds"))}[language]
    for size, (one, many) in zip((3600, 60, 1), names):
        if seconds % size == 0:
            count = seconds // size
            return f"{'eine' if language == 'de' else 'one'} {one}" if count == 1 else f"{count} {many}"
    return f"{seconds} {names[2][1]}"


def clock_reply(kind: str, language: str, now: datetime) -> str:
    if kind == "time":
        if language == "en":
            return f"It's {now:%H:%M}."
        return f"Es ist {now.hour} Uhr." if now.minute == 0 else f"Es ist {now.hour} Uhr {now.minute}."
    if language == "en":
        return f"It's {now:%A, %B} {now.day}."
    return f"Heute ist {WEEKDAYS[now.weekday()]}, der {now.day}. {MONTHS[now.month - 1]}."
