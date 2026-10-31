"""Live bus and train times from VRR's public timetable service, which needs no account."""

import json
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode
from urllib.request import urlopen
from zoneinfo import ZoneInfo

BASE = "https://efa.vrr.de/standard"
TIMEOUT_SECONDS = 15
MAX_NAME_CHARS = 100
LOCAL_TIME = ZoneInfo("Europe/Berlin")


def _get(endpoint: str, **params) -> dict:
    query = urlencode({"outputFormat": "rapidJSON", "locationServerActive": 1, **params})
    with urlopen(f"{BASE}/{endpoint}?{query}", timeout=TIMEOUT_SECONDS) as response:
        return json.load(response)


def find_stop(name: str) -> dict | None:
    data = _get("XML_STOPFINDER_REQUEST", name_sf=name[:MAX_NAME_CHARS], type_sf="any", coordOutputFormat="WGS84[DD.DDDDD]")
    stops = [item for item in data.get("locations", []) if item.get("type") == "stop"]
    return next((s for s in stops if s.get("isBest")), stops[0] if stops else None)


def _now_params(moment: datetime | None = None) -> dict:
    """The service's own idea of now can lag, so always say which time is meant."""
    local = (moment or datetime.now(timezone.utc)).astimezone(LOCAL_TIME)
    return {"itdDate": local.strftime("%Y%m%d"), "itdTime": local.strftime("%H%M")}


def parse_departures(data: dict, line: str | None = None, now: datetime | None = None) -> list[dict]:
    now = now or datetime.now(timezone.utc)
    departures = []
    for event in data.get("stopEvents", []):
        transport = event.get("transportation", {})
        number = transport.get("number", "")
        if line and line.lower() not in number.lower().split():
            continue
        planned = event.get("departureTimePlanned")
        estimated = event.get("departureTimeEstimated")
        delay = None
        if planned and estimated:
            delay = round((datetime.fromisoformat(estimated) - datetime.fromisoformat(planned)).total_seconds() / 60)
        expected = estimated or planned
        if expected and datetime.fromisoformat(expected) < now:
            continue  # already gone
        departures.append({
            "line": number,
            "to": transport.get("destination", {}).get("name"),
            "planned": planned,
            "expected": expected,
            "delay_minutes": delay,
            "platform": event.get("location", {}).get("properties", {}).get("platform"),
            "cancelled": bool(event.get("isCancelled")),
        })
    return departures


def departures(stop_name: str, line: str | None = None, limit: int = 8) -> dict:
    stop = find_stop(stop_name)
    if stop is None:
        return {"error": f"No stop found for {stop_name}."}
    data = _get("XML_DM_REQUEST", name_dm=stop["id"], type_dm="any", mode="direct", useRealtime=1,
                limit=40 if line else limit + 5, **_now_params())
    return {"stop": stop.get("name"), "departures": parse_departures(data, line)[:limit]}


def parse_trips(data: dict, limit: int = 3, now: datetime | None = None) -> list[dict]:
    now = now or datetime.now(timezone.utc)
    trips = []
    for journey in data.get("journeys", []):
        legs = []
        for leg in journey.get("legs", []):
            origin, destination = leg.get("origin", {}), leg.get("destination", {})
            legs.append({
                "line": leg.get("transportation", {}).get("number") or "zu Fuß",
                "from": origin.get("name"),
                "leaves": origin.get("departureTimeEstimated") or origin.get("departureTimePlanned"),
                "to": destination.get("name"),
                "arrives": destination.get("arrivalTimeEstimated") or destination.get("arrivalTimePlanned"),
            })
        if legs and legs[0]["leaves"] and datetime.fromisoformat(legs[0]["leaves"]) >= now:
            trips.append({"legs": legs})
    return trips[:limit]


def trips(origin: str, destination: str, arrive_by: datetime | None = None) -> dict:
    start, end = find_stop(origin), find_stop(destination)
    if start is None or end is None:
        return {"error": f"No stop found for {origin if start is None else destination}."}
    params = {
        "name_origin": start["id"], "type_origin": "any",
        "name_destination": end["id"], "type_destination": "any",
        "useRealtime": 1, "calcNumberOfTrips": 6,
    }
    if arrive_by:
        params |= {**_now_params(arrive_by), "itdTripDateTimeDepArr": "arr"}
    else:
        params |= {**_now_params(), "itdTripDateTimeDepArr": "dep"}
    return {"from": start.get("name"), "to": end.get("name"), "trips": parse_trips(_get("XML_TRIP_REQUEST2", **params))}


def best_trip(options: list[dict], arrive_by: datetime) -> dict | None:
    """The latest connection that still arrives in time."""
    in_time = [t for t in options if datetime.fromisoformat(t["legs"][-1]["arrives"]) <= arrive_by]
    return max(in_time, key=lambda t: t["legs"][0]["leaves"], default=None)


def describe_trip(trip: dict) -> str:
    rides = [leg for leg in trip["legs"] if leg["line"] != "zu Fuß"] or trip["legs"]
    first = rides[0]
    leaves = datetime.fromisoformat(first["leaves"]).astimezone(LOCAL_TIME).strftime("%H:%M")
    return f"{first['line']} ab {first['from']} um {leaves}"


def leave_plan(origin: str, destination: str, arrive_by: datetime, walk_minutes: int) -> dict | None:
    """When to leave, from live data. Returns None if no connection arrives in time."""
    found = trips(origin, destination, arrive_by)
    trip = best_trip(found.get("trips", []), arrive_by)
    if trip is None:
        return None
    leave = datetime.fromisoformat(trip["legs"][0]["leaves"]) - timedelta(minutes=walk_minutes)
    return {"leave": leave, "trip": describe_trip(trip)}
