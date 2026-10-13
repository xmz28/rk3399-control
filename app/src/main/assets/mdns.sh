#!/system/bin/sh
# Publish this NAS on each allowed local link; never reflect other devices.
BASE=/data/local/rk3399-control
ROOT=/data/local/alpine-nas
STATE="$BASE/network/run"
CONF="$ROOT/etc/avahi/avahi-daemon.conf"
mkdir -p "$STATE" "$ROOT/etc/avahi/services"
if [ -d "$STATE/mdns-lock" ]; then
    LOCK_PID=$(cat "$STATE/mdns-lock/pid" 2>/dev/null)
    if [ -n "$LOCK_PID" ] && kill -0 "$LOCK_PID" 2>/dev/null; then exit 0; fi
    rm -f "$STATE/mdns-lock/pid"; rmdir "$STATE/mdns-lock" 2>/dev/null
fi
mkdir "$STATE/mdns-lock" 2>/dev/null || exit 0
echo $$ > "$STATE/mdns-lock/pid"
trap 'rm -f "$STATE/mdns-lock/pid"; rmdir "$STATE/mdns-lock" 2>/dev/null' EXIT
ALLOWED=eth0,wlan0,eth1
WAN=
if [ "$(cat "$BASE/network/config/enabled" 2>/dev/null)" = 1 ]; then
    ALLOWED=br-lan
    WAN=$(cat "$STATE/wan" 2>/dev/null)
    WAN_IP=$(ip -4 -o addr show dev "$WAN" 2>/dev/null | chroot "$ROOT" /bin/busybox awk '{print $4}' | head -n 1)
    case "$WAN_IP" in 10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) ALLOWED="$ALLOWED,$WAN";; *) WAN=;; esac
fi
PORT=$(cat "$BASE/config/iperf_port" 2>/dev/null)
case "$PORT" in ''|*[!0-9]*) PORT=5201;; esac
LAN_ADDR=$(ip -4 -o addr show dev br-lan 2>/dev/null | chroot "$ROOT" /bin/busybox awk '{print $4}')
SIG="$ALLOWED:$LAN_ADDR:$WAN_IP:$PORT:$(cat "$BASE/enabled/mdns"):$(cat "$BASE/enabled/iperf")"
PID=$(cat "$ROOT/run/avahi-daemon/pid" 2>/dev/null)
if [ "$(cat "$STATE/mdns-signature" 2>/dev/null)" = "$SIG" ] && { [ "$(cat "$BASE/enabled/mdns")" != 1 ] || { [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; }; }; then exit 0; fi
[ -z "$PID" ] || kill "$PID" 2>/dev/null
rm -f "$ROOT/run/avahi-daemon/pid"
while ip rule del pref 8990 2>/dev/null; do :; done
while ip rule del pref 8991 2>/dev/null; do :; done
ip route flush table 181 2>/dev/null
ip route flush table 182 2>/dev/null
if [ "$(cat "$BASE/network/config/enabled" 2>/dev/null)" = 1 ]; then
    LAN_IP=$(cat "$BASE/network/config/lanIp")
    ip route replace 224.0.0.251/32 dev br-lan src "$LAN_IP" table 181
    ip rule add pref 8990 from "$LAN_IP/32" to 224.0.0.251/32 lookup 181
    if [ -n "$WAN" ]; then
        ip route replace 224.0.0.251/32 dev "$WAN" src "${WAN_IP%/*}" table 182
        ip rule add pref 8991 from "${WAN_IP%/*}/32" to 224.0.0.251/32 lookup 182
    fi
fi
[ -f "$CONF.pre-router" ] || cp "$CONF" "$CONF.pre-router"
sed "s/^allow-interfaces=.*/allow-interfaces=$ALLOWED/; s/^enable-reflector=.*/enable-reflector=no/" "$CONF.pre-router" > "$CONF.new"
mv "$CONF.new" "$CONF"
if [ "$(cat "$BASE/enabled/iperf")" = 1 ]; then
    printf '%s\n' '<?xml version="1.0" standalone="no"?>' '<!DOCTYPE service-group SYSTEM "avahi-service.dtd">' \
        '<service-group><name>RK3399 NAS</name><service><type>_iperf3._tcp</type>' \
        "<port>$PORT</port></service></service-group>" > "$ROOT/etc/avahi/services/rk3399-iperf.service"
else rm -f "$ROOT/etc/avahi/services/rk3399-iperf.service"; fi
if [ "$(cat "$BASE/enabled/mdns")" = 1 ]; then
    mkdir -p "$ROOT/run/avahi-daemon"
    chroot "$ROOT" /usr/sbin/avahi-daemon --daemonize --no-chroot --no-drop-root --file=/etc/avahi/avahi-daemon.conf || exit 2
fi
printf '%s' "$SIG" > "$STATE/mdns-signature"
