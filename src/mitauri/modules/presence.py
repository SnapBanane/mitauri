from .. import toolbox
from . import places

tool = toolbox.tool_for("presence")

MAX_NAME_CHARS = 60


@tool
def presence() -> dict:
    """Whether the user's phone and laptop are near each Bluetooth sensor, with a rough distance.

    The phone has its own distance to each sensor. Sensors are named for where they sit: desk is the user's desk at work, home is at home.
    A beacon named bag is in the user's bag, so phone or laptop hearing it means the bag is within reach of them.
    A sensor in the "unknown" state says nothing about where the user is. "not_connected" means that phone or laptop
    isn't reporting, usually because it's off, so say it's not connected. Only for answering the user. Bluetooth being
    off looks the same as being away, so say that if it matters.
    """
    return toolbox.ask_server("/presence")


@tool
def add_quiet_place(name: str, place: str | None = None, radius_metres: int = 200, allow_far: bool = False) -> str:
    """Add a place where the user doesn't need warning that their bag was left behind, like home, school or a friend's.

    While the phone is there, the bag check rests. Leaving starts it again, so a bag left there is still noticed.
    Leave place empty for where the user is now, which is best when they're there. Otherwise it picks the closest
    match to the phone: always tell the user which place was found, and pass allow_far only after they confirmed a
    place reported as far away. Use a larger radius for a big site like a school. The place stays only on the phone.
    """
    radius = max(places.MIN_PLACE_RADIUS_METRES, min(radius_metres, places.MAX_PLACE_RADIUS_METRES))
    label = name.strip()[:MAX_NAME_CHARS] or "Place"
    found_name = "the current location"
    if place:
        try:
            found, problem = places._closest_place(place, allow_far)
        except (OSError, ValueError):
            return "Not added: the place lookup couldn't be reached."
        if found is None:
            return f"Not added: {problem}"
        latitude, longitude, found_name = found["latitude"], found["longitude"], found["name"]
    else:
        position = toolbox.ask_server("/location")
        if "latitude" not in position:
            return f"Not added: {position.get('error', 'no location')}"
        latitude, longitude = position["latitude"], position["longitude"]
    answer = toolbox.ask_server("/quiet-places", {"action": "add", "name": label, "latitude": latitude, "longitude": longitude, "radius": radius})
    return f"{answer.get('result', answer.get('error', 'Not added.'))} Place: {found_name}, {radius} m around it."


@tool
def list_quiet_places() -> dict:
    """List the places where the bag check rests, and whether the phone is at one now."""
    return toolbox.ask_server("/quiet-places", {"action": "list"})


@tool
def remove_quiet_place(place_id: str) -> dict:
    """Remove a quiet place by its id, so the bag check runs there again."""
    return toolbox.ask_server("/quiet-places", {"action": "remove", "place": place_id})
