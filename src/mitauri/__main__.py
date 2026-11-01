import argparse
import asyncio
import json
import logging
import os
import re
import socket
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from . import dashboard, modules, proposals, wake
from .api import Api, load_token
from .auth import Devices
from .chat_memory import ChatMemory
from .brain import Brain, BrainError, prune_transcripts
from .config import API_PORT, load_config, new_token
from .memory import Memory
from .laptop_beacon import start_laptop_beacon
from .presence import DEFAULT_SENSOR, Presence, beacon_key, load_key, parse_beacons, parse_sensors
from .reminders import Reminders
from .chat_memory import MODEL as SEARCH_MODEL
from .speech import GROQ_MODEL, VOICES, WHISPER_THREADS, Speech, speech_engine, voice_files

log = logging.getLogger(__name__)


def main() -> None:
    parser = argparse.ArgumentParser(prog="mitauri")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("run", help="start the Discord bot")
    commands.add_parser("google-login", help="connect Google Calendar and Tasks")
    commands.add_parser("pair", help="show the details to enter in the phone app")
    pair_listener = commands.add_parser("pair-listener", help="show the command that connects a report-only computer")
    pair_listener.add_argument("name", help="what this computer is called in presence answers, such as laptop")
    dashboard_command = commands.add_parser("dashboard", help="show a one-time link to the status page")
    dashboard_command.add_argument("--revoke", action="store_true", help="sign out every browser instead")
    commands.add_parser("devices", help="list paired devices")
    commands.add_parser("unpair", help="remove a paired device").add_argument("device")
    module_command = commands.add_parser("modules", help="list modules, or switch one on or off")
    module_command.add_argument("action", choices=["list", "on", "off"], nargs="?", default="list")
    module_command.add_argument("name", nargs="?")
    publish_update = commands.add_parser("publish-update", help="offer the newest app build to the phone")
    publish_update.add_argument("folder", nargs="?", help="folder with the APK and output-metadata.json, if not this repo's build")
    sensor_key = commands.add_parser("presence-key", help="give a sensor on USB its key, so it can broadcast")
    sensor_key.add_argument("name")
    sensor_key.add_argument("--port", default="/dev/ttyACM0")
    sensor_key.add_argument("--private", action="store_true", help="for a sensor that travels: broadcast only, and be hard for strangers to follow")
    commands.add_parser("wake-test", help="play a test alarm on the speaker, once")
    commands.add_parser("wake-stop", help="stop an alarm that's playing")
    proposal = commands.add_parser("proposal", help="review changes Mitauri has suggested to itself")
    proposal.add_argument("action", choices=["list", "show", "patch", "apply", "discard"])
    proposal.add_argument("id", nargs="?")
    ask = commands.add_parser("ask", help="send one message from the terminal")
    ask.add_argument("message")
    args = parser.parse_args()

    config = load_config()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s" if args.command == "run" else None)

    if args.command == "pair":
        host = os.environ.get("MITAURI_API_HOST")
        if not host:
            raise SystemExit("Set MITAURI_API_HOST in secrets.env to this device's Tailscale address first.")
        print(f"Server: {host}\nCode:   {Devices(config.data_dir).new_code()}")
        print("The code works once and expires in 10 minutes. The server must be running.")
        return

    if args.command == "pair-listener":
        host = os.environ.get("MITAURI_API_HOST")
        if not host:
            raise SystemExit("Set MITAURI_API_HOST in secrets.env to this device's Tailscale address first.")
        if args.name == "phone" or not re.fullmatch(r"[a-z0-9_-]{1,20}", args.name):
            raise SystemExit("Use a short lowercase name other than phone.")
        print(f"On that computer, within 10 minutes, with the server running:\n"
              f"uv run mitauri-listener setup {host} {Devices(config.data_dir).new_code(listener=args.name)}")
        return

    if args.command == "dashboard":
        access = dashboard.Access(config.data_dir)
        if args.revoke:
            access.revoke_all()
            print("Every browser is signed out.")
            return
        host = os.environ.get("MITAURI_API_HOST")
        if not host:
            raise SystemExit("Set MITAURI_API_HOST in secrets.env to this device's Tailscale address first.")
        print(f"http://{host}:{API_PORT}/dashboard#{access.new_code()}")
        print("The link works once and expires in 10 minutes. The browser then stays signed in for 30 days.")
        return

    if args.command == "devices":
        for device, entry in Devices(config.data_dir).all().items():
            print(device, entry["name"])
        return

    if args.command == "unpair":
        removed = Devices(config.data_dir).remove(args.device)
        print("Removed. Restart the server to end any open connection." if removed else "No such device.")
        return

    if args.command == "modules":
        if args.action != "list":
            if args.name is None:
                raise SystemExit("Name the module: mitauri modules list")
            try:
                modules.set_enabled(config.data_dir, args.name, args.action == "on")
            except KeyError:
                raise SystemExit("No such module.")
            except ValueError as error:
                raise SystemExit(str(error))
        for item in modules.listing(config.data_dir):
            print("on " if item["enabled"] else "off", item["name"].ljust(14), item["description"])
        return

    if args.command == "presence-key":
        import serial
        data_dir = load_config().data_dir
        connection = serial.Serial()
        connection.port, connection.baudrate, connection.timeout = args.port, 115200, 2
        connection.dtr = connection.rts = False
        connection.open()
        time.sleep(3)  # opening the port can restart the board, and it would miss the key while booting
        connection.reset_input_buffer()
        command = "PKEY" if args.private else "KEY"
        connection.write(f"{command} {beacon_key(load_key(data_dir), args.name).hex()}\n".encode())
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if b'"key":"saved"' in connection.readline():
                print(f"{args.name} now broadcasts. Add it to MITAURI_PRESENCE_BEACONS and restart Mitauri.")
                return
        raise SystemExit("The sensor didn't confirm the key. Is its firmware current, and is the port free?")
    if args.command == "wake-test":
        # Only here, in a terminal: it's loud, and it may be in a bedroom.
        settings = wake.load_settings(config.data_dir)
        audio = wake.with_tone(Speech(config.data_dir).synthesize("Das ist ein Test von Mitauri. Guten Morgen!", "de"))
        print("Playing once on", settings["device"], "- press Ctrl+C or run mitauri wake-stop to stop it")
        reached = alarm_notice(config.data_dir, over=False, text="Test des Weckers")
        print("Phone notification:", "sent" if reached else "not delivered. Is the server running and the phone connected?")
        try:
            wake.play(settings["device"], audio, wake.wav_seconds(audio), settings["volume"], config.data_dir, repeats=1)
        except KeyboardInterrupt:
            pass
        except OSError as error:
            raise SystemExit(f"{error} Is it on the same Wi-Fi? In a real alarm, your phone would ring instead.")
        finally:
            alarm_notice(config.data_dir, over=True)
        print("Done.")
        return

    if args.command == "publish-update":
        from . import app_update
        from .audit import record
        try:
            info = app_update.publish(config.data_dir, Path(args.folder) if args.folder else app_update.BUILD_FOLDER)
        except app_update.PublishError as error:
            raise SystemExit(str(error))
        record(config.data_dir, "app_update_published", version=info["version_name"])
        print(f"Published {info['version_name']} ({info['version_code']}).")
        notice = Request(
            f"http://127.0.0.1:{API_PORT}/app-update-notice", method="POST",
            headers={"Authorization": f"Bearer {load_token(config.data_dir)}"},
        )
        try:
            with urlopen(notice, timeout=5) as response:
                told = json.load(response)["told_phone"]
            print("The phone is fetching it now." if told else "The phone isn't connected, so it'll fetch it when it reconnects.")
        except HTTPError:
            print("The server refused the notice, so the phone will fetch it when it next reconnects.")
        except OSError:
            print("The server isn't running, so the phone will fetch it once it is.")
        return

    if args.command == "wake-stop":
        wake.request_stop(config.data_dir)
        print("Stop requested.")
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
    # Discord voice isn't used, and request logs are just noise.
    logging.getLogger("discord.client").setLevel(logging.ERROR)
    logging.getLogger("discord.state").setLevel(logging.ERROR)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    # Checked before anything else, because a second copy would replace the running one's internal token.
    if port_in_use(API_PORT):
        raise SystemExit(f"Mitauri is already running: port {API_PORT} is in use. Stop the other copy first.")
    log.info(startup_summary(config, os.environ.get("MITAURI_WHISPER_MODEL", "small"), WHISPER_THREADS))
    speech = Speech(config.data_dir)
    speech.preload()
    new_token(config.data_dir)
    session = (config.data_dir / "session.txt")
    prune_transcripts(config.data_dir, {session.read_text()} if session.exists() else set())
    watched = parse_sensors(os.environ.get("MITAURI_PRESENCE_WATCH", ""))
    presence = Presence(load_key(config.data_dir), parse_beacons(os.environ.get("MITAURI_PRESENCE_BEACONS", "")), list(watched))
    if modules.is_enabled(config.data_dir, "presence"):
        sensors = parse_sensors(os.environ.get("MITAURI_PRESENCE_SENSORS", ""))
        if legacy_port := os.environ.get("MITAURI_PRESENCE_PORT"):
            sensors = sensors or {DEFAULT_SENSOR: legacy_port}
        for name, port in sensors.items():
            presence.watch_serial(port, name)
        if sensors:  # the beacon is only for USB sensors to hear, and a server has no Bluetooth to send it with
            start_laptop_beacon(load_key(config.data_dir))
    history = ChatMemory(config.data_dir / "chat_memory.db")
    api = Api(brain, load_token(config.data_dir), config.data_dir, speech, memory, presence, history)
    bot = MitauriBot(int(user_id), Brain(config, memory, limited=True), reminders, config, api, brain, speech, memory)
    bot.presence, bot.watched_labels = presence, watched
    api.discord_ready = lambda: bot.is_ready() and not bot.is_closed()
    try:
        asyncio.run(serve(bot, token, api, brain))
    except KeyboardInterrupt:
        pass


