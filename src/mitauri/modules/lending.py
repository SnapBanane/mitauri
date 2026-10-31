import sqlite3
import time

from .. import toolbox
from ..config import data_dir

MAX_TEXT_CHARS = 200
MAX_OPEN = 200

tool = toolbox.tool_for("lending")


def _db() -> sqlite3.Connection:
    db = sqlite3.connect(data_dir() / "lending.db")
    db.execute(
        "CREATE TABLE IF NOT EXISTS loans (id INTEGER PRIMARY KEY AUTOINCREMENT, what TEXT NOT NULL, person TEXT NOT NULL, "
        "i_lent INTEGER NOT NULL, created REAL NOT NULL)"
    )
    return db


@tool
def record_loan(what: str, person: str, i_lent: bool = True) -> str:
    """Note something lent or borrowed, like a drill or 20 €. i_lent is False if the user borrowed it from person."""
    if len(what) > MAX_TEXT_CHARS or len(person) > MAX_TEXT_CHARS:
        return "Not saved: that's too long. Shorten it."
    with _db() as db:
        if db.execute("SELECT COUNT(*) FROM loans").fetchone()[0] >= MAX_OPEN:
            return "Not saved: too many open loans. Ask the user which are settled."
        loan = db.execute(
            "INSERT INTO loans (what, person, i_lent, created) VALUES (?, ?, ?, ?)", (what, person, i_lent, time.time())
        ).lastrowid
    return f"Saved as [{loan}]."


@tool
def list_loans() -> list[dict]:
    """List what's still lent out or borrowed. created is a Unix timestamp."""
    return [
        {"id": row[0], "what": row[1], "person": row[2], "direction": "lent to them" if row[3] else "borrowed from them", "created": int(row[4])}
        for row in _db().execute("SELECT id, what, person, i_lent, created FROM loans ORDER BY id")
    ]


@tool
def settle_loan(loan_id: int) -> str:
    """Remove a loan once it's been returned or paid back."""
    with _db() as db:
        removed = db.execute("DELETE FROM loans WHERE id = ?", (loan_id,)).rowcount > 0
    return "Settled." if removed else "No loan has that id."
