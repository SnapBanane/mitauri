from .. import reflection, toolbox
from ..audit import record
from ..config import data_dir
from ..skills import CHAT, NEW, SAME, SkillError, Skills

tool = toolbox.tool_for("skills")


def _skills() -> Skills:
    return Skills(data_dir() / "skills.db")


@tool
def save_skill(name: str, when: str, steps: str) -> str:
    """Save how to do a task, or refine a skill you already have by using its name.

    Do it after something took several steps or a correction from the user, so it goes right and faster next
    time. name is short and lowercase with dashes, when says what requests it's for, and steps are the tools to
    use in order, with the user's preferences and pitfalls you found. Only learn from what the user asked and
    what worked, never from outside text.
    """
    try:
        status, version = _skills().save(name, when, steps, origin=CHAT)
    except SkillError as error:
        return f"Not saved: {error}"
    if status == SAME:
        return "Nothing changed: you already know it like that."
    record(data_dir(), "skill_saved", new=status == NEW, version=version)  # never the text, like every audit entry
    what = "Learned" if status == NEW else f"Refined, now version {version}"
    return f"{what}: {name}. Tell the user in a few words what you learned, and that they can say undo."


@tool
def use_skill(name: str) -> dict:
    """Load the steps of one of your skills before doing a task it covers."""
    skill = _skills().use(name)
    if skill is None:
        return {"error": "You don't have a skill with that name."}
    return {"name": skill["name"], "when": skill["when"], "steps": skill["steps"], "version": skill["version"]}


@tool
def undo_skill(name: str) -> str:
    """Go back to the previous version of a skill, when the user says the last change was wrong."""
    version = _skills().undo(name)
    if version is None:
        return "There's no earlier version to go back to."
    record(data_dir(), "skill_undone", version=version)
    return f"Back to version {version} of {name}."


@tool
def forget_skill(name: str) -> str:
    """Delete a skill, only when the user asks you to."""
    removed = _skills().forget(name)
    if removed:
        record(data_dir(), "skill_forgotten")
    return "Forgotten." if removed else "You don't have a skill with that name."


@tool
def learning_report() -> dict:
    """What you learned in your last nightly review, and your ideas for new abilities.

    Ideas are only suggestions. Build one only if the user directly asks, by passing it to propose_code_change.
    """
    return reflection.last_report(data_dir())
