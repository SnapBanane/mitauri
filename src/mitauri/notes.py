import sqlite3
import time
from pathlib import Path


class Notes:
    """Quick notes, optionally with the place they were made, like where the car is parked."""

    def __init__(self, path: Path):
        self._db = sqlite3.connect(path)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, text TEXT NOT NULL, "
            "latitude REAL, longitude REAL, created REAL NOT NULL)"
        )
        self._db.commit()

    def add(self, text: str, latitude: float | None = None, longitude: float | None = None) -> int:
        with self._db:
            return self._db.execute(
                "INSERT INTO notes (text, latitude, longitude, created) VALUES (?, ?, ?, ?)",
                (text, latitude, longitude, time.time()),
            ).lastrowid

    def find(self, query: str | None = None, limit: int = 20) -> list[dict]:
        rows = self._db.execute(
            "SELECT id, text, latitude, longitude, created FROM notes "
            "WHERE instr(lower(text), lower(?)) > 0 ORDER BY id DESC LIMIT ?",
            (query or "", limit),
        ).fetchall()
        return [
            {"id": r[0], "text": r[1], "latitude": r[2], "longitude": r[3], "created": int(r[4])}
            for r in rows
        ]

    def remove(self, note_id: int) -> bool:
        with self._db:
            return self._db.execute("DELETE FROM notes WHERE id = ?", (note_id,)).rowcount > 0

    def count(self) -> int:
        return self._db.execute("SELECT COUNT(*) FROM notes").fetchone()[0]
