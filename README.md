# Mitauri

A personal AI assistant you talk to in Discord direct messages. It runs on your own device and uses the Claude Code
CLI, so it draws on your Claude subscription rather than a paid API.

Usage counts against the same limits as your normal Claude use. Keep it for yourself: Anthropic does
not allow offering subscription logins in products for other people.

## Modules

Each feature is a module in `src/mitauri/modules/`, listed in its `__init__.py`. Switch them under Settings > Modules, or
with `mitauri modules off stocks`. Switching one off removes its tools and instructions, and stops its background work.
Only the listed modules exist: nothing is loaded from elsewhere. To add one, add a file and a line in that list.

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

## School timetable and wake-up

Add your WebUntis login to `secrets.env`, as `MITAURI_WEBUNTIS_SERVER`, `_SCHOOL`, `_USER` and `_PASSWORD`.
"Was habe ich morgen?" reads the timetable. A refused login isn't retried until you change it, as repeated
failures could lock your school account.

"Weck mich 90 Minuten vor der ersten Stunde" turns on the wake-up. Tell Mitauri your own rules, like "wenn ich zur
ersten Stunde muss, stell den Wecker auf 6:50", and it saves them. Whenever they or the timetable change, a small
Haiku request picks the time from those rules. It can only pick a time that morning before the first lesson, and
you get a notification when it changes. If that fails, the minutes-before setting applies. It skips cancelled lessons, and rechecks every
30 minutes. Google has no public alarm API for Nest, so it plays an alarm tone and spoken audio on the speaker over
Wi-Fi, repeating for up to 5 minutes. To stop it, use Stop on the phone's notification, say or type "Stopp", say
"Hey Google, stopp", or run `mitauri wake-stop`. The phone rings 3 minutes in, or straight away if the speaker
fails, and answering or declining also stops it. It's off by default, and only works
while the server is running on the same Wi-Fi as the speaker, so keep a phone alarm for anything important.
The speaker fetches the audio from this machine on port 8767, which is open only while the alarm plays and
serves one secret address. If you use a firewall, allow that port from your own network, such as
`sudo ufw allow from 192.168.0.0/16 to any port 8767 proto tcp`. Try the speaker with `mitauri wake-test`.

## Long-term memory

Mitauri keeps your app and voice chats so it can search them by meaning: "Was hatte ich letzte Woche über den Urlaub
gesagt?" The search model runs locally, and only what a search finds goes to Claude. Messages mentioning passwords,
PINs, TANs, codes or card numbers aren't kept. Discord chats, photos and tool results aren't kept either, though
Mitauri's own replies can repeat parts of them. There's no expiry: delete it all under Settings > Memories and
privacy. "New chat" doesn't delete it.

## How you write

Mitauri learns how you write so that replies sound like you, not like an assistant. It uses your messages to it, your
own lines when an allowed app's notification includes them, and any WhatsApp chat you share with it: export a chat
without media, share it to "Learn my writing style" and pick which sender is you. Only your own lines leave the phone;
everyone else's stay out. Lines that look like codes, passwords or links are skipped. They stay in the private data
folder and Haiku sums them up about once a day. See or delete the result under Memories and privacy.

Replies you ask for by typing, like "antworte Max: bin gleich da" or just "kannst du antworten?", go out after 4 seconds in which the island shows the
exact text. If you don't say what to write, Mitauri writes it in your style. Tap anywhere to cancel. Each typed message
allows one reply. Voice until voices are recognised, replies from 1 to 5 am, and a second reply go to the fingerprint
screen. A locked phone never sends.

## Lending and leaving

"Ich hab Max meine Bohrmaschine geliehen" is noted, and "wer hat was von mir?" lists it. "Erinner mich an die Schlüssel,
wenn ich von zu Hause losgehe" reminds you each time you leave, once an hour at most, until you cancel it. Android
watches for this on the phone, so the server doesn't learn when you leave.

## Auto music

Settings > Auto music starts Spotify when headphones you picked connect. It doesn't start during a call, interrupt what's
already playing, and doesn't restart after a dropout of under 2 seconds. It stays out of WhatsApp using Android's usage
access, checked only when headphones connect. Without that, it only starts while the phone is locked. Spotify usually
starts without opening; it only opens on screen if it stayed silent. Nothing about this leaves the phone.

## Island

"Stell einen Timer auf 10 Minuten" shows a live countdown in the island at the top of the screen. Tap it to see the
details or dismiss it. Leave-now alerts show there too. Voice mode takes over for a moment and hands it back. A
countdown only updates while on screen, and with nothing running, nothing is drawn. It isn't shown over the lock
screen or saved on the phone, but it comes back when the app reconnects to a running server. Dismissing it on the
phone stops it coming back, but a reminder set with it still fires.

