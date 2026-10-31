from .. import toolbox

tool = toolbox.tool_for("presence")


@tool
def presence() -> dict:
    """Whether the user's phone is near the Bluetooth presence sensor at home, with a rough distance.

    Only for answering the user. Bluetooth being off looks the same as being away, so say that if it matters.
    """
    return toolbox.ask_server("/presence")
