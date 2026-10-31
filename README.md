# Mitauri

A personal AI assistant you talk to in Discord direct messages. It runs on your own device and uses the Claude Code
CLI, so it draws on your Claude subscription rather than a paid API.

Usage counts against the same limits as your normal Claude use. Keep it for yourself: Anthropic does
not allow offering subscription logins in products for other people.

## Models

Mitauri uses Haiku, and hands hard problems to Sonnet through its `think_harder` tool. Only self-building
proposals use Opus. One Claude Code
process stays running so replies start quickly. It uses about 250 MB of memory while idle.

## Setup

You need [uv](https://docs.astral.sh/uv/) and [Claude Code](https://code.claude.com), logged in with
`claude` on this device.

1. Create an application in the [Discord Developer Portal](https://discord.com/developers/applications).
   On its **Bot** page, click **Reset Token** and copy the token.
2. In Discord, create a private server for yourself. A bot can only receive your direct messages if
   you share a server with it.
3. In the portal, open **OAuth2**, generate a URL with the `bot` scope, open it, and add the bot to
   that server. It doesn't need any permissions.
4. In Discord, turn on **Settings > Advanced > Developer Mode**, then right-click or long-press your
   name and choose **Copy User ID**.
5. Copy `.env.example` to `~/.local/share/mitauri/secrets.env`, run `chmod 600` on it, and fill in the values.
6. Start it with `uv run mitauri run`, then send your bot a direct message.

Only the Discord user ID in `~/.local/share/mitauri/secrets.env` gets a response, and only in direct messages. Send `/new` to start
a fresh conversation; saved facts are kept.

To try it without Discord: `uv run mitauri ask "Hello"`.

## Reminders and the morning briefing

Ask for a reminder in plain language, like "remind me in 20 minutes to call Sam". Reminders that come
due while Mitauri is off are sent when it starts again. Sending them doesn't use any Claude usage.

The briefing is off by default. Set `MITAURI_BRIEFING_TIME=08:00` in `~/.local/share/mitauri/secrets.env` to turn it on. It uses one
Claude request a day, and if Mitauri is off for more than 3 hours after that time, it skips that day.

## Calls

Ask Mitauri to call you, now or later: "Ruf mich um 7 an." It rings with a full-screen call, using the
alarm volume so it reaches a silent phone. Answering asks you to unlock first, and the reason stays
hidden on the lock screen. Once answered you can keep talking until you hang up, or stay quiet for
about 6 seconds, which ends the call. Reminders also arrive in the app, and fall back to Discord if the phone
can't be reached. A call that's over 15 minutes late becomes a normal reminder instead.

## Door

Add your door login under Settings > Door login. Then saying or typing exactly "Öffne die Tür" or "Tür auf"
presses the door button after 3 seconds, with no fingerprint. Tap the island to cancel. Openings are logged
under your name by the building's system, so:

- These exact phrases go straight to the phone, without Claude, so nothing Claude reads can trigger it.
- It works only on an unlocked phone, never presses twice, and ignores repeats for 30 seconds.
- The login stays encrypted on the phone and never reaches the server or Claude.

Without a login, or for other wording, Mitauri opens the door app, and its fingerprint check decides.

## Notifications

Turn on notification access under Setup > Notification access, and pick which apps Mitauri may read. It
reads them only when you ask, nothing is stored on the phone, and banking, password and code apps are
always blocked. What it reads goes to Claude. Replies use the notification's own reply button, and only
send after you've checked the exact text and confirmed with your fingerprint or screen lock.

## Buses and trains

"Wann fährt der nächste Bus an München Hauptbahnhof?" or "Wie komme ich bis 9 Uhr nach Augsburg?" Times come
live from VRR's public timetable service, in the VRR region only.

Say "Sag mir, wann ich los muss, um 9 Uhr in Augsburg zu sein" for a leave-now alert. It re-checks live data
20 minutes before you need to leave, then notifies you at the right time.

It adds a tip to take an umbrella if rain is likely in the next two hours.

## Place reminders

"Erinnere mich an die Milch, wenn ich beim Rewe in Schwabing bin" notifies you on arrival. It needs
background location ("Allow all the time") and reminders live encrypted on the phone, so the server doesn't
learn when you arrive. Place lookups use OpenStreetMap. Name the place with its city, or ask for the nearest one, and check which
branch Mitauri says it found.

## Photos

Tap the plus button in the chat to send a photo, like a letter or receipt. The app shrinks it and removes its
metadata, including location. Neither the app nor the server saves it, but Claude Code's conversation log
keeps it until that log is deleted, after 14 days or a new conversation. Anthropic processes it to answer.

## Research and weather

"Recherchier mal gründlich ..." starts a background job that can only search the web, with no access to your
files or memories. You get a notification when the report is ready, and reports are deleted after 30 days.
"Wie wird das Wetter?" uses Open-Meteo, which only gets your position rounded to about a kilometre.

## Notes

"Merk dir, wo ich geparkt habe" saves a note with your location, and "Wo steht mein Auto?" finds it.

## Self-building

Ask "Bau dir eine Funktion, die ..." and Mitauri writes a proposal in an isolated copy of its code, using
Opus, with no shell and no access outside that copy. You get a message when it's ready. Then, at the
computer: `mitauri proposal show <id>` to read it, and `mitauri proposal apply <id>` or `discard <id>`.
Applying only works from an interactive terminal, so nothing on the phone can change the code.

## Directions

Ask for a route, like "route to Hamburg Hauptbahnhof by train". Mitauri sends a Google Maps link that
opens in the Maps app when tapped. "Wie weit ist es zum nächsten Rewe?" searches OpenStreetMap for places,
straight-line distances and typical road times, with no live traffic. These are free public services that
ask for light use. They receive your search text and, when you ask for something nearby, a rough position.

## Stocks

"Wie steht die SAP-Aktie?" gets a delayed price from Stooq, with no account. Only the symbols are sent.

## Presence sensor

`firmware/presence` makes an ESP32 listen for a Bluetooth beacon from the app and report it over USB. The
beacon's token changes every 5 minutes, and only your server has the key, so nobody else can track it or
fake it. Sightings stay in memory and are never saved. Distance is rough, and being away looks the same as
Bluetooth being off. Build and flash with `pio run -t upload` in that folder.

## Phone app

The Android app in `android/` chats with Mitauri over Tailscale, stays connected in the background,
and can share the phone's location. Holding the power button can start voice mode, shown in an island
around the camera. Install Tailscale on both devices and set `MITAURI_API_HOST` in `~/.local/share/mitauri/secrets.env`.

Create a signing key once with `scripts/make-signing-key.sh`, then build and install with
`cd android && ./gradlew installRelease`. Run `uv run mitauri pair` while the server is running, and
enter the address and one-time code in the app. Finish the checklist on the app's Setup screen.

## Security

- The app holds a private key in Android's secure hardware, and signs every request with it. There's
  no shared password to steal. See paired devices with `mitauri devices`, and remove one with
  `mitauri unpair <id>`.
- Traffic is encrypted by Tailscale. A tailnet can include other people's devices, so limit port 8766
  to your phone in your Tailscale access rules as well.
- Repeated failed attempts lock that address out for 15 minutes.
- Secrets and data live in `~/.local/share/mitauri`, private to your user. The token between
  Mitauri's own processes is replaced at every start. `audit.log` records actions without content.
- The phone encrypts its chat history, keeps it out of backups, and asks for your fingerprint or PIN
  before showing memories or disconnecting.
- Claude Code keeps logs of Mitauri's conversations under `~/.claude/projects/`. Starting a new
  conversation deletes the old log, and logs unused for 14 days are deleted at startup.
- Discord isn't private, so it gets its own conversation with no access to your calendar, tasks,
  memories or location. Reminders never go there, only a note that one is waiting.
- Calendar and task text is treated as data, not instructions. Mitauri has no shell access, and
  limits how much can be saved or how often it can ring, so a malicious entry can't do much.
- Encrypt the disk that holds the data folder, as nothing else protects it if the machine is stolen.
- File permissions don't carry over to Windows. Restrict the data folder with `icacls` there.

Voice mode records on the phone and sends the audio to your server, which uses Whisper for recognition
and Piper for the voice. Both run locally and are downloaded on first start, about 700 MB in all. Your
words still go to Claude as text. Voice mode doesn't run over the lock screen.

## Connect Google Calendar and Tasks

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project and enable the
   **Google Calendar API** and **Google Tasks API**.
2. Under **Google Auth Platform**, set up the consent screen as **External** and **publish the app to
   production**. In testing mode, Google logs you out every 7 days. Google will warn that the app is
   unverified when you log in; that's expected for your own app.
3. Create an OAuth client of type **Desktop app** and download its JSON file.
4. Save it as `~/.local/share/mitauri/credentials.json`.
5. Run `uv run mitauri google-login` and open the printed link.

If the device has no browser, first forward the login port from the computer you're using:
`ssh -L 8765:localhost:8765 <device>`.

## Run it all the time

```sh
mkdir -p ~/.config/systemd/user
cp deploy/mitauri.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now mitauri
loginctl enable-linger "$USER"   # keeps it running while you're logged out
```

Check that the paths in the service file match your device. Logs: `journalctl --user -u mitauri -f`.

## Secrets in git

`.git/hooks/pre-commit` runs `scripts/check-secrets.py`, which blocks commits containing your real secrets,
common token formats or secret files. Git doesn't copy hooks when cloning, so reinstall it on a new machine:
`printf '#!/bin/sh\nexec python3 scripts/check-secrets.py\n' > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit`

## Tests

`uv run pytest`
