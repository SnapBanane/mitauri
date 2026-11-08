"""mitauri doctor: checks this setup for mistakes that would weaken it, like a secret others can read or the server
listening on the open internet. It only reads, and prints nothing secret."""

import ipaddress
import os
import shutil
import stat
import time
from datetime import datetime
from pathlib import Path

from . import modules, proposals, reflection
from .config import SECRETS_FILE
from .skills import SkillError, Skills, check

OK, WARN, FAIL = "ok", "!", "x"
TAILSCALE = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))
FAILURE_DAYS = 7


def _mode(path: Path) -> int:
    return stat.S_IMODE(path.stat().st_mode)


def checks(data_dir: Path, env: dict | None = None) -> list[tuple[str, str]]:
    env = os.environ if env is None else env
    found: list[tuple[str, str]] = []

    def add(level: str, text: str) -> None:
        found.append((level, text))

    if _mode(data_dir) & 0o077:
        add(FAIL, f"The data folder can be read by other users. Fix: chmod 700 {data_dir}")
    else:
        add(OK, "The data folder is private.")
    secrets = data_dir / SECRETS_FILE
    if not secrets.exists():
        add(WARN, f"There's no {SECRETS_FILE} yet.")
    elif _mode(secrets) & 0o077:
        add(FAIL, f"{SECRETS_FILE} can be read by other users. Fix: chmod 600 {secrets}")
    else:
        add(OK, f"{SECRETS_FILE} is private.")

    if env.get("ANTHROPIC_API_KEY") or env.get("ANTHROPIC_AUTH_TOKEN"):
        add(WARN, "An Anthropic API key is set here. Mitauri removes it before calling Claude, but other tools would bill it.")
    claude = shutil.which(env.get("MITAURI_CLAUDE_BIN", "claude"))
    add(OK if claude else FAIL, "Claude Code is installed." if claude else "Claude Code isn't on the PATH.")

    host = env.get("MITAURI_API_HOST", "")
    if not host:
        add(WARN, "MITAURI_API_HOST isn't set, so only this machine can reach the server and the phone can't pair.")
    else:
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            add(WARN, f"MITAURI_API_HOST is a name, not an address, so it can't be checked: {host}")
        else:
            if address.is_unspecified:
                add(FAIL, "The server listens on every network. Set MITAURI_API_HOST to this machine's Tailscale address.")
            elif any(address in network for network in TAILSCALE if network.version == address.version) or address.is_loopback:
                add(OK, "The server only listens on Tailscale.")
            elif address.is_private:
                add(WARN, "The server listens on the local network, where others on it can reach it. Tailscale is safer.")
            else:
                add(FAIL, "The server listens on a public address. Use the Tailscale address instead.")
    if not (env.get("MITAURI_DISCORD_TOKEN") and env.get("MITAURI_DISCORD_USER_ID")):
        add(WARN, "Discord isn't set up, so `mitauri run` won't start.")

    powerful = [m for m in ("door", "notifications", "selfbuild", "watch") if modules.is_enabled(data_dir, m)]
    if powerful:
        add(OK, f"Modules that act for you, each only when you ask: {', '.join(powerful)}.")

    _check_skills(data_dir, add)
    _check_proposals(data_dir, add)
    _check_failures(data_dir, add)
    _check_review(data_dir, env, add)
    return found


def _check_skills(data_dir: Path, add) -> None:
    path = data_dir / "skills.db"
    if not modules.is_enabled(data_dir, "skills") or not path.exists():
        return
    found = Skills(path).all()
    broken = []
    for skill in found:
        try:
            check(skill["name"], skill["when"], skill["steps"])
        except SkillError:
            broken.append(skill["name"])
    if broken:
        add(FAIL, f"These skills break the current rules: {', '.join(broken)}. Remove them: mitauri skills forget <name>")
    else:
        add(OK, f"{len(found)} skills, all within the rules.")


def _check_proposals(data_dir: Path, add) -> None:
    waiting = [p for p in proposals.all_proposals(data_dir) if p["status"] == "ready"]
    stuck = [p for p in proposals.all_proposals(data_dir)
             if p["status"] == "running" and time.time() - p["created"] > 3 * proposals.RUN_TIMEOUT_SECONDS
             and not p.get("rounds")]
    if waiting:
        add(WARN, f"{len(waiting)} proposals wait for your review: mitauri proposal list")
    if stuck:
        add(WARN, f"These proposals seem stuck: {', '.join(p['id'] for p in stuck)}. Discard them if so.")


def _check_failures(data_dir: Path, add) -> None:
    failures = reflection.tool_failures(data_dir, time.time() - FAILURE_DAYS * 86_400)
    if not failures:
        add(OK, f"No tool failed in the last {FAILURE_DAYS} days.")
        return
    worst = sorted(failures.items(), key=lambda item: -item[1])[:5]
    add(WARN, f"Tools that failed in the last {FAILURE_DAYS} days: " + ", ".join(f"{n} ({c}x)" for n, c in worst))


def _check_review(data_dir: Path, env: dict, add) -> None:
    if not modules.is_enabled(data_dir, "skills"):
        add(OK, "Learning is off.")
        return
    if not modules.is_enabled(data_dir, "history"):
        add(WARN, "The nightly review needs Long-term memory on, as it learns from saved chats.")
        return
    when = env.get("MITAURI_REFLECT_TIME", "")
    if not when:
        add(OK, "The nightly review is off.")
        return
    last = data_dir / reflection.LAST_DATE_FILE
    ran = last.read_text() if last.exists() else "never"
    add(OK, f"The nightly review runs at {when}, last on {ran}.")


def report(data_dir: Path) -> int:
    """Prints the checks. Returns the exit code: 1 if anything needs fixing."""
    results = checks(data_dir)
    for level, text in results:
        print(f"[{level}] {text}")
    failed = sum(level == FAIL for level, _ in results)
    print(f"\n{datetime.now():%Y-%m-%d %H:%M}: " + (f"{failed} to fix." if failed else "Nothing to fix."))
    return 1 if failed else 0
