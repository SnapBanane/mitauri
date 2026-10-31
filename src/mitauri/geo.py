"""Finding places and travel times with OpenStreetMap's free public services. They ask for light use, so
requests are spaced out and repeated. Only the search text and, if asked, a rough position are sent."""

import json
import math
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

SEARCH_URL = "https://nominatim.openstreetmap.org/search"
ROUTE_URL = "https://routing.openstreetmap.de/routed-{mode}/route/v1/driving/{start};{end}?overview=false"
HEADERS = {"User-Agent": "Mitauri personal assistant (private, low volume)"}
ATTRIBUTION = "Data © OpenStreetMap contributors"
TIMEOUT_SECONDS = 15
MIN_SECONDS_BETWEEN_SEARCHES = 1.1
CACHE_SECONDS = 3600
SEARCH_BOX_DEGREES = 0.3
ROUTE_MODES = {"driving": "car", "cycling": "bike", "walking": "foot"}

_last_search = 0.0
_cache: dict[tuple, tuple[float, list[dict]]] = {}


def rough(value: float) -> float:
    return round(value, 2)


def distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    """In a straight line. Real routes are longer."""
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(h))


def _get(url: str) -> object:
    with urlopen(Request(url, headers=HEADERS), timeout=TIMEOUT_SECONDS) as response:
        return json.load(response)


def parse_places(results: list, near: tuple[float, float] | None) -> list[dict]:
    places = []
    for item in results:
        latitude, longitude = float(item["lat"]), float(item["lon"])
        place = {"name": item.get("display_name", ""), "type": item.get("type"), "latitude": latitude, "longitude": longitude}
        if near:
            place["distance_km"] = round(distance_km(near, (latitude, longitude)), 2)
        places.append(place)
    return sorted(places, key=lambda p: p["distance_km"]) if near else places


def search(query: str, near: tuple[float, float] | None = None, limit: int = 5) -> list[dict]:
    """Near a position, results inside the surrounding area come first. Distances use the exact position
    on this machine, but only a rough one is sent."""
    global _last_search
    params = {"q": query[:200], "format": "jsonv2", "limit": limit, "countrycodes": "de,at,ch,nl,be,fr,lu"}
    if near:
        latitude, longitude = rough(near[0]), rough(near[1])
        params["viewbox"] = (
            f"{longitude - SEARCH_BOX_DEGREES},{latitude + SEARCH_BOX_DEGREES},"
            f"{longitude + SEARCH_BOX_DEGREES},{latitude - SEARCH_BOX_DEGREES}"
        )
        params["bounded"] = 1
    key = tuple(sorted(params.items()))
    cached = _cache.get(key)
    if cached and cached[0] > time.time():
        return parse_places(cached[1], near)
    wait = _last_search + MIN_SECONDS_BETWEEN_SEARCHES - time.time()
    if wait > 0:
        time.sleep(wait)
    _last_search = time.time()
    results = _get(f"{SEARCH_URL}?{urlencode(params)}")
    _cache[key] = (time.time() + CACHE_SECONDS, results)
    return parse_places(results, near)


def route(start: tuple[float, float], end: tuple[float, float], mode: str = "driving") -> dict | None:
    """Typical travel time. It knows nothing about current traffic."""
    url = ROUTE_URL.format(
        mode=ROUTE_MODES[mode],
        start=f"{rough(start[1])},{rough(start[0])}",
        end=f"{end[1]},{end[0]}",
    )
    data = _get(url)
    routes = data.get("routes") if isinstance(data, dict) else None
    if not routes:
        return None
    return {"km": round(routes[0]["distance"] / 1000, 1), "minutes": round(routes[0]["duration"] / 60)}
