import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv


API_PORT = 8766
SECRETS_FILE = "secrets.env"
LOCATION_TIMEOUT_SECONDS = 20


@dataclass(frozen=True)
class Config:
    data_dir: Path
    timezone: str
    model: str
    claude_bin: str
    briefing_time: str = ""  # HH:MM, empty turns the morning briefing off
    thinking_model: str = "sonnet"
    reflect_time: str = "03:30"  # HH:MM for the nightly review, empty turns it off


def data_dir() -> Path:
    os.umask(0o077)  # everything Mitauri creates is private to this user
    path = Path(os.environ.get("MITAURI_DATA_DIR", "~/.local/share/mitauri")).expanduser()
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    return path


def lock_down(directory: Path) -> None:
    """Fixes the permissions of files created before everything was private."""
    directory.chmod(0o700)
    for path in directory.rglob("*"):
        path.chmod(0o700 if path.is_dir() else 0o600)


def move_secrets(old: Path, directory: Path) -> None:
    """Secrets belong with the private data, not in a project folder that could get shared."""
    if old.exists() and not (directory / SECRETS_FILE).exists():
        old.replace(directory / SECRETS_FILE)


def new_token(directory: Path) -> None:
    """Replaces the token between Mitauri's processes, so an old copy of it stops working."""
    (directory / "internal_token").unlink(missing_ok=True)
    load_token(directory)


def load_token(directory: Path) -> str:
    """The secret between Mitauri's own processes on this machine. The phone never sees it."""
    path = directory / "internal_token"
    if not path.exists():
        path.touch(mode=0o600)
        path.write_text(secrets.token_hex(32))
    return path.read_text()


def load_config() -> Config:
    directory = data_dir()
    move_secrets(Path(__file__).resolve().parents[2] / ".env", directory)  # Mitauri's own folder only
    lock_down(directory)
    load_dotenv(directory / SECRETS_FILE)
    (directory / "api_token").unlink(missing_ok=True)  # the old token shared with the phone
    return Config(
        data_dir=directory,
        timezone=os.environ.get("MITAURI_TIMEZONE", "Europe/Berlin"),
        model=os.environ.get("MITAURI_MODEL", "haiku"),
        claude_bin=os.environ.get("MITAURI_CLAUDE_BIN", "claude"),
        briefing_time=os.environ.get("MITAURI_BRIEFING_TIME", ""),
        thinking_model=os.environ.get("MITAURI_THINKING_MODEL", "sonnet"),
        reflect_time=os.environ.get("MITAURI_REFLECT_TIME", "03:30"),
    )
