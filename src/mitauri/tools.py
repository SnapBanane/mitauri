"""The tools Claude can use, served over MCP. Each module adds its own, unless it's switched off."""

from importlib import import_module

from . import toolbox
from .modules import MODULES

_loaded = [import_module(f".modules.{module.name}", __package__) for module in MODULES]
server = toolbox.server


def __getattr__(name: str):
    """Reaches a tool by name, wherever it lives."""
    if name == "LIMITED_TOOLS":
        return toolbox.limited_tools
    for module in _loaded:
        if hasattr(module, name):
            return getattr(module, name)
    raise AttributeError(name)


if __name__ == "__main__":
    server.run()
