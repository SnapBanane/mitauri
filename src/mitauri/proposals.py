"""Mitauri suggesting changes to itself. A proposal is written in an isolated copy, by a Claude that
can only read and edit that copy, with no shell. Applying one only works from a terminal on this
machine, after a person has read it, so nothing on the phone or in a message can ever change the code."""

import difflib
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

from .config import data_dir

PROJECT = Path(__file__).resolve().parents[2]
SKIPPED = {".venv", ".gradle", ".pio", "build", "__pycache__", ".pytest_cache", ".git", ".idea", ".env", "local.properties"}
SKIPPED_SUFFIXES = (".keystore", ".jks", ".apk", ".png", ".jar", ".env")
MAX_REQUEST_CHARS = 2_000
MAX_PER_DAY = 5
CLOCK_MARGIN_SECONDS = 0.05
RUN_TIMEOUT_SECONDS = 900
PROMPT = """You're improving Mitauri, a private personal assistant. Make this change in the current folder:

{request}

Rules: only edit files here. Keep every security protection in the README's Security section. Don't add
network calls to new hosts, new dependencies, or ways to run commands. Add tests when you change behaviour.
You can't run anything. End with a short summary, in German, of what you changed and what still needs testing."""


def _root(directory: Path | None = None) -> Path:
    """Beside the data folder, not in it, so that folder can be blocked completely."""
    base = directory or data_dir()
    return base.parent / f"{base.name}-proposals"


def _skip(path: Path) -> bool:
    return bool(SKIPPED & set(path.parts)) or path.name.endswith(SKIPPED_SUFFIXES)


def project_files(root: Path) -> list[Path]:
    return sorted(p.relative_to(root) for p in root.rglob("*") if p.is_file() and not _skip(p.relative_to(root)))


def _state(proposal: Path) -> dict:
    return json.loads((proposal / "state.json").read_text())


def _save(proposal: Path, **changes) -> None:
    (proposal / "state.json").write_text(json.dumps({**_state(proposal), **changes}))


def all_proposals(directory: Path | None = None) -> list[dict]:
    root = _root(directory)
    if not root.exists():
        return []
    found = [p for p in root.iterdir() if (p / "state.json").exists()]
    return sorted((_state(p) | {"id": p.name} for p in found), key=lambda s: s["created"])


def create(request: str, directory: Path | None = None, project: Path = PROJECT) -> tuple[str | None, str]:
    """Makes the isolated copy. Returns the new proposal's id, or None and why it wasn't made."""
    if len(request) > MAX_REQUEST_CHARS:
        return None, "That request is too long."
    existing = all_proposals(directory)
    if any(p["status"] == "running" for p in existing):
        return None, "Another proposal is still being written."
    if sum(p["created"] > time.time() - 86_400 for p in existing) >= MAX_PER_DAY:
        return None, "That's the limit of proposals for today."
    proposal_id = uuid.uuid4().hex[:8]
    started = time.time()
    proposal = _root(directory) / proposal_id
    copy = proposal / "code"
    for relative in project_files(project):
        target = copy / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(project / relative, target)
    (proposal / "state.json").write_text(json.dumps({
        "request": request, "status": "running", "created": started, "summary": "", "told": False,
    }))
    return proposal_id, ""


def _blocked_paths() -> list[str]:
    """Anything outside the copy is already refused. These block the worst places a second time."""
    places = [f"//{str(data_dir()).lstrip('/')}/**", "~/.ssh/**", "~/.claude/**", "~/.config/**", "~/.gnupg/**"]
    return [f"{tool}({place})" for place in places for tool in ("Read", "Edit", "Write", "Glob", "Grep")]


