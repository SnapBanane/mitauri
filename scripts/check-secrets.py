#!/usr/bin/env python3
"""Stops a commit that contains a secret. It compares what's being committed with Mitauri's real secrets
and common token formats, and never prints a secret. Runs as a git pre-commit hook."""

import json
import re
import subprocess
import sys
from pathlib import Path

DATA = Path.home() / ".local/share/mitauri"
PATTERNS = re.compile(
    r"gh[opsu]_[A-Za-z0-9]{20,}|github_pat_\w{20,}|sk-ant-\w{10,}|AIza[\w-]{30,}|GOCSPX-[\w-]{10,}|ya29\.[\w-]{20,}"
    r"|xox[bp]-[\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
    r"|[MN][A-Za-z\d]{23}\.[\w-]{6}\.[\w-]{27,}"  # a Discord bot token
)
BLOCKED_NAME = re.compile(
    r"(^|/)(\.env|secrets\.env|credentials\.json|token\.json|internal_token|devices\.json|signing\.properties"
    r"|local\.properties|client_secret[^/]*\.json)$|\.(keystore|jks|db)$"
)


def real_secrets() -> set[str]:
    found = set()
    env = DATA / "secrets.env"
    if env.exists():
        for line in env.read_text().splitlines():
            name, _, value = line.partition("=")
            if name.endswith("TOKEN") and len(value.strip()) > 8:
                found.add(value.strip())
    for name in ("credentials.json", "token.json"):
        path = DATA / name
        if path.exists():
            found |= {v for v in re.findall(r'"(?:client_secret|refresh_token|token)":\s*"([^"]+)"', path.read_text()) if len(v) > 8}
    path = DATA / "internal_token"
    if path.exists():
        found.add(path.read_text().strip())
    path = DATA / "signing/signing.properties"
    if path.exists():
        found |= {line.split("=", 1)[1] for line in path.read_text().splitlines() if "Password" in line}
    return found


def problems(names_and_content: list[tuple[str, bytes]], secrets: set[str]) -> list[str]:
    found = []
    for name, content in names_and_content:
        text = content.decode(errors="ignore")
        if BLOCKED_NAME.search(name):
            found.append(f"{name}: this kind of file must not be committed")
        elif any(secret in text for secret in secrets):
            found.append(f"{name}: contains one of your real secrets")
        elif PATTERNS.search(text):
            found.append(f"{name}: looks like it contains a token or private key")
    return found


def staged() -> list[tuple[str, bytes]]:
    names = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"], capture_output=True, check=True
    ).stdout.decode().split("\0")
    return [(n, subprocess.run(["git", "show", f":{n}"], capture_output=True, check=True).stdout) for n in names if n]


def main() -> int:
    found = problems(staged(), real_secrets())
    for line in found:
        print(f"BLOCKED {line}", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
