"""Long-term memory of past conversations, searched by meaning. Everything stays on this machine. The model runs
locally, so searching sends nothing out, though what a search finds then goes to Claude to answer you.

Comparing against every stored message is exact, and takes milliseconds at this scale, so it needs no index."""

import re
import sqlite3
import time
from pathlib import Path

import numpy as np

from .config import data_dir

MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MAX_TEXT_CHARS = 2_000
RESULTS = 6
MIN_SIMILARITY = 0.25  # low on purpose: Claude judges relevance, and scores alone can't separate them
SENSITIVE = re.compile(
    r"(?i)\b(passwort|kennwort|password|tan|pin|otp|2fa|iban|kreditkarte|credit card|sicherheitscode|verification code)\b"
)

_model = None


def _embed(texts: list[str]) -> np.ndarray:
    global _model
    if _model is None:
        from fastembed import TextEmbedding
        _model = TextEmbedding(MODEL, cache_dir=str(data_dir() / "models"))  # the default is wiped on reboot
    vectors = np.array(list(_model.embed(texts)), dtype=np.float32)
    return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


class ChatMemory:
    """The server only stores text. Vectors are made when searching, so the server needs no model in memory."""

    def __init__(self, path: Path, embed=_embed):
        self._embed = embed
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS turns (id INTEGER PRIMARY KEY, time REAL NOT NULL, you TEXT NOT NULL, "
            "mitauri TEXT NOT NULL, vector BLOB)"
        )
        self._db.commit()

    def add(self, you: str, mitauri: str, when: float | None = None) -> bool:
        """Skips anything that looks like it holds a password or code, so those are never kept."""
        if not you.strip() or SENSITIVE.search(you) or SENSITIVE.search(mitauri):
            return False
        with self._db:
            self._db.execute(
                "INSERT INTO turns (time, you, mitauri) VALUES (?, ?, ?)",
                (when or time.time(), you[:MAX_TEXT_CHARS], mitauri[:MAX_TEXT_CHARS]),
            )
        return True

    def _embed_new(self) -> None:
        rows = self._db.execute("SELECT id, you, mitauri FROM turns WHERE vector IS NULL").fetchall()
        if not rows:
            return
        vectors = self._embed([f"{you}\n{mitauri}" for _, you, mitauri in rows])
        with self._db:
            self._db.executemany(
                "UPDATE turns SET vector = ? WHERE id = ?",
                [(vector.tobytes(), row[0]) for vector, row in zip(vectors, rows)],
            )

    def search(self, query: str, limit: int = RESULTS) -> list[dict]:
        self._embed_new()
        rows = self._db.execute("SELECT id, time, you, mitauri, vector FROM turns").fetchall()
        if not rows:
            return []
        matrix = np.stack([np.frombuffer(row[4], dtype=np.float32) for row in rows])
        scores = matrix @ self._embed([query[:MAX_TEXT_CHARS]])[0]
        best = np.argsort(-scores)[:limit]
        return [
            {"when": int(rows[i][1]), "you_said": rows[i][2], "mitauri_said": rows[i][3], "match": round(float(scores[i]), 2)}
            for i in best if scores[i] >= MIN_SIMILARITY
        ]

    def count(self) -> int:
        return self._db.execute("SELECT COUNT(*) FROM turns").fetchone()[0]

    def clear(self) -> None:
        with self._db:
            self._db.execute("DELETE FROM turns")
        self._db.execute("VACUUM")  # so deleted text doesn't linger in the file
