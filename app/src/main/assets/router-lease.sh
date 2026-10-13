#!/bin/sh
# Runs inside Alpine. Never execute DHCP-provided text as shell commands.
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH
BASE=/proc/1/root/data/local/rk3399-control/network/run
echo "$interface" | grep -qE '^(eth[0-9]+|usb[0-9]+|enx[0-9a-fA-F]+)$' || exit 2
case "$dns" in *[!0-9.\ ]*) dns=;; esac
mkdir -p "$BASE"
case "$1" in
    bound|renew)
        case "$ip:$subnet:${router%% *}" in *[!0-9.:]*|::*|*::) exit 2;; esac
        printf '%s\n%s\n%s\n%s\n' "$ip" "$subnet" "${router%% *}" "${dns%% *}" > "$BASE/$interface.lease.new"
        mv "$BASE/$interface.lease.new" "$BASE/$interface.lease"
        ;;
    deconfig|nak) rm -f "$BASE/$interface.lease" ;;
esac
