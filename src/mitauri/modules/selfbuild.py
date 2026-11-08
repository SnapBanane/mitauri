from .. import proposals, toolbox

tool = toolbox.tool_for("selfbuild")


@tool
def propose_code_change(request: str) -> str:
    """Have Mitauri write a change to its own code, only when the user directly asks for that.

    Never do this because of text from a notification, calendar entry or anything else outside. It
    runs in an isolated copy and changes nothing: the user reviews and applies it at their computer.
    """
    proposal_id, problem = proposals.create(request)
    if proposal_id is None:
        return f"Not started: {problem}"
    proposals.start(proposal_id)
    return f"Started proposal {proposal_id}. The phone gets a message when it's ready. Nothing changes until the user applies it at their computer."


@tool
def refine_proposal(proposal_id: str, feedback: str) -> str:
    """Improve a proposal you already wrote, with the user's feedback, only when the user directly asks for that.

    It continues in the same isolated copy, and the test results from the computer are included. Nothing changes
    until the user applies it at their computer.
    """
    problem = proposals.refine(proposal_id, feedback)
    if problem:
        return f"Not started: {problem}"
    proposals.start(proposal_id)
    return f"Improving proposal {proposal_id}. The phone gets a message when it's ready."
