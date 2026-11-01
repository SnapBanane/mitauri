#!/usr/bin/env bash
# Puts one tagged version on the server. Only run this when a release is really meant.
set -euo pipefail

tag="${1:?Name the version: scripts/release.sh v0.39}"
server="${MITAURI_SERVER:-opc@mitauri-server}"
key="${MITAURI_SERVER_KEY:-$HOME/.ssh/mitauri_oracle}"
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"

[[ "$tag" =~ ^v[0-9]+(\.[0-9]+)*$ ]] || { echo "Not a version tag: $tag" >&2; exit 1; }
git -C "$repo" rev-parse -q --verify "refs/tags/$tag" >/dev/null || { echo "No such tag: $tag" >&2; exit 1; }

check="$(mktemp -d)"
trap 'rm -rf "$check"' EXIT
git -C "$repo" archive "$tag" | tar -x -C "$check"
echo "Testing $tag here"
(cd "$check" && uv run --quiet pytest -q)

echo "Copying $tag to the server"
git -C "$repo" archive "$tag" -- . ':!android' ':!firmware' \
    | ssh -i "$key" "$server" "sudo -u mitauri bash -c 'rm -rf ~mitauri/releases/$tag && mkdir -p ~mitauri/releases/$tag && tar -x -C ~mitauri/releases/$tag'"

show_log() {
    echo "Last log lines from the server:" >&2
    ssh -i "$key" "$server" "sudo -n journalctl _SYSTEMD_USER_UNIT=mitauri.service -n 15 --no-pager -o cat" >&2 || true
}

ssh -i "$key" "$server" "sudo -iu mitauri bash -s -- $tag" <<'REMOTE' || { show_log; exit 1; }
set -euo pipefail
tag="$1"
export XDG_RUNTIME_DIR="/run/user/$(id -u)" PATH="$HOME/.local/bin:$PATH"
cd "$HOME/releases/$tag"
uv sync --quiet --group dev
uv run --quiet pytest -q

previous="$(readlink "$HOME/current" || true)"
ln -sfn "$HOME/releases/$tag" "$HOME/current"
mkdir -p "$HOME/.config/systemd/user"
cp deploy/mitauri-server.service "$HOME/.config/systemd/user/mitauri.service"
systemctl --user daemon-reload
systemctl --user enable --quiet mitauri

if [ ! -f "$HOME/.local/share/mitauri/secrets.env" ]; then
    echo "$tag is in place. Not started, because the server has no secrets.env yet."
    exit 0
fi

systemctl --user restart mitauri
for _ in $(seq 1 30); do
    if ss -tln | grep -q ':8766 '; then
        echo "$tag is running."
        cd "$HOME/releases"
        ls -1t | tail -n +4 | { grep -vxF "$(basename "${previous:-none}")" || true; } | xargs -r rm -rf
        exit 0
    fi
    sleep 2
done

echo "$tag didn't come up." >&2
if [ -n "$previous" ]; then
    ln -sfn "$previous" "$HOME/current"
    systemctl --user restart mitauri
    echo "Went back to $(basename "$previous")." >&2
fi
exit 1
REMOTE
