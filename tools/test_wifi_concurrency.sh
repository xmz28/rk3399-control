#!/system/bin/sh
# Lab-only diagnostic. Requires existing connected STA and saved AP config.
# Leaves no permanent AP/STA configuration and does not change radar settings.
BASE=/data/local/rk3399-control
TEST=/data/misc/wifi/hostapd-test
mkdir -p "$TEST"
chown wifi:wifi "$TEST"
chmod 0770 "$TEST"
restorecon "$TEST" 2>/dev/null || true
wpa_cli -i wlan0 status | grep -q '^wpa_state=COMPLETED$' || exit 2
cleanup() {
    [ ! -s "$TEST/hostapd.pid" ] || kill "$(cat "$TEST/hostapd.pid")" 2>/dev/null
    iw dev ap_test del 2>/dev/null || true
}
trap cleanup EXIT INT TERM
iw phy phy0 interface add ap_test type __ap addr 0a:fb:ea:f6:a2:e1 || exit 3
CH=${1:-1}; HW=g; [ "$CH" = 60 ] && HW=a
case "$CH" in 1|60) ;; *) exit 2;; esac
sed "s/^interface=.*/interface=ap_test/; s/^channel=.*/channel=$CH/; s/^hw_mode=.*/hw_mode=$HW/; /^bridge=/d; /^ctrl_interface=/d" \
    /data/local/rk3399-hotspot/hostapd.conf > "$TEST/hostapd.conf"
printf '\nctrl_interface=%s\n' "$TEST" >> "$TEST/hostapd.conf"
chmod 0660 "$TEST/hostapd.conf"
chown system:wifi "$TEST/hostapd.conf"
restorecon "$TEST/hostapd.conf" 2>/dev/null || true
ip link set ap_test up
hostapd -B -P "$TEST/hostapd.pid" "$TEST/hostapd.conf" || exit 4
COUNT=0
while [ "$COUNT" -lt 24 ]; do
    COUNT=$((COUNT + 1))
    sleep 5
    echo "SAMPLE=$COUNT"
    wpa_cli -i wlan0 status | grep -E '^(wpa_state|ssid|ip_address)='
    iw dev ap_test info
    iw dev wlan0 link
    hostapd_cli -p "$TEST" -i ap_test status 2>/dev/null | grep -E '^(state|freq|ssid|num_sta)='
    [ ! -d /sys/class/net/br-lan ] || ip link set ap_test master br-lan
done