While music plays, a small pill shows the cover left of the camera and the song name right of it.
Tap for the full text, live bars, previous, pause, next and Hide, which lasts until you next pause. A paused song stays for a minute, and only sound playing on this phone brings it up, so Spotify idling or playing on another device doesn't.
Its bars follow the music, using only how strong the bass to treble are at that moment, and stop while the screen is
off. Voice and countdowns take priority. This reads Spotify's media session through the app's notification access, so it
needs no account or Spotify API, and nothing about the song leaves the phone.

The island needs one thing switched on: Settings > Accessibility > Mitauri Island, or the Island item in the app's
setup. Android keeps normal "over other apps" windows under the status bar, where they never get taps, and it shows a
notice for them. The accessibility kind sits above it with no notice. Mitauri Island reads only where the status bar's
icons sit, never what they say or anything in another window, and only while the pill is showing. The pill still hides
on the lock screen. Without the service, the pill still shows, but tapping it doesn't work and it keeps a fixed size.

The pill reads the camera's position from Android and leaves that space free, moving longer text below it. It grows and
shrinks so it ends just before the nearest status bar icon.

The pill stays around the camera whenever the screen is on, also on the lock screen, where it stays empty apart from
short alerts. When idle it ignores touches, so the status bar still pulls down through it, and sideways it hides.

The island also shows any app's music, calls with their time and buttons, routes from a navigation app, alarms, countdowns,
stopwatches, recordings and progress from other apps. Tap or hold to open one, and its buttons and Open appear. With several
going at once, swipe sideways to switch. On the music window, tapping the top opens the app that's playing. A small dot
shows while a camera (green) or the microphone (amber) is in use. For a moment it also shows charging, low battery, the
ringer changing, silent mode or Do Not Disturb, battery saver, the hotspot, headphones connecting and unlocking. All of this comes from the phone
itself, stays on the phone and leaves out protected apps.

## Stocks

"Wie steht die SAP-Aktie?" gets a possibly delayed price from Yahoo Finance's public chart data, with no
account. It's unofficial and could stop working. Only the symbols are sent.

## Presence sensor

`firmware/presence` makes an ESP32 listen for Bluetooth beacons from the app and the laptop and report them over
USB. Each beacon's token changes every 5 minutes, and only your server has the key, so nobody else can track it or
fake it. Sightings stay in memory and are never saved. Distance is rough, and being away looks the same as
Bluetooth being off. Build and flash with `pio run -e esp32dev -t upload` in that folder, or `-e esp32s3` for an
ESP32-S3 board.

Name the sensors in `MITAURI_PRESENCE_SENSORS`, as `.env.example` shows. A sensor only reports while it's plugged
into the machine running Mitauri, so a sensor that's unplugged shows as unknown rather than away.

A sensor can also sit on plain USB power, with no network. It broadcasts a signed beacon, the phone listens for it
and tells the server, and the server checks the signature. To set one up, flash it, keep it on USB, run
`uv run mitauri presence-key desk`, and add the name to `MITAURI_PRESENCE_BEACONS`. The key lives on the sensor and
is derived from your main key, so a stolen sensor can only fake itself. Someone close by could still relay its signal
live, so never use presence as proof for anything that matters. The phone reports once a minute and nothing is saved.

A computer can listen too, even once the server lives elsewhere. Run `uv run mitauri pair-listener laptop` on the
server and the command it prints on the computer, then copy `deploy/mitauri-listener.service` to
`~/.config/systemd/user/` and run `systemctl --user enable --now mitauri-listener`. It only talks to a Tailscale
address, and its key can send these reports and nothing else. Its key is in `~/.local/share/mitauri-listener`. If the
server's address changes, run the pairing again.

For a sensor that travels, like one in a bag on a powerbank, use `uv run mitauri presence-key bag --port /dev/ttyUSB0
--private`. It stops listening and only broadcasts, hides its counter and changes its Bluetooth address every minute.
Strangers can't follow it by its address or counter, though the fixed `MS` marker in the broadcast still shows that a
Mitauri beacon is nearby. Check that your powerbank stays on, because many switch off when so little power is drawn.

To be warned when it's left behind, add `MITAURI_PRESENCE_WATCH=bag=Tasche`. You get one notification, or a Discord
message if the phone can't take it, once neither phone nor laptop has heard it for 2 minutes, and it can warn again
after it's been heard. It stays quiet if the phone isn't reporting or its Bluetooth is off. The laptop hearing it counts
as within reach, so leaving it beside a running laptop won't warn you.

## Watch

