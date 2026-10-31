from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

SCOPES = [
    "https://www.googleapis.com/auth/calendar.events",
    "https://www.googleapis.com/auth/tasks",
]


class NotLoggedIn(Exception):
    pass


def login(data_dir: Path) -> None:
    secrets = data_dir / "credentials.json"
    if not secrets.exists():
        raise SystemExit(f"Put your Google OAuth client file at {secrets} first (see README).")
    credentials = InstalledAppFlow.from_client_secrets_file(secrets, SCOPES).run_local_server(
        port=8765, open_browser=False
    )
    _save(data_dir / "token.json", credentials)


def _save(path: Path, credentials: Credentials) -> None:
    path.touch(mode=0o600, exist_ok=True)
    path.write_text(credentials.to_json())


def _credentials(data_dir: Path) -> Credentials:
    token = data_dir / "token.json"
    if not token.exists():
        raise NotLoggedIn("Google is not connected yet. Run: mitauri google-login")
    credentials = Credentials.from_authorized_user_file(token, SCOPES)
    if not credentials.valid:
        credentials.refresh(Request())
        _save(token, credentials)
    return credentials


def calendar(data_dir: Path):
    return build("calendar", "v3", credentials=_credentials(data_dir), cache_discovery=False)


def tasks(data_dir: Path):
    return build("tasks", "v1", credentials=_credentials(data_dir), cache_discovery=False)
