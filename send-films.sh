#!/bin/bash
# Sends the link of your films and series (some providers call it a VPortal link) to Aurus IPTV on the TV over adb,
# so that it need not be typed with the remote.
# The link is not shown, not saved on this computer and not logged. Aurus IPTV takes it at its next start.
#
#   ./send-films.sh 192.168.1.50        (the TV's address; or put it in a file named .tv-address next to this script)
#
# The TV needs "USB debugging" / "Network debugging" switched on in its developer options.
S="${1:-$(cat "$(dirname "$0")/.tv-address" 2>/dev/null)}"
[ -z "$S" ] && { echo "Give the TV's address: ./send-films.sh 192.168.1.50"; exit 1; }
case "$S" in *:*) ;; *) S="$S:5555";; esac
printf 'Paste the library link and press Enter (it stays hidden): '
read -rs LINK; echo
[ -z "$LINK" ] && { echo "Nothing pasted."; exit 1; }
case "$LINK" in *"[key:"*"]http"*) ;; *) echo "That does not look like a library link (portal::[key:...]http://...)."; exit 1;; esac
T=$(mktemp); chmod 600 "$T"
printf '%s\n' "$LINK" > "$T"
D=/sdcard/Android/data/app.hermes.channels/files
adb connect "$S" >/dev/null 2>&1
adb -s "$S" shell mkdir -p $D >/dev/null 2>&1
if adb -s "$S" push "$T" $D/films.txt >/dev/null 2>&1; then echo "Sent. Open Aurus IPTV on the TV."; else echo "Could not reach the TV (is it awake, is debugging on?)."; fi
rm -f "$T"; unset LINK