The phone talks to an ACT307 (VeryFitPro / iDO) watch directly over Bluetooth, so VeryFitPro isn't needed: uninstall
or force stop it, as the watch takes one connection at a time. Under Settings > Watch, find the watch, tap it, and pick
which apps appear on it. Mitauri reminders appear there too, and Mitauri can show messages, read the battery, and change
brightness and face when you ask. It only uses fixed commands: raw frames, firmware and factory reset can't be reached.

The watch has no pairing or encryption, so anyone nearby with the right tools can read what's sent, and could also send
things to the watch. Treat what it shows as unverified, and only turn on apps you're fine with that for. Banking,
password and code apps are always blocked, and code-looking messages are hidden. `src/mitauri/watch.py` is the laptop's
client and the protocol reference, and `WatchProtocol.kt` is its port. Both are tested against the same captured frames.

## Status page

A read-only page showing how far the phone and laptop are from each sensor, the phone's location, the watch's battery,
server health and recent errors. It refreshes every 5 seconds and needs nothing installed.

```bash
uv run mitauri dashboard           # prints a link that works once, within 10 minutes
uv run mitauri dashboard --revoke  # signs out every browser
```

Open the link on any device on your tailnet. That browser stays signed in for 30 days. The page can't change anything,
and others on the tailnet can't see past its login.

While a tab is open and visible, the server asks the phone for its location and the watch's status once a minute, which
uses some battery. It keeps the answers in memory only and drops them about a minute after the tab closes, so no location
history builds up; the audit log only notes that the location was asked for. The map link opens OpenStreetMap, which then
sees those coordinates, and only when you click it.

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

Everyday requests skip Claude and answer at once, by voice or typed: the time and date, "danke", starting or
stopping a timer ("Stell einen Timer auf 5 Minuten"), and reminders ("Erinner mich in 10 Minuten an den Ofen"). Only
a whole sentence counts, so anything else goes to Claude as usual.

Voice mode records on the phone and sends the audio to your server. With `MITAURI_GROQ_API_KEY` in
`secrets.env`, the server passes the spoken part to Groq for recognition, so Groq receives your voice; a failed
request falls back to local Whisper. Without a key, nothing but text leaves your machines, and it uses Parakeet for recognition,
Whisper for clips under two seconds, and Piper for the voice. All run locally and are downloaded on first
start, about 1.1 GB in all, and recognition needs about 2 GB of memory. If it hears worse than before, set
`MITAURI_SPEECH_ENGINE=whisper`, which is slower. The server log's `Voice timing` lines show how long
each reply took. If Claude goes to a tool without saying anything, Mitauri says "Moment." right then. Your
words still go to Claude as text. Voice mode doesn't run over the lock screen.

## Update the phone app

After one install by cable, the phone updates itself:

```bash
(cd android && ./gradlew assembleRelease) && uv run mitauri publish-update
```

Raise `versionCode` first, because phones only take a higher one. A connected phone fetches the new version
straight away, and otherwise does so when it reconnects. It waits while the app is open or a call is running, and
Android refuses anything not signed with your key. The first update needs "Install unknown apps" allowed for Mitauri, and Google Play Protect may ask you
to approve an install. Mitauri shows a notification when Android needs a tap. Settings > App has a button to check by hand.

To update someone else's server, copy the APK and `output-metadata.json` to a folder there and run
`uv run mitauri publish-update that-folder`. All phones must have the app from the same signing key.

## Run it on a server

The laptop is for developing and the server runs what you release. Nothing reaches the server until you run this
from the laptop, and it needs a version tag:

```bash
scripts/release.sh v0.39
```

It runs the tests on the laptop, copies that tag to the server without the Android and firmware code, runs the tests
again there and switches over. If the server doesn't come back up, it returns to the previous version by itself.
Secrets don't travel with a release: `secrets.env` is only copied by hand, once.

A new phone app goes the same way, only when you mean to release it: build it, then run `scripts/publish-app.sh`.

Self-building proposals are written on the server but can't be applied there, as the next release would replace them.
`mitauri proposal apply` on the server prints the command that brings one to the laptop. They can't change the Android
code, as the server doesn't have it.

Don't run the laptop and the server at the same time with the same Discord token, or both will answer.

The laptop also pulls a backup each day. The server encrypts it to a public key, so it can't read its own backups, and
the backup's login can do nothing except fetch one. Fourteen days are kept, matching how long conversation logs live.

```bash
cp deploy/mitauri-backup.* ~/.config/systemd/user/ && systemctl --user enable --now mitauri-backup.timer
```

To restore, run `gpg -d <backup> | tar -xz` in an empty folder. It asks for the passphrase of the `mitauri-backup` key,
so keep that passphrase and the key somewhere other than this laptop as well.

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
