from datetime import datetime, timedelta, timezone

from .. import toolbox
from ..config import data_dir
from ..reminders import Reminders

MAX_MINUTES = 24 * 60
MAX_TITLE_CHARS = 30
MAX_DETAIL_CHARS = 200
MAX_PENDING_REMINDERS = 100

tool = toolbox.tool_for("island")


@tool
def start_countdown(title: str, minutes: float, detail: str | None = None, remind_at_end: bool = True) -> dict:
    """Show a live countdown in the phone's island, like a timer. Use it when the user asks for a timer.

    title is a word or two. With remind_at_end, the phone also gets a notification when it's over.
    Only one runs at a time, so a new one replaces the old one.
    """
    if not 0 < minutes <= MAX_MINUTES:
        return {"error": "That has to be between now and 24 hours from now."}
    answer = toolbox.ask_server("/island", {
        "action": "show", "title": title[:MAX_TITLE_CHARS], "seconds": round(minutes * 60), "detail": (detail or "")[:MAX_DETAIL_CHARS],
    })
    if remind_at_end and "error" not in answer:
        reminders = Reminders(data_dir() / "reminders.db")
        if len(reminders.pending()) < MAX_PENDING_REMINDERS:
            reminders.add(f"{title[:MAX_TITLE_CHARS]}: Zeit ist um.", datetime.now(timezone.utc) + timedelta(minutes=minutes))
    return answer


@tool
def clear_countdown() -> dict:
    """Remove the countdown from the island. Any reminder set with it stays, so cancel that separately."""
    return toolbox.ask_server("/island", {"action": "clear"})
