import sqlite3
from datetime import datetime
from pathlib import Path

MESSAGE = "message"
CALL = "call"
TRIP = "trip"  # a leave-now alert, re-checked against live data before it fires


class Reminders:
    """What to send the user at a set time, as a message or a call. Times are UTC timestamps."""

    def __init__(self, path: Path):
        self._db = sqlite3.connect(path)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS reminders "
            "(id INTEGER PRIMARY KEY, text TEXT NOT NULL, due REAL NOT NULL, kind TEXT NOT NULL DEFAULT 'message')"
        )
        columns = [row[1] for row in self._db.execute("PRAGMA table_info(reminders)")]
        if "kind" not in columns:
            self._db.execute("ALTER TABLE reminders ADD COLUMN kind TEXT NOT NULL DEFAULT 'message'")
        self._db.commit()

    def add(self, text: str, when: datetime, kind: str = MESSAGE) -> int:
        with self._db:
            return self._db.execute(
                "INSERT INTO reminders (text, due, kind) VALUES (?, ?, ?)", (text, when.timestamp(), kind)
            ).lastrowid

    def remove(self, reminder_id: int) -> bool:
        with self._db:
            return self._db.execute(
                "DELETE FROM reminders WHERE id = ?", (reminder_id,)
            ).rowcount > 0

    def remove_matching(self, reminder_id: int, text: str) -> bool:
        """For an id kept elsewhere: ids get reused once a reminder has fired, so the text must match too."""
        with self._db:
            return self._db.execute(
                "DELETE FROM reminders WHERE id = ? AND text = ?", (reminder_id, text)
            ).rowcount > 0

    def due(self, now: datetime) -> list[tuple[int, str, str, float]]:
        return self._db.execute(
            "SELECT id, text, kind, due FROM reminders WHERE due <= ? ORDER BY due", (now.timestamp(),)
        ).fetchall()

    def pending(self) -> list[tuple[int, str, float, str]]:
        return self._db.execute("SELECT id, text, due, kind FROM reminders ORDER BY due").fetchall()
