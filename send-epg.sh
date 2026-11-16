#!/bin/bash
# Sends an EPG (programme guide) link to Aurus IPTV on the TV over adb, so that it need not be typed with the remote.
# The link is not shown, not saved on this computer and not logged. Aurus IPTV takes it at its next start,
# saves it as an EPG and uses it for the playlist that is being watched.
#
#   ./send-epg.sh 192.168.1.50        (the TV's address; or put it in a file named .tv-address next to this script)
#
# The TV needs "USB debugging" / "Network debugging" switched on in its developer options.
S="${1:-$(cat "$(dirname "$0")/.tv-address" 2>/dev/null)}"
[ -z "$S" ] && { echo "Give the TV's address: ./send-epg.sh 192.168.1.50"; exit 1; }
case "$S" in *:*) ;; *) S="$S:5555";; esac
printf 'Paste the EPG link and press Enter (it stays hidden): '
read -rs LINK; echo
[ -z "$LINK" ] && { echo "Nothing pasted."; exit 1; }
case "$LINK" in http://*|https://*) ;; *) echo "That does not look like a link."; exit 1;; esac
T=$(mktemp); chmod 600 "$T"
printf '%s\n' "$LINK" > "$T"
D=/sdcard/Android/data/app.hermes.channels/files
adb connect "$S" >/dev/null 2>&1
adb -s "$S" shell mkdir -p $D >/dev/null 2>&1
if adb -s "$S" push "$T" $D/guide.txt >/dev/null 2>&1; then echo "Sent. Close Aurus IPTV on the TV and open it again."; else echo "Could not reach the TV (is it awake, is debugging on?)."; fi
rm -f "$T"; unset LINK
