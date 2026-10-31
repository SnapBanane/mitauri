"""What every module's tools share: the tool server, the audit trail, and the way to reach the phone."""

import functools
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from mcp.server.fastmcp import FastMCP

from . import modules
from .audit import record
from .config import API_PORT, LOCATION_TIMEOUT_SECONDS, data_dir, load_token

server = FastMCP("mitauri")
limited_tools: set[str] = set()


def tool_for(module: str, limited: list[str] | None = None):
    """A decorator for one module's tools. Whatever else happens, they stay plain functions."""
    limited_names = set(limited or [])
    limited_tools.update(limited_names)

    def tool(function):
        """Registers a tool, and records that it ran and whether it failed, but not what it was given."""
        @functools.wraps(function)
        def wrapper(*args, **kwargs):
            try:
                result = function(*args, **kwargs)
            except Exception:
                record(data_dir(), "tool", name=function.__name__, ok=False)
                raise
            record(data_dir(), "tool", name=function.__name__, ok=True)
            return result

        # Discord is less private than the app, so it only gets tools that can't reach personal data.
        allowed_here = os.environ.get("MITAURI_TOOL_PROFILE") != "limited" or function.__name__ in limited_names
        if allowed_here and modules.is_enabled(data_dir(), module):
            server.tool()(wrapper)
        return wrapper

    return tool


def ask_server(path: str, body: dict | None = None) -> dict:
    """Passes a request to the phone through the running server."""
    request = Request(
        f"http://127.0.0.1:{API_PORT}{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {load_token(data_dir())}", "Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=LOCATION_TIMEOUT_SECONDS + 5) as response:
            return json.load(response)
    except HTTPError as error:
        try:
            return json.load(error)
        except ValueError:
            return {"error": f"The server refused that ({error.code})."}
    except OSError:
        return {"error": "The phone connection isn't running."}


def phone_position() -> tuple[float, float] | None:
    position = ask_server("/location")
    return (position["latitude"], position["longitude"]) if "latitude" in position else None
