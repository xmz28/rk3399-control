#!/system/bin/sh
# Temporary startup-path comparison; does not change DFS/radar controls.
BASE=/data/local/rk3399-control
TEST=/data/local/tmp/rk3399-ap-ab
CONF=/data/local/rk3399-hotspot/hostapd.conf
mkdir -p "$TEST" || exit 2
chmod 0700 "$TEST"
[ "$(cat "$BASE/config/wifi_ap")" = 1 ] || exit 2
[ ! -s "$BASE/network/pending" ] || exit 2
cp "$CONF" "$TEST/original.conf" || exit 2
chmod 0600 "$TEST/original.conf"
ROUTER=$(cat "$BASE/network/daemon.pid")
CONTROLLER=$(cat "$BASE/controller.pid")
restore() {
    trap - EXIT INT TERM
    for PID in $(pidof hostapd); do kill "$PID" 2>/dev/null; done
    cp "$TEST/original.conf" "$CONF"
    chmod 0600 "$CONF"
    printf 1 > "$BASE/config/wifi_ap"
    rm -f "$BASE/config/wifi_transition" "$BASE/network/run/ap.pid"
    kill -CONT "$CONTROLLER" "$ROUTER" 2>/dev/null
    echo RESTORED
}
trap restore EXIT INT TERM
kill -STOP "$ROUTER" "$CONTROLLER" || exit 3
# A watchdog restores even if a startup command hangs.
(sleep 150; kill -TERM $$ 2>/dev/null) & WATCHDOG=$!
for MODE in legacy router; do
    echo "BEGIN=$MODE requested=64"
    for PID in $(pidof hostapd); do kill "$PID" 2>/dev/null; done
    "$BASE/controller.sh" wifi sta > "$TEST/reset-$MODE.log" 2>&1
    printf 1 > "$BASE/config/wifi_ap"
    sed 's/^channel=.*/channel=64/; /^bridge=/d' "$TEST/original.conf" > "$CONF"
    chmod 0600 "$CONF"
    if [ "$MODE" = legacy ]; then
        # Legacy prepare_radio + start_hostapd without its DHCP/watchdog/NAT.
        svc wifi disable
        sleep 4
        for SERVICE in p2p_supplicant p2p_supplicant1 wpa_supplicant wpa_supplicant1; do stop "$SERVICE" 2>/dev/null; done
        sleep 2
        ip link set wlan0 nomaster 2>/dev/null
        ip link set wlan0 up
        cp "$CONF" /data/misc/wifi/hostapd.conf
        mkdir -p /data/misc/wifi/hostapd
        rm -f /data/misc/wifi/hostapd/wlan0
        chown system:wifi /data/misc/wifi/hostapd.conf /data/misc/wifi/hostapd
        chmod 0660 /data/misc/wifi/hostapd.conf
        chmod 0770 /data/misc/wifi/hostapd
        restorecon /data/misc/wifi/hostapd.conf /data/misc/wifi/hostapd 2>/dev/null
        hostapd -B /data/misc/wifi/hostapd.conf > "$TEST/start-$MODE.log" 2>&1
    else
        "$BASE/router-ap.sh" > "$TEST/start-$MODE.log" 2>&1
    fi
    sleep 8
    echo "SAMPLE=$MODE"
    wl -i wlan0 isup
    wl -i wlan0 status | head -n 4
    iw dev wlan0 info
    dmesg | grep 'move channel 64' | tail -n 2
    # Leave time for the phone to perform an independent scan.
    sleep 12
done
kill "$WATCHDOG" 2>/dev/null
