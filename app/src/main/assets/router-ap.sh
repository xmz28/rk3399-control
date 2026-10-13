#!/system/bin/sh
# One-shot AP startup. The router supervisor owns addresses, DHCP and routing.
BASE=/data/local/rk3399-control
CONF=/data/local/rk3399-hotspot/hostapd.conf
svc wifi disable 2>/dev/null || true
sleep 4
for SERVICE in p2p_supplicant p2p_supplicant1 wpa_supplicant wpa_supplicant1; do stop "$SERVICE" 2>/dev/null || true; done
mkdir -p /data/misc/wifi/hostapd
sed '/^bridge=/d' "$CONF" > "$BASE/network/run/hostapd.conf"
if [ "$(cat "$BASE/network/config/interfaceMode" 2>/dev/null)" != explicit ] ||
    grep -qE '(^| )ap( |$)' "$BASE/network/config/lanMembers"; then
    printf '\nbridge=br-lan\n' >> "$BASE/network/run/hostapd.conf"
fi
cp "$BASE/network/run/hostapd.conf" /data/misc/wifi/hostapd.conf
chown system:wifi /data/misc/wifi/hostapd.conf /data/misc/wifi/hostapd
chmod 0660 /data/misc/wifi/hostapd.conf
chmod 0770 /data/misc/wifi/hostapd
restorecon /data/misc/wifi/hostapd.conf /data/misc/wifi/hostapd 2>/dev/null || true
ip addr flush dev wlan0 scope global
ip link set wlan0 up
/system/bin/hostapd -B /data/misc/wifi/hostapd.conf
