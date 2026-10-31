import json
from datetime import datetime, timedelta, timezone

from .. import toolbox, transit
from ..config import data_dir
from ..reminders import TRIP, Reminders

TRIP_RECHECK_MINUTES = 20
MAX_PENDING_REMINDERS = 100

tool = toolbox.tool_for("transit", limited=['next_departures', 'plan_trip'])


@tool
def leave_alert(origin: str, destination: str, arrive_by: str, walk_minutes: int = 5) -> str:
    """Tell the user when to leave to reach a stop by bus or train by a set time, using live data.

    origin and destination are VRR stops with their city. arrive_by is an RFC 3339 timestamp with a UTC
    offset. walk_minutes is how long the walk to the first stop takes, so ask if you don't know it.
    """
    try:
        moment = datetime.fromisoformat(arrive_by)
        if moment.tzinfo is None:
            return "Not set: the time needs a UTC offset."
        plan = transit.leave_plan(origin, destination, moment, walk_minutes)
    except (OSError, ValueError):
        return "Not set: the timetable service couldn't be reached."
    if plan is None:
        return "Not set: no connection arrives in time."
    reminders = Reminders(data_dir() / "reminders.db")
    if len(reminders.pending()) >= MAX_PENDING_REMINDERS:
        return "Not set: too many reminders are waiting already."
    now = datetime.now(timezone.utc)
    if plan["leave"] <= now:
        return f"Not set: to make it, the user needed to leave already. Best connection: {plan['trip']}."
    alert = {
        "origin": origin, "destination": destination, "arrive_by": moment.isoformat(),
        "walk": walk_minutes, "leave": plan["leave"].isoformat(), "trip": plan["trip"],
    }
    check = max(now, plan["leave"] - timedelta(minutes=TRIP_RECHECK_MINUTES))
    reminders.add(json.dumps(alert), check, TRIP)
    return f"Set. Currently: leave at {plan['leave'].isoformat()} for {plan['trip']}. It re-checks live data shortly before."


@tool
def next_departures(stop: str, line: str | None = None) -> dict:
    """Live departures from a bus or train stop in the VRR region (Rhine-Ruhr), with delays.

    stop should include the city, like "München Hauptbahnhof". line can limit it to one line.
    """
    try:
        return transit.departures(stop, line)
    except (OSError, ValueError):
        return {"error": "The timetable service couldn't be reached."}


@tool
def plan_trip(origin: str, destination: str, arrive_by: str | None = None) -> dict:
    """Bus and train connections in the VRR region between two stops, including the city in names.

    arrive_by is an optional RFC 3339 timestamp, in local time. It doesn't include walking to the
    first stop, so ask how long that takes before saying when to leave.
    """
    try:
        return transit.trips(origin, destination, datetime.fromisoformat(arrive_by) if arrive_by else None)
    except (OSError, ValueError):
        return {"error": "The timetable service couldn't be reached."}
