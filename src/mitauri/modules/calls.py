from datetime import datetime, timezone

from .. import toolbox
from ..config import data_dir
from ..reminders import CALL, Reminders

MAX_TEXT_CHARS = 500
MAX_PENDING_CALLS = 10
MIN_CALL_SPACING_SECONDS = 60

tool = toolbox.tool_for("calls")


@tool
def call_me(reason: str, when: str | None = None) -> str:
    """Ring the user's phone with a full-screen call, now or at a set time.

    Only use this when the user asks to be called. reason is spoken when they answer, so write it
    as a short sentence in their language. when is an RFC 3339 timestamp with a UTC offset.
    """
    moment = datetime.now(timezone.utc)
    if when:
        moment = datetime.fromisoformat(when)
        if moment.tzinfo is None:
            return "Not set: the time needs a UTC offset."
        if moment <= datetime.now(timezone.utc):
            return "Not set: that time has already passed."
    # Limits how much a mistake, or a malicious calendar entry, could ever ring the phone.
    reminders = Reminders(data_dir() / "reminders.db")
    calls = [due for _, _, due, kind in reminders.pending() if kind == CALL]
    if len(reason) > MAX_TEXT_CHARS:
        return "Not set: that's too long. Shorten it."
    if len(calls) >= MAX_PENDING_CALLS:
        return "Not set: too many calls are waiting already."
    if any(abs(due - moment.timestamp()) < MIN_CALL_SPACING_SECONDS for due in calls):
        return "Not set: another call is already due within a minute of that."
    return f"Call [{reminders.add(reason, moment, CALL)}] scheduled."
