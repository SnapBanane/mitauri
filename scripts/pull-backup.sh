#!/usr/bin/env bash
# Fetches today's encrypted backup. The key used here can do nothing on the server except produce one.
set -euo pipefail

server="${MITAURI_BACKUP_SERVER:-mitauri@mitauri-server}"
key="${MITAURI_BACKUP_KEY:-$HOME/.ssh/mitauri_backup}"
folder="${MITAURI_BACKUP_DIR:-$HOME/.local/share/mitauri-backups}"
keep_days=14  # conversation logs only live 14 days, so backups don't keep them longer

umask 077
mkdir -p "$folder"
name="$folder/mitauri-$(date +%F).tar.gz.gpg"
ssh -i "$key" -o IdentitiesOnly=yes -o BatchMode=yes "$server" > "$name.part"
[ -s "$name.part" ] || { rm -f "$name.part"; echo "The backup came back empty." >&2; exit 1; }
mv "$name.part" "$name"
find "$folder" -name 'mitauri-*.tar.gz.gpg' -mtime +"$keep_days" -delete