def run(proposal_id: str) -> None:
    """Runs in its own process, so a long proposal doesn't hold up anything else."""
    proposal = _root() / proposal_id
    env = {k: v for k, v in os.environ.items() if k not in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")}
    env["CLAUDE_CODE_DISABLE_ADVISOR_TOOL"] = "1"
    try:
        result = subprocess.run(
            [
                os.environ.get("MITAURI_CLAUDE_BIN", "claude"), "-p",
                "--model", os.environ.get("MITAURI_PROPOSAL_MODEL", "opus"),
                "--tools", "Read,Edit,Write,Glob,Grep",
                "--allowedTools", "Read(./**)", "Edit(./**)", "Write(./**)", "Glob(./**)", "Grep(./**)",
                "--disallowedTools", *_blocked_paths(),
                "--strict-mcp-config", "--setting-sources", "",
                "--no-session-persistence",
            ],
            input=PROMPT.format(request=_state(proposal)["request"]),
            capture_output=True, text=True, timeout=RUN_TIMEOUT_SECONDS, cwd=proposal / "code", env=env,
        )
        ok = result.returncode == 0
        _save(proposal, status="ready" if ok else "failed", summary=result.stdout.strip()[-2000:])
    except (OSError, subprocess.TimeoutExpired):
        _save(proposal, status="failed", summary="It stopped before finishing.")


def start(proposal_id: str) -> None:
    subprocess.Popen(
        [sys.executable, "-m", "mitauri.proposals", proposal_id],
        start_new_session=True, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def unreported(directory: Path | None = None) -> list[dict]:
    return [p for p in all_proposals(directory) if p["status"] != "running" and not p["told"]]


def mark_told(proposal_id: str, directory: Path | None = None) -> None:
    _save(_root(directory) / proposal_id, told=True)


def changes(proposal_id: str, directory: Path | None = None, project: Path = PROJECT) -> dict[str, tuple[str | None, str | None]]:
    """What the proposal itself changed, as (current text, proposed text). None means absent.

    The copy keeps each file's original time, so a newer one was edited by the proposal. Anything
    else that differs is the project moving on since, and applying must not undo that.
    """
    proposal = _root(directory) / proposal_id
    code = proposal / "code"
    started = _state(proposal)["created"] - CLOCK_MARGIN_SECONDS
    in_copy = set(project_files(code))
    touched = {r for r in in_copy if (code / r).stat().st_mtime >= started}
    touched |= {r for r in project_files(project) if r not in in_copy and (project / r).stat().st_mtime < started}
    found = {}
    for relative in touched:
        old_path, new_path = project / relative, code / relative
        old = old_path.read_text(errors="replace") if old_path.exists() else None
        new = new_path.read_text(errors="replace") if new_path.exists() else None
        if old != new:
            found[str(relative)] = (old, new)
    return found


def conflicts(proposal_id: str, directory: Path | None = None, project: Path = PROJECT) -> list[str]:
    """Files the proposal changed that were also changed in the project since it started."""
    started = _state(_root(directory) / proposal_id)["created"] - CLOCK_MARGIN_SECONDS
    return sorted(
        name for name in changes(proposal_id, directory, project)
        if (project / name).exists() and (project / name).stat().st_mtime >= started
    )


def diff(proposal_id: str, directory: Path | None = None, project: Path = PROJECT) -> str:
    parts = []
    for name, (old, new) in sorted(changes(proposal_id, directory, project).items()):
        parts += difflib.unified_diff(
            (old or "").splitlines(True), (new or "").splitlines(True), f"a/{name}", f"b/{name}"
        )
    return "".join(parts)


def apply(proposal_id: str, directory: Path | None = None, project: Path = PROJECT) -> list[str]:
    """Copies the proposal into the project. Only the terminal command calls this."""
    proposal = _root(directory) / proposal_id
    if _state(proposal)["status"] != "ready":
        raise ValueError("That proposal isn't ready.")
    clashes = conflicts(proposal_id, directory, project)
    if clashes:
        raise ValueError(f"These changed since the proposal started, so applying would undo that: {', '.join(clashes)}")
    changed = changes(proposal_id, directory, project)
    for name, (_, new) in changed.items():
        target = project / name
        if new is None:
            target.unlink()
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(proposal / "code" / name, target)
    _save(proposal, status="applied", told=True)
    return sorted(changed)


def discard(proposal_id: str, directory: Path | None = None) -> None:
    shutil.rmtree(_root(directory) / proposal_id)


if __name__ == "__main__":
    run(sys.argv[1])
