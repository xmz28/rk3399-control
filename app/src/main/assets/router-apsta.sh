#!/system/bin/sh
# Experimental AP6256 concurrent mode. AP follows the upstream STA channel.
BASE=/data/local/rk3399-control
CTRL=/data/misc/wifi/hostapd-ap0
CONF=/data/misc/wifi/hostapd-ap0.conf
if [ "$(wl apsta 2>/dev/null)" != 1 ]; then
    wl down; wl apsta 1; wl up
    wpa_cli -i wlan0 reassociate >/dev/null 2>&1
    sleep 3
fi
iw phy phy0 interface add ap0 type __ap || exit 2
ip link set ap0 down
# The vendor defaults ap0 to the P2P-device MAC. Give it a distinct lab MAC.
MAC=$(cat /sys/class/net/wlan0/address)
ip link set ap0 address "0e:${MAC#*:}"
mkdir -p "$CTRL"
chown wifi:wifi "$CTRL"
chmod 0770 "$CTRL"
FREQ=$(wpa_cli -i wlan0 status | sed -n 's/^freq=//p')
case "$FREQ" in ''|*[!0-9]*) exit 3;; esac
if [ "$FREQ" -ge 5000 ]; then CH=$(( (FREQ - 5000) / 5 )); HW=a
else CH=$(( (FREQ - 2407) / 5 )); HW=g; fi
sed "s/^interface=.*/interface=ap0/; s/^channel=.*/channel=$CH/; s/^hw_mode=.*/hw_mode=$HW/; /^ctrl_interface=/d; /^bridge=/d" \
    /data/local/rk3399-hotspot/hostapd.conf > "$CONF"
printf '\nctrl_interface=%s\n' "$CTRL" >> "$CONF"
if [ "$(cat "$BASE/network/config/interfaceMode" 2>/dev/null)" != explicit ] ||
    grep -qE '(^| )ap( |$)' "$BASE/network/config/lanMembers"; then
    printf '\nbridge=br-lan\n' >> "$CONF"
fi
chown system:wifi "$CONF"
chmod 0660 "$CONF"
restorecon "$CTRL" "$CONF" 2>/dev/null || true
ip link set ap0 up
hostapd -B -P "$CTRL/hostapd.pid" "$CONF"