def alarm_notice(data_dir, over: bool, text: str = "") -> bool:
    """Asks the running server to tell the phone. Without a server, the test still plays."""
    request = Request(
        f"http://127.0.0.1:{API_PORT}/alarm-notice",
        data=json.dumps({"over": over, "text": text}).encode(),
        headers={"Authorization": f"Bearer {load_token(data_dir)}", "Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=20) as response:
            return bool(json.load(response).get("delivered"))
    except (OSError, ValueError):
        return False


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
    elif action == "patch":
        print(proposals.diff(proposal_id), end="")
    elif action == "discard":
        proposals.discard(proposal_id)
        print("Discarded.")
    else:
        if not (proposals.PROJECT / ".git").exists():
            raise SystemExit("A released copy is replaced by the next release, so apply it on the laptop instead:\n"
                             f"ssh <server> \"sudo -iu mitauri /home/mitauri/current/.venv/bin/mitauri proposal patch {proposal_id}\" | git apply")
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


def port_in_use(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(1)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def startup_summary(config, whisper_model: str, whisper_threads: int) -> str:
    """What's in use, for the log when the server starts. It shows settings only, never a secret."""
    enabled = [m["name"] for m in modules.listing(config.data_dir) if m["enabled"]]
    disabled = [m["name"] for m in modules.listing(config.data_dir) if not m["enabled"]]
    language = os.environ.get("MITAURI_SPEECH_LANGUAGE", "de")
    wake_settings = wake.load_settings(config.data_dir)
    whisper = f"Whisper {whisper_model}, {whisper_threads} threads"
    hearing = {
        "groq": f"Groq {GROQ_MODEL}, sends recordings to Groq, and {whisper} if it fails",
        "parakeet": f"Parakeet, and {whisper} for short clips",
    }.get(speech_engine(), whisper)
    lines = [
        "Mitauri is starting",
        f"  Chat model:      {config.model}",
        f"  Hard problems:   {config.thinking_model}",
        f"  Self-building:   {os.environ.get('MITAURI_PROPOSAL_MODEL', 'opus')}",
        f"  Speech to text:  {hearing}, language {language}",
        f"  Voices:          {', '.join(voice_files(language)[1] for language in VOICES)}",
        f"  Memory search:   {SEARCH_MODEL.split('/')[-1]} (loads on first search)",
        f"  Wake-up alarm:   {'on' if wake_settings['enabled'] else 'off'}",
        f"  Modules on:      {', '.join(enabled)}",
    ]
    if disabled:
        lines.append(f"  Modules off:     {', '.join(disabled)}")
    return "\n".join(lines)


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
