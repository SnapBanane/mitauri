import argparse
import asyncio
import logging
import os
import sys

from . import proposals, wake
from .api import Api, load_token
from .auth import Devices
from .brain import Brain, BrainError, prune_transcripts
from .config import load_config, new_token
from .memory import Memory
from .presence import Presence, load_key
from .reminders import Reminders
from .speech import Speech


def main() -> None:
    parser = argparse.ArgumentParser(prog="mitauri")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="start the Discord bot")
    commands.add_parser("google-login", help="connect Google Calendar and Tasks")
    commands.add_parser("pair", help="show the details to enter in the phone app")
    commands.add_parser("devices", help="list paired devices")
    commands.add_parser("unpair", help="remove a paired device").add_argument("device")
    commands.add_parser("wake-test", help="play a test alarm on the speaker, once")
    proposal = commands.add_parser("proposal", help="review changes Mitauri has suggested to itself")
    proposal.add_argument("action", choices=["list", "show", "apply", "discard"])
    proposal.add_argument("id", nargs="?")
    ask = commands.add_parser("ask", help="send one message from the terminal")
    ask.add_argument("message")
    args = parser.parse_args()

    config = load_config()
    logging.basicConfig(level=logging.INFO)

    if args.command == "pair":
        host = os.environ.get("MITAURI_API_HOST")
        if not host:
            raise SystemExit("Set MITAURI_API_HOST in secrets.env to this device's Tailscale address first.")
        print(f"Server: {host}\nCode:   {Devices(config.data_dir).new_code()}")
        print("The code works once and expires in 10 minutes. The server must be running.")
        return

    if args.command == "devices":
        for device, entry in Devices(config.data_dir).all().items():
            print(device, entry["name"])
        return

    if args.command == "unpair":
        removed = Devices(config.data_dir).remove(args.device)
        print("Removed. Restart the server to end any open connection." if removed else "No such device.")
        return

    if args.command == "wake-test":
        # Only here, in a terminal: it's loud, and it may be in a bedroom.
        settings = wake.load_settings(config.data_dir)
        audio = Speech(config.data_dir).synthesize("Das ist ein Test von Mitauri. Guten Morgen!", "de")
        wake.play(settings["device"], audio, wake.wav_seconds(audio), settings["volume"], repeats=1)
        print("Played on", settings["device"])
        return

    if args.command == "proposal":
        review_proposal(args.action, args.id)
        return

    if args.command == "google-login":
        from .google_api import login
        login(config.data_dir)
        print("Google connected.")
        return

    memory = Memory(config.data_dir / "memory.db")
    brain = Brain(config, memory)
    if args.command == "ask":
        try:
            print(asyncio.run(ask_once(brain, args.message)))
        except BrainError as error:
            raise SystemExit(str(error))
        return

    from .bot import MitauriBot
    token = os.environ.get("MITAURI_DISCORD_TOKEN")
    user_id = os.environ.get("MITAURI_DISCORD_USER_ID")
    if not token or not user_id:
        raise SystemExit("Set MITAURI_DISCORD_TOKEN and MITAURI_DISCORD_USER_ID in secrets.env first.")
    reminders = Reminders(config.data_dir / "reminders.db")
    speech = Speech(config.data_dir)
    speech.preload()
    new_token(config.data_dir)
    session = (config.data_dir / "session.txt")
    prune_transcripts(config.data_dir, {session.read_text()} if session.exists() else set())
    presence = Presence(load_key(config.data_dir))
    presence.watch_serial(os.environ.get("MITAURI_PRESENCE_PORT", "/dev/ttyUSB0"))
    api = Api(brain, load_token(config.data_dir), config.data_dir, speech, memory, presence)
    bot = MitauriBot(int(user_id), Brain(config, memory, limited=True), reminders, config, api, brain, speech)
    try:
        asyncio.run(serve(bot, token, api, brain))
    except KeyboardInterrupt:
        pass


def review_proposal(action: str, proposal_id: str | None) -> None:
    if action == "list":
        for item in proposals.all_proposals():
            print(item["id"], item["status"], item["request"][:70])
        return
    if proposal_id is None:
        raise SystemExit("Name the proposal: mitauri proposal list")
    if action == "show":
        state = next((p for p in proposals.all_proposals() if p["id"] == proposal_id), None)
        if state is None:
            raise SystemExit("No such proposal.")
        print(f"Request: {state['request']}\n\n{state['summary']}\n")
        print(proposals.diff(proposal_id))
    elif action == "discard":
        proposals.discard(proposal_id)
        print("Discarded.")
    else:
        # Asked here on purpose: applying must take a person at this keyboard.
        if not sys.stdin.isatty():
            raise SystemExit("Applying only works from an interactive terminal.")
        if input(f"Apply proposal {proposal_id} to the project? Type its id to confirm: ") != proposal_id:
            raise SystemExit("Not applied.")
        try:
            applied = proposals.apply(proposal_id)
        except ValueError as error:
            raise SystemExit(str(error))
        for name in applied:
            print("changed", name)
        print("Run the tests, then restart Mitauri.")


async def ask_once(brain: Brain, message: str) -> str:
    try:
        return await brain.ask(message)
    finally:
        await brain.close()


async def serve(bot, token: str, api: Api, brain: Brain) -> None:
    host = os.environ.get("MITAURI_API_HOST")
    await api.start(["127.0.0.1", host] if host else ["127.0.0.1"])
    await brain.warm()
    try:
        async with bot:
            await bot.start(token)
    finally:
        await api.stop()
        await brain.close()


if __name__ == "__main__":
    main()
