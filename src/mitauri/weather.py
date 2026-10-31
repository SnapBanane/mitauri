"""Forecasts from Open-Meteo, which needs no account. It only ever gets a rough position."""

import json
from urllib.parse import urlencode
from urllib.request import urlopen

URL = "https://api.open-meteo.com/v1/forecast"
TIMEOUT_SECONDS = 15
HOURS = 12
CODES = {
    0: "klar", 1: "meist klar", 2: "teils bewölkt", 3: "bewölkt", 45: "Nebel", 48: "Nebel",
    51: "leichter Niesel", 53: "Niesel", 55: "starker Niesel", 56: "gefrierender Niesel", 57: "gefrierender Niesel",
    61: "leichter Regen", 63: "Regen", 65: "starker Regen", 66: "gefrierender Regen", 67: "gefrierender Regen",
    71: "leichter Schnee", 73: "Schnee", 75: "starker Schnee", 77: "Schneegriesel",
    80: "leichte Schauer", 81: "Schauer", 82: "starke Schauer", 85: "Schneeschauer", 86: "Schneeschauer",
    95: "Gewitter", 96: "Gewitter mit Hagel", 99: "Gewitter mit Hagel",
}


def rough(value: float) -> float:
    """About a kilometre, which is plenty for weather and doesn't say which house you're in."""
    return round(value, 2)


def parse_forecast(data: dict, now_hour: str | None = None) -> list[dict]:
    hourly = data.get("hourly", {})
    times = hourly.get("time", [])
    start = next((i for i, t in enumerate(times) if now_hour and t >= now_hour), 0)
    return [
        {
            "time": times[i],
            "temperature_c": hourly["temperature_2m"][i],
            "rain_chance_percent": hourly["precipitation_probability"][i],
            "weather": CODES.get(hourly["weather_code"][i], "unbekannt"),
        }
        for i in range(start, min(start + HOURS, len(times)))
    ]


def forecast(latitude: float, longitude: float, now_hour: str | None = None) -> list[dict]:
    query = urlencode({
        "latitude": rough(latitude), "longitude": rough(longitude),
        "hourly": "temperature_2m,precipitation_probability,weather_code",
        "forecast_days": 2, "timezone": "Europe/Berlin",
    })
    with urlopen(f"{URL}?{query}", timeout=TIMEOUT_SECONDS) as response:
        return parse_forecast(json.load(response), now_hour)
