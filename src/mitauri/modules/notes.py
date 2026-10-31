from .. import toolbox
from ..config import data_dir
from ..notes import Notes

MAX_TEXT_CHARS = 500
MAX_NOTES = 1_000

tool = toolbox.tool_for("notes")


@tool
def save_note(text: str, with_location: bool = False) -> str:
    """Save a quick note. Use with_location for places like where the car is parked."""
    notes = Notes(data_dir() / "notes.db")
    if len(text) > MAX_TEXT_CHARS:
        return "Not saved: that's too long. Shorten it."
    if notes.count() >= MAX_NOTES:
        return "Not saved: notes are full. Ask the user what to delete."
    latitude = longitude = None
    if with_location:
        place = toolbox.ask_server("/location")
        if "latitude" not in place:
            return f"Not saved: {place.get('error', 'no location')}"
        latitude, longitude = place["latitude"], place["longitude"]
    return f"Saved as note [{notes.add(text, latitude, longitude)}]."


@tool
def find_notes(query: str | None = None) -> list[dict]:
    """Find saved notes containing some text, or the latest ones. Times are Unix timestamps."""
    return Notes(data_dir() / "notes.db").find(query)


@tool
def delete_note(note_id: int) -> str:
    """Delete a saved note by its id."""
    return "Deleted." if Notes(data_dir() / "notes.db").remove(note_id) else "No note has that id."
