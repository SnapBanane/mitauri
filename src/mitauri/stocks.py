"""Stock quotes from Yahoo Finance's public chart data, which needs no account but is unofficial, so it
could change without warning. Prices can be delayed, so they're not for trading."""

import json
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

URL = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
HEADERS = {"User-Agent": "Mozilla/5.0"}
TIMEOUT_SECONDS = 15
MAX_SYMBOLS = 10


def parse_quote(symbol: str, data: dict) -> dict:
    results = (data.get("chart") or {}).get("result") or []
    if not results:
        return {"symbol": symbol, "error": "No data for this symbol."}
    meta = results[0]["meta"]
    price, previous = meta["regularMarketPrice"], meta.get("chartPreviousClose")
    return {
        "symbol": meta.get("symbol", symbol),
        "name": meta.get("longName") or meta.get("shortName"),
        "price": price,
        "currency": meta.get("currency"),
        "change_percent": round((price - previous) / previous * 100, 2) if previous else None,
        "as_of": datetime.fromtimestamp(meta["regularMarketTime"], timezone.utc).isoformat(),
    }


def quotes(symbols: list[str]) -> list[dict]:
    found = []
    for symbol in symbols[:MAX_SYMBOLS]:
        request = Request(URL.format(symbol=quote(symbol.upper(), safe="")), headers=HEADERS)
        try:
            with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                found.append(parse_quote(symbol, json.load(response)))
        except HTTPError as error:
            if error.code != 404:
                raise
            found.append({"symbol": symbol, "error": "No data for this symbol."})
    return found
