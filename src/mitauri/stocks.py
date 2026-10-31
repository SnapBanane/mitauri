"""Stock quotes from Stooq, which needs no account. They're delayed, so they're not for trading."""

import csv
import io
from urllib.parse import urlencode
from urllib.request import urlopen

URL = "https://stooq.com/q/l/"
TIMEOUT_SECONDS = 15
MAX_SYMBOLS = 10


def parse_quotes(text: str) -> list[dict]:
    quotes = []
    for row in csv.DictReader(io.StringIO(text)):
        close = row.get("Close")
        if not close or close == "N/D":
            quotes.append({"symbol": row.get("Symbol"), "error": "No data for this symbol."})
            continue
        opened = float(row["Open"])
        quotes.append({
            "symbol": row["Symbol"],
            "name": row.get("Name"),
            "price": float(close),
            "change_today_percent": round((float(close) - opened) / opened * 100, 2) if opened else None,
            "as_of": f"{row.get('Date')} {row.get('Time')}",
        })
    return quotes


def quotes(symbols: list[str]) -> list[dict]:
    query = urlencode({"s": ",".join(s.lower() for s in symbols[:MAX_SYMBOLS]), "f": "sd2t2nocv", "h": "", "e": "csv"})
    with urlopen(f"{URL}?{query}", timeout=TIMEOUT_SECONDS) as response:
        return parse_quotes(response.read().decode(errors="replace"))
