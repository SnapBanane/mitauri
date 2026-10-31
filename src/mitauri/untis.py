"""The timetable from WebUntis. The login stays in secrets.env and is never shown to Claude. A failed login
stops all further tries until the login is changed, because repeated failures could lock the school account."""

import http.cookiejar
import json
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import unquote_plus, urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

from dotenv import dotenv_values

from .config import SECRETS_FILE

TIMEOUT_SECONDS = 20
CLIENT = "mitauri"
BLOCK_FILE = "untis_login_failed"


class UntisError(Exception):
    pass


def format_time(value: int) -> str:
    return f"{value // 100:02d}:{value % 100:02d}"


def parse_day(number: int) -> date:
    return date(number // 10000, number // 100 % 100, number % 100)


def active_lessons(lessons: list[dict]) -> list[dict]:
    return [lesson for lesson in lessons if lesson.get("code") != "cancelled"]


def first_lessons(lessons: list[dict]) -> dict[date, int]:
    """The first lesson that's really happening on each day. A cancelled first lesson means a later start."""
    firsts: dict[date, int] = {}
    for lesson in active_lessons(lessons):
        day = parse_day(lesson["date"])
        firsts[day] = min(firsts.get(day, lesson["startTime"]), lesson["startTime"])
    return firsts


def fetch_lessons(data_dir: Path, start: date, end: date) -> list[dict]:
    secrets_path = data_dir / SECRETS_FILE
    secrets = dotenv_values(secrets_path)
    try:
        server = secrets["MITAURI_WEBUNTIS_SERVER"].removeprefix("https://").strip("/")
        school = unquote_plus(secrets["MITAURI_WEBUNTIS_SCHOOL"])
        user, password = secrets["MITAURI_WEBUNTIS_USER"], secrets["MITAURI_WEBUNTIS_PASSWORD"]
    except (KeyError, AttributeError):
        raise UntisError("WebUntis isn't set up in secrets.env.") from None
    block = data_dir / BLOCK_FILE
    changed = str(secrets_path.stat().st_mtime)
    if block.exists() and block.read_text() == changed:
        raise UntisError("The WebUntis login failed earlier. It won't be tried again until it's changed in secrets.env.")

    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    url = f"https://{server}/WebUntis/jsonrpc.do?{urlencode({'school': school})}"

    def call(method: str, params: dict) -> dict:
        body = json.dumps({"id": CLIENT, "method": method, "params": params, "jsonrpc": "2.0"}).encode()
        with opener.open(Request(url, body, {"Content-Type": "application/json"}), timeout=TIMEOUT_SECONDS) as response:
            return json.load(response)

    login = call("authenticate", {"user": user, "password": password, "client": CLIENT})
    if "error" in login:
        block.write_text(changed)
        raise UntisError("WebUntis refused the login.")
    block.unlink(missing_ok=True)
    try:
        person = login["result"]
        number = lambda day: int(day.strftime("%Y%m%d"))
        answer = call("getTimetable", {"options": {
            "startDate": number(start), "endDate": number(end),
            "element": {"id": person["personId"], "type": person["personType"]},
            "subjectFields": ["name", "longname"], "teacherFields": ["name"], "roomFields": ["name"],
        }})
        if "error" in answer:
            raise UntisError("WebUntis didn't return a timetable.")
        return answer["result"]
    finally:
        call("logout", {})


def describe_day(lessons: list[dict], day: date) -> list[dict]:
    number = int(day.strftime("%Y%m%d"))
    names = lambda items: [i.get("longname") or i.get("name") or "?" for i in items or []]
    return [
        {
            "start": format_time(lesson["startTime"]),
            "end": format_time(lesson["endTime"]),
            "subject": names(lesson.get("su")),
            "room": [r.get("name") for r in lesson.get("ro") or []],
            "teacher": [t.get("name") for t in lesson.get("te") or []],
            "cancelled": lesson.get("code") == "cancelled",
            "note": lesson.get("lstext") or lesson.get("substText"),
        }
        for lesson in sorted(lessons, key=lambda l: l["startTime"])
        if lesson["date"] == number
    ]


def upcoming(data_dir: Path, today: date, days: int = 9) -> list[dict]:
    return fetch_lessons(data_dir, today, today + timedelta(days=days))
