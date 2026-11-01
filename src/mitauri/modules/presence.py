from .. import toolbox

tool = toolbox.tool_for("presence")


@tool
def presence() -> dict:
    """Whether the user's phone and laptop are near each Bluetooth sensor, with a rough distance.

    The phone has its own distance to each sensor. Sensors are named for where they sit: desk is the user's desk at work, home is at home. A sensor in the
    "unknown" state says nothing about where the user is. Only for answering the user. Bluetooth being off looks
    the same as being away, so say that if it matters.
    """
    return toolbox.ask_server("/presence")
