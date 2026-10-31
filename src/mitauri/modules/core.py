import os
import subprocess
from datetime import datetime, timezone

from .. import toolbox
from ..config import data_dir
from ..memory import Memory
from ..reminders import Reminders

THINKING_TIMEOUT_SECONDS = 240
MAX_TEXT_CHARS = 500
MAX_PROBLEM_CHARS = 20_000
MAX_FACTS = 200
MAX_PENDING_REMINDERS = 100

tool = toolbox.tool_for("core", limited=['set_reminder', 'think_harder'])


@tool
def think_harder(problem: str) -> str:
    """Ask a stronger model to reason through a hard problem, and return its answer.

    It can't see the conversation or use tools, so include every fact it needs. Takes a while.
    """
    if len(problem) > MAX_PROBLEM_CHARS:
        return "Not sent: that's too long. Shorten it."
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    try:
        result = subprocess.run(
            [
                os.environ.get("MITAURI_CLAUDE_BIN", "claude"), "-p",
                "--model", os.environ.get("MITAURI_THINKING_MODEL", "sonnet"),
                "--system-prompt", "Reason carefully and give a clear, concise answer.",
                "--tools", "", "--strict-mcp-config", "--setting-sources", "",
                "--no-session-persistence",
            ],
            input=problem, capture_output=True, text=True, timeout=THINKING_TIMEOUT_SECONDS,
            cwd=data_dir(), env=env,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"Couldn't get an answer: {error}"
    if result.returncode != 0:
        return f"Couldn't get an answer: {(result.stdout or result.stderr).strip()[:300]}"
    return result.stdout.strip()


@tool
def remember(fact: str) -> str:
    """Save a lasting fact or preference about the user."""
    memory = Memory(data_dir() / "memory.db")
    if len(fact) > MAX_TEXT_CHARS:
        return "Not saved: that's too long. Shorten it."
    if len(memory.all()) >= MAX_FACTS:
        return "Not saved: memory is full. Ask the user what to forget."
    return f"Saved as [{memory.add(fact)}]."


@tool
def forget(fact_id: int) -> str:
    """Remove a remembered fact by its number."""
    removed = Memory(data_dir() / "memory.db").remove(fact_id)
    return "Forgotten." if removed else "No fact has that number."


@tool
def set_reminder(text: str, when: str) -> str:
    """Message the user at a set time. when is an RFC 3339 timestamp that includes a UTC offset."""
    moment = datetime.fromisoformat(when)
    if moment.tzinfo is None:
        return "Not set: the time needs a UTC offset."
    if moment <= datetime.now(timezone.utc):
        return "Not set: that time has already passed."
    reminders = Reminders(data_dir() / "reminders.db")
    if len(text) > MAX_TEXT_CHARS:
        return "Not set: that's too long. Shorten it."
    if len(reminders.pending()) >= MAX_PENDING_REMINDERS:
        return "Not set: too many reminders are waiting already."
    return f"Reminder [{reminders.add(text, moment)}] set."


@tool
def list_reminders() -> list[dict]:
    """List reminders and calls that haven't fired yet. Times are in UTC."""
    return [
        {"id": reminder_id, "text": text, "kind": kind,
         "when": datetime.fromtimestamp(due, timezone.utc).isoformat()}
        for reminder_id, text, due, kind in Reminders(data_dir() / "reminders.db").pending()
    ]


@tool
def cancel_reminder(reminder_id: int) -> str:
    """Cancel a reminder by its id."""
    removed = Reminders(data_dir() / "reminders.db").remove(reminder_id)
    return "Cancelled." if removed else "No reminder has that id."
