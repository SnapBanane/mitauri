import sqlite3
from pathlib import Path


class Memory:
    """Long-term facts about the user, kept across conversations."""

    def __init__(self, path: Path):
        self._db = sqlite3.connect(path)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS facts (id INTEGER PRIMARY KEY, text TEXT NOT NULL)"
        )
        self._db.commit()

    def add(self, text: str) -> int:
        with self._db:
            return self._db.execute("INSERT INTO facts (text) VALUES (?)", (text,)).lastrowid

    def remove(self, fact_id: int) -> bool:
        with self._db:
            return self._db.execute("DELETE FROM facts WHERE id = ?", (fact_id,)).rowcount > 0

    def all(self) -> list[tuple[int, str]]:
        return self._db.execute("SELECT id, text FROM facts ORDER BY id").fetchall()
