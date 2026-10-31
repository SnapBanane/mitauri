from datetime import datetime
from zoneinfo import ZoneInfo

from .. import geo, toolbox, weather

tool = toolbox.tool_for("weather")


@tool
def get_weather(place: str | None = None) -> dict:
    """Hourly forecast for the next 12 hours, for a place, or else for where the phone is."""
    try:
        if place:
            found = geo.search(place, toolbox.phone_position(), limit=1)
            if not found:
                return {"error": f"I couldn't find {place}."}
            latitude, longitude = found[0]["latitude"], found[0]["longitude"]
        else:
            position = toolbox.ask_server("/location")
            if "latitude" not in position:
                return position
            latitude, longitude = position["latitude"], position["longitude"]
        now_hour = datetime.now(ZoneInfo("Europe/Berlin")).strftime("%Y-%m-%dT%H:00")
        return {"hours": weather.forecast(latitude, longitude, now_hour)}
    except (OSError, ValueError):
        return {"error": "The weather service couldn't be reached."}
