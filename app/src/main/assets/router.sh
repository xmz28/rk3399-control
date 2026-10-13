#!/system/bin/sh
# IPv4 single-uplink router. Only our own chains/table (180) are modified.
BASE=/data/local/rk3399-control
NET="$BASE/network"
CFG="$NET/config"
RUN="$NET/run"
ROOT=/data/local/alpine-nas
awk() { chroot "$ROOT" /bin/busybox awk "$@"; }
mkdir -p "$CFG" "$RUN"
chmod 0700 "$NET" "$CFG" "$RUN"
value() { cat "$CFG/$1" 2>/dev/null; }
enabled() { [ "$(value enabled)" = 1 ]; }
alive() { [ -s "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
stop_pid() { if alive "$1"; then kill "$(cat "$1")" 2>/dev/null; fi; rm -f "$1"; }
note() { printf '%s\n' "$*" > "$RUN/message"; echo "$(date '+%F %T') $*"; }
prefix() {
    case "$1" in
        255.255.255.255) echo 32;; 255.255.255.254) echo 31;; 255.255.255.252) echo 30;;
        255.255.255.248) echo 29;; 255.255.255.240) echo 28;; 255.255.255.224) echo 27;;
        255.255.255.192) echo 26;; 255.255.255.128) echo 25;; 255.255.255.0) echo 24;;
        255.255.254.0) echo 23;; 255.255.252.0) echo 22;; 255.255.248.0) echo 21;;
        255.255.240.0) echo 20;; 255.255.224.0) echo 19;; 255.255.192.0) echo 18;;
        255.255.128.0) echo 17;; 255.255.0.0) echo 16;; *) return 1;;
    esac
}
ipv4_number() { OLD_IFS=$IFS; IFS=.; set -- $1; IFS=$OLD_IFS; echo $((($1 << 24) | ($2 << 16) | ($3 << 8) | $4)); }
network_address() {
    N=$(( $(ipv4_number "$1") & (0xffffffff << (32 - $2)) ))
    echo "$(( (N >> 24) & 255 )).$(( (N >> 16) & 255 )).$(( (N >> 8) & 255 )).$(( N & 255 ))/$2"
}
lease() {
    WAN_IP=; WAN_GW=; WAN_PREFIX=; WAN_DNS=
    if [ "$1" = wlan0 ]; then
        wpa_cli -i wlan0 status 2>/dev/null | grep -q '^wpa_state=COMPLETED$' || return 1
        WAN_IP=$(ip -4 -o addr show dev wlan0 scope global | head -n 1 | awk '{print $4}')
        WAN_PREFIX=${WAN_IP#*/}; WAN_IP=${WAN_IP%/*}
        WAN_GW=$(ip -4 route show table all | grep '^default .* dev wlan0' | head -n 1 | awk '{print $3}')
        [ -n "$WAN_GW" ] || WAN_GW=$(getprop dhcp.wlan0.gateway)
        WAN_DNS=$(getprop dhcp.wlan0.dns1)
    else
        [ -s "$RUN/$1.lease" ] || return 1
        WAN_IP=$(sed -n '1p' "$RUN/$1.lease")
        WAN_PREFIX=$(prefix "$(sed -n '2p' "$RUN/$1.lease")") || return 1
        WAN_GW=$(sed -n '3p' "$RUN/$1.lease")
        WAN_DNS=$(sed -n '4p' "$RUN/$1.lease")
    fi
    case "$WAN_IP:$WAN_GW:$WAN_PREFIX" in *[!0-9.:]*|::*|*::|:*|*:) return 1;; esac
    [ "$WAN_IP" != "$LAN_IP" ] && [ "$WAN_GW" != "$LAN_IP" ]
}
stop_old() {
    stop wifi_hotspot 2>/dev/null || true
    stop ethernet_role 2>/dev/null || true
    for MATCH in wifi_hotspot.sh ethernet_role.sh; do
        for FILE in $(grep -l "$MATCH" /proc/[0-9]*/cmdline 2>/dev/null); do
            PID=${FILE#/proc/}; PID=${PID%/cmdline}; [ "$PID" = "$$" ] || kill "$PID" 2>/dev/null || true
        done
    done
    for PID in $(pidof dnsmasq 2>/dev/null); do
        grep -q -E 'dnsmasq-(eth0|wlan0).conf' "/proc/$PID/cmdline" && kill "$PID" 2>/dev/null || true
    done
    rm -f "$ROOT/run/dnsmasq-eth0.pid" "$ROOT/run/dnsmasq-wlan0.pid"
    while iptables -D FORWARD -i eth0 -o wlan0 -j DROP 2>/dev/null; do :; done
    while iptables -D FORWARD -i wlan0 -o eth0 -j DROP 2>/dev/null; do :; done
}
cleanup() {
    for IFACE in eth0 eth1; do
        stop_pid "$RUN/$IFACE.pid"
        ip link set "$IFACE" nomaster 2>/dev/null || true
        ip addr flush dev "$IFACE" scope global 2>/dev/null || true
    done
    stop_pid "$ROOT/run/dnsmasq-router.pid"
    stop_pid "$RUN/ap.pid"
    for PID in $(pidof hostapd 2>/dev/null); do kill "$PID" 2>/dev/null || true; done
    ip link set wlan0 nomaster 2>/dev/null || true
    iw dev ap0 del 2>/dev/null || true
    while ip rule del pref 9000 lookup 180 2>/dev/null; do :; done
    ip route flush table 180 2>/dev/null || true
    ip link del br-lan 2>/dev/null || true
    for CHAIN in RK_INPUT RK_FORWARD; do
        PARENT=INPUT; [ "$CHAIN" = RK_FORWARD ] && PARENT=FORWARD
        while iptables -D "$PARENT" -j "$CHAIN" 2>/dev/null; do :; done
        iptables -F "$CHAIN" 2>/dev/null; iptables -X "$CHAIN" 2>/dev/null
        while ip6tables -D "$PARENT" -j "$CHAIN" 2>/dev/null; do :; done
        ip6tables -F "$CHAIN" 2>/dev/null; ip6tables -X "$CHAIN" 2>/dev/null
    done
    while iptables -t nat -D POSTROUTING -j RK_NAT 2>/dev/null; do :; done
    iptables -t nat -F RK_NAT 2>/dev/null; iptables -t nat -X RK_NAT 2>/dev/null
    while iptables -t raw -D PREROUTING -j RK_RAW 2>/dev/null; do :; done
    iptables -t raw -F RK_RAW 2>/dev/null; iptables -t raw -X RK_RAW 2>/dev/null
    [ ! -s "$RUN/original_forward" ] || cat "$RUN/original_forward" > /proc/sys/net/ipv4/ip_forward
    rm -f "$RUN/active" "$RUN/signature" "$RUN/wan" "$RUN/dns-signature" "$RUN/firewall-signature"
    for IFACE in eth0 eth1; do rm -f "$RUN/$IFACE.lease" "$RUN/$IFACE.role" "$RUN/$IFACE.carrier" "$RUN/$IFACE.probe"; done
    for IFACE in eth0 eth1 wlan0; do
        for OPTION in arp_ignore arp_announce; do
            SAVED="$RUN/$IFACE.$OPTION"
            [ ! -s "$SAVED" ] || cat "$SAVED" > "/proc/sys/net/ipv4/conf/$IFACE/$OPTION" 2>/dev/null
            rm -f "$SAVED"
        done
    done
}
start_dhcp_client() {
    IFACE=$1
    alive "$RUN/$IFACE.pid" && return
    # Continuous udhcpc renews leases. Event script only stores offers; selected
    # WAN is configured by this supervisor, never by an unselected candidate.
    chroot "$ROOT" /bin/busybox udhcpc -f -i "$IFACE" -t "$WAIT" -T 1 -A 10 \
        -s /opt/router-lease.sh -p "/proc/1/root$RUN/$IFACE.pid" \
        > "$RUN/$IFACE-dhcp.log" 2>&1 &
    echo $! > "$RUN/$IFACE.pid"
}
init_network() {
    stop_old
    [ -f "$RUN/original_forward" ] || cat /proc/sys/net/ipv4/ip_forward > "$RUN/original_forward"
    # Prevent Android EthernetService's DHCP client from assigning a second
    # address to whichever wired port it happens to track. Restore on disable.
    APK=$(cat "$BASE/config/app_apk")
    CLASSPATH="$APK" app_process /system/bin com.example.rk3399_control.EthernetRootCli static 0.0.0.0 0 - - >/dev/null 2>&1 || true
    ip link add br-lan type bridge || return 1
    MAC=$(cat /sys/class/net/eth0/address)
    ip link set br-lan address "02:${MAC#*:}"
    ip link set br-lan up || return 1
    ip addr add "$LAN_IP/24" dev br-lan || return 1
    ip rule add pref 9000 lookup 180 || return 1
    printf '1' > "$RUN/active"
    for IFACE in eth0 eth1; do ip addr flush dev "$IFACE" scope global 2>/dev/null; done
}
configure_dns() {
    DNS=${WAN_DNS:-${WAN_GW:-1.1.1.1}}
    SIG="$LAN_IP:$DNS"
    if [ "$(cat "$RUN/dns-signature" 2>/dev/null)" != "$SIG" ] || ! alive "$ROOT/run/dnsmasq-router.pid"; then
        stop_pid "$ROOT/run/dnsmasq-router.pid"
        printf '%s\n' 'interface=br-lan' 'except-interface=lo' 'bind-interfaces' \
            'no-resolv' "server=$DNS" "dhcp-range=$LAN_NET.100,$LAN_NET.200,255.255.255.0,12h" \
            "dhcp-option=3,$LAN_IP" "dhcp-option=6,$LAN_IP" \
            'pid-file=/run/dnsmasq-router.pid' 'dhcp-leasefile=/var/lib/misc/router.leases' \
            > "$ROOT/etc/dnsmasq-router.conf"
        chroot "$ROOT" /usr/sbin/dnsmasq --conf-file=/etc/dnsmasq-router.conf && printf '%s' "$SIG" > "$RUN/dns-signature"
    fi
}
firewall() {
    FTP_RANGE=$(sed -n 's/^[[:space:]]*ftp-pr:[[:space:]]*\([0-9][0-9]*-[0-9][0-9]*\)[[:space:]]*$/\1/p' "$ROOT/etc/copyparty-base.conf" 2>/dev/null | head -n 1)
    SIG="$WAN:$WAN_IP:$WAN_PREFIX:$FTP_RANGE:$(cat "$RUN/eth0.role"):$(cat "$RUN/eth1.role"):$(cat "$BASE/config/http_port"):$(cat "$BASE/config/ftp_port"):$(cat "$BASE/config/sftp_port"):$(cat "$BASE/config/iperf_port")"
    [ "$(cat "$RUN/firewall-signature" 2>/dev/null)" != "$SIG" ] || return
    iptables -N RK_INPUT 2>/dev/null; iptables -F RK_INPUT
    iptables -N RK_FORWARD 2>/dev/null; iptables -F RK_FORWARD
    # IPv6 WAN access is deliberately closed until IPv6 routing is implemented.
    ip6tables -N RK_INPUT 2>/dev/null; ip6tables -F RK_INPUT
    ip6tables -N RK_FORWARD 2>/dev/null; ip6tables -F RK_FORWARD
    ip6tables -C INPUT -j RK_INPUT 2>/dev/null || ip6tables -I INPUT 1 -j RK_INPUT
    ip6tables -C FORWARD -j RK_FORWARD 2>/dev/null || ip6tables -I FORWARD 1 -j RK_FORWARD
    ip6tables -A RK_INPUT -i br-lan -j ACCEPT
    for IFACE in eth0 eth1 wlan0; do
        ip6tables -A RK_INPUT -i "$IFACE" -j DROP
        ip6tables -A RK_FORWARD -i "$IFACE" -j DROP
        ip6tables -A RK_FORWARD -o "$IFACE" -j DROP
    done
    ip6tables -A RK_FORWARD -i br-lan -j DROP
    ip6tables -A RK_FORWARD -o br-lan -j DROP
    iptables -t nat -N RK_NAT 2>/dev/null; iptables -t nat -F RK_NAT
    iptables -t raw -N RK_RAW 2>/dev/null; iptables -t raw -F RK_RAW
    iptables -C INPUT -j RK_INPUT 2>/dev/null || iptables -I INPUT 1 -j RK_INPUT
    iptables -C FORWARD -j RK_FORWARD 2>/dev/null || iptables -I FORWARD 1 -j RK_FORWARD
    iptables -t nat -C POSTROUTING -j RK_NAT 2>/dev/null || iptables -t nat -I POSTROUTING 1 -j RK_NAT
    iptables -t raw -C PREROUTING -j RK_RAW 2>/dev/null || iptables -t raw -I PREROUTING 1 -j RK_RAW
    FTP_PORT=$(cat "$BASE/config/ftp_port")
    # Vendor kernel lacks xt_CT; custom-port FTP needs explicit passive ports.
    iptables -A RK_INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
    iptables -A RK_INPUT -i br-lan -j ACCEPT
    iptables -A RK_FORWARD -i br-lan -o br-lan -j ACCEPT
    if [ -n "$WAN" ]; then
        iptables -A RK_FORWARD -i br-lan -o "$WAN" -j ACCEPT
        iptables -A RK_FORWARD -i "$WAN" -o br-lan -m state --state ESTABLISHED,RELATED -j ACCEPT
        iptables -t nat -A RK_NAT -s "$LAN_NET.0/24" -o "$WAN" -j MASQUERADE
        iptables -A RK_INPUT -i "$WAN" -m state --state ESTABLISHED,RELATED -j ACCEPT
        iptables -A RK_INPUT -i "$WAN" -p udp --sport 67 --dport 68 -j ACCEPT
        # Access to the box from directly connected private upstream subnet.
        # No general WAN->LAN forwarding and no public Internet administration.
        case "$WAN_IP" in 10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*)
            iptables -A RK_INPUT -i "$WAN" -s "$WAN_IP/$WAN_PREFIX" -p udp --dport 5353 -j ACCEPT
            iptables -A RK_INPUT -i "$WAN" -s "$WAN_IP/$WAN_PREFIX" -p icmp -j ACCEPT
            for PORT in 8765 445 139 3000 $(cat "$BASE/config/http_port") $(cat "$BASE/config/ftp_port") $(cat "$BASE/config/sftp_port") $(cat "$BASE/config/iperf_port"); do
                case "$PORT" in ''|*[!0-9]*) continue;; esac
                iptables -A RK_INPUT -i "$WAN" -s "$WAN_IP/$WAN_PREFIX" -p tcp --dport "$PORT" -j ACCEPT
            done
            IPERF_PORT=$(cat "$BASE/config/iperf_port")
            case "$IPERF_PORT" in ''|*[!0-9]*) ;; *) iptables -A RK_INPUT -i "$WAN" -s "$WAN_IP/$WAN_PREFIX" -p udp --dport "$IPERF_PORT" -j ACCEPT;; esac
            if [ -n "$FTP_RANGE" ]; then
                FTP_MIN=${FTP_RANGE%-*}; FTP_MAX=${FTP_RANGE#*-}
                if [ "$FTP_MIN" -ge 1024 ] && [ "$FTP_MAX" -le 65535 ] && [ "$FTP_MAX" -ge "$FTP_MIN" ] && [ $((FTP_MAX - FTP_MIN)) -le 100 ]; then
                    iptables -A RK_INPUT -i "$WAN" -s "$WAN_IP/$WAN_PREFIX" -p tcp --dport "$FTP_MIN:$FTP_MAX" -j ACCEPT
                fi
            fi
            ;;
        esac
        iptables -A RK_INPUT -i "$WAN" -j DROP
    fi
    for IFACE in eth0 eth1; do
        [ "$IFACE" = "$WAN" ] || iptables -A RK_INPUT -i "$IFACE" -j DROP
        iptables -A RK_FORWARD -i "$IFACE" -j DROP
        iptables -A RK_FORWARD -o "$IFACE" -j DROP
    done
    if [ "$WAN" != wlan0 ] && [ "$(cat "$BASE/config/wifi_ap")" != 1 ]; then
        iptables -A RK_INPUT -i wlan0 -j DROP
    fi
    iptables -A RK_FORWARD -i br-lan -j DROP
    iptables -A RK_FORWARD -o br-lan -j DROP
    printf '%s' "$SIG" > "$RUN/firewall-signature"
}
tick() {
    if [ -s "$BASE/config/wifi_transition" ] && ! kill -0 "$(cat "$BASE/config/wifi_transition")" 2>/dev/null; then rm -f "$BASE/config/wifi_transition"; fi
    UPLINK=$(value uplink); [ -n "$UPLINK" ] || UPLINK=auto
    WAIT=$(value wait); case "$WAIT" in ''|*[!0-9]*) WAIT=20;; esac
    LAN_IP=$(value lanIp); [ -n "$LAN_IP" ] || LAN_IP=192.168.31.1
    LAN_NET=${LAN_IP%.*}
    SIG="$UPLINK:$WAIT:$LAN_IP:$(value eth0Mode):$(value eth1Mode):$(value preset):$(value wifiMode)"
    if [ "$(cat "$RUN/signature" 2>/dev/null)" != "$SIG" ]; then
        if [ ! -f "$RUN/active" ] || ! ip -4 -o addr show dev br-lan | grep -q " $LAN_IP/24 "; then
            cleanup
            init_network || { note '桥接或策略路由初始化失败'; return 1; }
        else
            # Changing uplink must not destroy a healthy AP/STA radio or the
            # LAN bridge: phone clients keep their leases and control session.
            for IFACE in eth0 eth1; do
                stop_pid "$RUN/$IFACE.pid"
                ip link set "$IFACE" nomaster 2>/dev/null
                ip addr flush dev "$IFACE" scope global
                rm -f "$RUN/$IFACE.lease" "$RUN/$IFACE.probe" "$RUN/$IFACE.carrier"
            done
            ip route flush table 180
            rm -f "$RUN/firewall-signature" "$RUN/wan"
        fi
        printf '%s' "$SIG" > "$RUN/signature"
    fi
    NOW=$(date +%s)
    WIFI_POLICY=$(value wifiMode)
    DESIRED=
    case "$WIFI_POLICY" in ap) DESIRED=1;; apsta) DESIRED=2;; sta) DESIRED=0;; esac
    WIFI_POLICY_SIG="$(value preset):$WIFI_POLICY:$(value wifiGeneration)"
    if [ "$(cat "$RUN/wifi-policy" 2>/dev/null)" != "$WIFI_POLICY_SIG" ] && [ ! -f "$BASE/config/wifi_transition" ]; then
        if [ -n "$DESIRED" ] && [ "$(cat "$BASE/config/wifi_ap")" != "$DESIRED" ]; then
            nohup "$BASE/controller.sh" wifi "$WIFI_POLICY" > "$BASE/logs/router-wifi.log" 2>&1 &
            echo $! > "$BASE/config/wifi_transition"
        fi
        printf '%s' "$WIFI_POLICY_SIG" > "$RUN/wifi-policy"
    fi
    # Avoid Linux weak-host ARP replies from an isolated port for another NIC.
    for IFACE in eth0 eth1 wlan0; do
        [ -d "/proc/sys/net/ipv4/conf/$IFACE" ] || continue
        for OPTION in arp_ignore arp_announce; do
            [ -f "$RUN/$IFACE.$OPTION" ] || cat "/proc/sys/net/ipv4/conf/$IFACE/$OPTION" > "$RUN/$IFACE.$OPTION"
        done
        printf 1 > "/proc/sys/net/ipv4/conf/$IFACE/arp_ignore"
        printf 2 > "/proc/sys/net/ipv4/conf/$IFACE/arp_announce"
    done
    for IFACE in eth0 eth1; do
        [ -d "/sys/class/net/$IFACE" ] || { printf 'absent' > "$RUN/$IFACE.role"; continue; }
        ip link set "$IFACE" up
        CARRIER=$(cat "/sys/class/net/$IFACE/carrier" 2>/dev/null)
        OLD=$(cat "$RUN/$IFACE.carrier" 2>/dev/null)
        printf '%s' "$CARRIER" > "$RUN/$IFACE.carrier"
        if [ "$CARRIER" != 1 ]; then
            stop_pid "$RUN/$IFACE.pid"; rm -f "$RUN/$IFACE.lease" "$RUN/$IFACE.probe"
            ip link set "$IFACE" nomaster 2>/dev/null; ip addr flush dev "$IFACE" scope global
            printf down > "$RUN/$IFACE.role"; continue
        fi
        if [ "$UPLINK" = none ] || { [ "$UPLINK" != "$IFACE" ] && [ "$(value ${IFACE}Mode)" = lan ]; }; then
            printf lan > "$RUN/$IFACE.role"; continue
        fi
        if [ "$OLD" != 1 ] || [ ! -s "$RUN/$IFACE.probe" ]; then
            ip link set "$IFACE" nomaster 2>/dev/null; ip addr flush dev "$IFACE" scope global
            printf '%s' "$NOW" > "$RUN/$IFACE.probe"
            printf probing > "$RUN/$IFACE.role"
            start_dhcp_client "$IFACE"
        fi
        if lease "$IFACE"; then
            printf candidate > "$RUN/$IFACE.role"
        elif [ "$UPLINK" = "$IFACE" ]; then
            printf connecting > "$RUN/$IFACE.role"
        elif [ $((NOW - $(cat "$RUN/$IFACE.probe"))) -ge "$WAIT" ]; then
            if grep -q -E 'lease of|not found|Permission denied' "$RUN/$IFACE-dhcp.log"; then
                # A broken DHCP event hook is not evidence of a downstream LAN.
                printf isolated > "$RUN/$IFACE.role"
                note "$IFACE DHCP 配置异常，已隔离，请查看路由日志"
            else
                stop_pid "$RUN/$IFACE.pid"; printf lan > "$RUN/$IFACE.role"
            fi
        fi
    done
    WAN=
    if [ "$UPLINK" = auto ]; then
        # Stable preference: board Ethernet, USB Ethernet, then STA. No multi-WAN.
        for IFACE in eth0 eth1 wlan0; do
            [ "$IFACE" = wlan0 ] && [ "$(cat "$BASE/config/wifi_ap")" = 1 ] && continue
            [ "$IFACE" = wlan0 ] || [ "$(cat "$RUN/$IFACE.role")" = candidate ] || continue
            if lease "$IFACE"; then WAN=$IFACE; break; fi
        done
    elif [ "$UPLINK" != none ]; then
        lease "$UPLINK" && WAN=$UPLINK
    fi
    CONFLICT=0
    if [ -n "$WAN" ]; then
        MASK=$((0xffffffff << (32 - WAN_PREFIX)))
        if [ $(( $(ipv4_number "$WAN_IP") & MASK )) -eq $(( $(ipv4_number "$LAN_IP") & MASK )) ]; then
            CONFLICT=1
            WAN=
        fi
    fi
    [ -n "$WAN" ] || { WAN_IP=; WAN_GW=; WAN_DNS=; WAN_PREFIX=; }
    for IFACE in eth0 eth1; do
        ROLE=$(cat "$RUN/$IFACE.role" 2>/dev/null)
        if [ "$IFACE" = "$WAN" ]; then
            ip link set "$IFACE" nomaster 2>/dev/null
            CIDR="$WAN_IP/$WAN_PREFIX"
            ip -4 -o addr show dev "$IFACE" | grep -q " $CIDR " || { ip addr flush dev "$IFACE" scope global; ip addr add "$CIDR" dev "$IFACE"; }
            printf wan > "$RUN/$IFACE.role"
        elif [ "$ROLE" = candidate ] || [ "$ROLE" = wan ]; then
            ip link set "$IFACE" nomaster 2>/dev/null; ip addr flush dev "$IFACE" scope global
            printf isolated > "$RUN/$IFACE.role"
        elif [ "$ROLE" = lan ]; then
            ip addr flush dev "$IFACE" scope global
            ip link set "$IFACE" master br-lan
        fi
    done
    if [ -n "$WAN" ] && [ "$WAN" = wlan0 ]; then printf wwan > "$RUN/wlan0.role"
    elif [ "$(cat "$BASE/config/wifi_ap")" = 1 ]; then printf lan > "$RUN/wlan0.role"
    else printf standby > "$RUN/wlan0.role"; fi
    printf lan > "$RUN/ap0.role"
    WIFI_MODE=$(cat "$BASE/config/wifi_ap")
    if [ -f "$BASE/config/wifi_transition" ]; then
        :
    elif [ "$WIFI_MODE" = 1 ]; then
        stop_old
        if ! pidof hostapd >/dev/null && ! alive "$RUN/ap.pid"; then
            nohup "$BASE/router-ap.sh" > "$BASE/logs/router-ap.log" 2>&1 & echo $! > "$RUN/ap.pid"
        fi
        if iw dev wlan0 info | grep -q 'type AP'; then ip addr flush dev wlan0 scope global; ip link set wlan0 master br-lan; fi
    elif [ "$WIFI_MODE" = 2 ]; then
        if iw dev wlan0 info | grep -q 'type AP'; then
            nohup "$BASE/router.sh" restore-concurrent > "$BASE/logs/router-wifi.log" 2>&1 &
            echo $! > "$BASE/config/wifi_transition"
            return 0
        fi
        if ! hostapd_cli -p /data/misc/wifi/hostapd-ap0 -i ap0 status 2>/dev/null | grep -q '^state=ENABLED'; then
            LAST_AP=$(cat "$RUN/ap-attempt" 2>/dev/null); [ -n "$LAST_AP" ] || LAST_AP=0
            if ! alive "$RUN/ap.pid" && [ $((NOW - LAST_AP)) -ge 20 ]; then
                for PID in $(pidof hostapd 2>/dev/null); do kill "$PID" 2>/dev/null || true; done
                iw dev ap0 del 2>/dev/null || true
                printf '%s' "$NOW" > "$RUN/ap-attempt"
                nohup "$BASE/router-apsta.sh" > "$BASE/logs/router-apsta.log" 2>&1 & echo $! > "$RUN/ap.pid"
            fi
        fi
        ip link set wlan0 nomaster 2>/dev/null
        [ ! -d /sys/class/net/ap0 ] || ip link set ap0 master br-lan
    else
        ip link set wlan0 nomaster 2>/dev/null
    fi
    # Atomic-enough replacement of owned routes without flushing Android tables.
    ip route replace "$LAN_NET.0/24" dev br-lan src "$LAN_IP" table 180
    PREVIOUS=$(cat "$RUN/wan" 2>/dev/null)
    if [ "$PREVIOUS" != "$WAN" ]; then
        ip route flush table 180
        ip route replace "$LAN_NET.0/24" dev br-lan src "$LAN_IP" table 180
        rm -f "$RUN/firewall-signature"
    fi
    if [ -n "$WAN" ]; then
        ip route replace "$(network_address "$WAN_IP" "$WAN_PREFIX")" dev "$WAN" src "$WAN_IP" table 180
        ip route replace default via "$WAN_GW" dev "$WAN" table 180
        if [ "$PREVIOUS" != "$WAN" ]; then
            chroot "$ROOT" /bin/busybox arping -U -c 2 -I "$WAN" "$WAN_IP" >/dev/null 2>&1 &
        fi
        printf 1 > /proc/sys/net/ipv4/ip_forward
    else
        ip route del default table 180 2>/dev/null
        # Do not fall through to Android's leftover upstream default route.
        ip route replace unreachable default table 180
        printf 0 > /proc/sys/net/ipv4/ip_forward
    fi
    printf '%s' "$WAN" > "$RUN/wan"
    configure_dns
    firewall
    "$BASE/mdns.sh" >> "$BASE/logs/mdns.log" 2>&1
    TEXT="上行 ${WAN:-无} ${WAN_IP:-}；LAN $LAN_IP"
    [ "$CONFLICT" != 1 ] || TEXT='WAN 与 LAN 网段重叠，请修改 LAN 地址后再应用'
    [ "$(cat "$RUN/message" 2>/dev/null)" = "$TEXT" ] || note "$TEXT"
}
stage() {
    [ ! -s "$NET/pending" ] || { echo '请先确认或回滚上一项配置'; return 2; }
    mkdir "$NET/apply-lock" 2>/dev/null || { echo '另一项配置正在写入'; return 2; }
    echo $$ > "$NET/apply-lock/pid"
    trap 'rm -f "$NET/apply-lock/pid"; rmdir "$NET/apply-lock" 2>/dev/null' EXIT
    for NAME in enabled uplink wait lanIp eth0Mode eth1Mode preset wifiMode; do
        [ -f "$NET/staged/$2/$NAME" ] || { echo '暂存配置不完整'; return 2; }
    done
    mkdir -p "$NET/previous"
    for NAME in enabled uplink wait lanIp eth0Mode eth1Mode preset wifiMode wifiGeneration; do rm -f "$NET/previous/$NAME"; done
    cp "$CFG/"* "$NET/previous/" 2>/dev/null || true
    cat "$BASE/config/wifi_ap" > "$NET/previous/wifi_ap"
    printf '%s' "$2" > "$NET/token"
    printf '%s' "$(( $(date +%s) + 120 ))" > "$NET/pending"
    for NAME in enabled uplink wait lanIp eth0Mode eth1Mode preset wifiMode; do
        cp "$NET/staged/$2/$NAME" "$CFG/$NAME" || { rollback; return 3; }
        rm -f "$NET/staged/$2/$NAME"
    done
    printf '%s' "$2" > "$CFG/wifiGeneration"
    rmdir "$NET/staged/$2"
    echo '配置已提交，120 秒内重新连接并确认，否则自动回滚'
}
rollback() {
    BEFORE_WIFI=$(cat "$BASE/config/wifi_ap" 2>/dev/null)
    for NAME in enabled uplink wait lanIp eth0Mode eth1Mode preset wifiMode wifiGeneration; do
        if [ -f "$NET/previous/$NAME" ]; then cp "$NET/previous/$NAME" "$CFG/$NAME"; else rm -f "$CFG/$NAME"; fi
    done
    cp "$NET/previous/wifi_ap" "$BASE/config/wifi_ap" 2>/dev/null || true
    AFTER_WIFI=$(cat "$BASE/config/wifi_ap" 2>/dev/null)
    if [ "$BEFORE_WIFI" != "$AFTER_WIFI" ]; then
        nohup "$BASE/router.sh" restore-wifi "$AFTER_WIFI" > "$BASE/logs/router-wifi.log" 2>&1 &
        echo $! > "$BASE/config/wifi_transition"
    fi
    rm -f "$RUN/wifi-policy"
    rm -f "$NET/pending" "$NET/token"
    note '配置已回滚'
}
status() {
    echo "enabled=$(value enabled)"
    echo "uplink=$(value uplink)"
    echo "wait=$(value wait)"
    echo "lanIp=$(value lanIp)"
    echo "eth0Mode=$(value eth0Mode)"
    echo "eth1Mode=$(value eth1Mode)"
    echo "preset=$(value preset)"
    echo "wifiPolicy=$(value wifiMode)"
    echo "activeWan=$(cat "$RUN/wan" 2>/dev/null)"
    echo "message=$(cat "$RUN/message" 2>/dev/null)"
    echo "pendingUntil=$(cat "$NET/pending" 2>/dev/null)"
    echo "token=$(cat "$NET/token" 2>/dev/null)"
    echo "forward=$(cat /proc/sys/net/ipv4/ip_forward)"
    if alive "$ROOT/run/dnsmasq-router.pid"; then echo dhcp=1; else echo dhcp=0; fi
    echo "wifiOperation=$(cat "$BASE/config/wifi_ap" 2>/dev/null)"
    echo "ap0.channel=$(wl -i ap0 status 2>/dev/null | sed -n 's/.*Primary channel: //p')"
    if hostapd_cli -p /data/misc/wifi/hostapd-ap0 -i ap0 status 2>/dev/null | grep -q '^state=ENABLED'; then echo concurrentAp=1; else echo concurrentAp=0; fi
    for IFACE in eth0 eth1 wlan0 ap0 br-lan; do
        echo "$IFACE.role=$(cat "$RUN/$IFACE.role" 2>/dev/null)"
        echo "$IFACE.ip=$(ip -4 -o addr show dev "$IFACE" 2>/dev/null | awk '{print $4}')"
        echo "$IFACE.mac=$(cat /sys/class/net/$IFACE/address 2>/dev/null)"
        echo "$IFACE.carrier=$(cat /sys/class/net/$IFACE/carrier 2>/dev/null)"
    done
}
case "$1" in
    restore-wifi)
        case "$2" in
            1) "$BASE/controller.sh" wifi ap;;
            0) "$BASE/controller.sh" wifi sta;;
            2) "$BASE/controller.sh" wifi sta; printf 2 > "$BASE/config/wifi_ap";;
        esac;;
    restore-concurrent) "$BASE/controller.sh" wifi sta; printf 2 > "$BASE/config/wifi_ap";;
    status) status;;
    stage) stage "$@";;
    confirm) [ -s "$NET/pending" ] && [ "$2" = "$(cat "$NET/token")" ] || exit 2; rm -f "$NET/pending" "$NET/token"; echo '配置已确认';;
    rollback) [ -s "$NET/pending" ] || exit 2; rollback;;
    restart)
        stop_pid "$NET/daemon.pid"
        nohup "$BASE/router.sh" daemon > "$BASE/logs/router.log" 2>&1 &
        ;;
    daemon)
        alive "$NET/daemon.pid" && exit 0
        echo $$ > "$NET/daemon.pid"
        # A reboot with an unconfirmed transaction restores the last known config.
        BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
        if [ "$(cat "$NET/boot_id" 2>/dev/null)" != "$BOOT_ID" ]; then
            rm -f "$NET/apply-lock/pid"
            rmdir "$NET/apply-lock" 2>/dev/null || true
            [ ! -s "$NET/pending" ] || rollback
            printf '%s' "$BOOT_ID" > "$NET/boot_id"
        fi
        rm -f "$RUN/signature"
        while true; do
            if [ -d "$NET/apply-lock" ] && ! alive "$NET/apply-lock/pid"; then
                rm -f "$NET/apply-lock/pid"; rmdir "$NET/apply-lock" 2>/dev/null
            fi
            if [ -s "$NET/pending" ] && [ "$(date +%s)" -ge "$(cat "$NET/pending")" ]; then rollback; fi
            if [ -d "$NET/apply-lock" ]; then :
            elif enabled; then
                tick || { [ ! -s "$NET/pending" ] || rollback; }
            elif [ -f "$RUN/active" ]; then
                cleanup
                "$BASE/controller.sh" ethernet-apply
                "$BASE/controller.sh" wifi "$( [ "$(cat "$BASE/config/wifi_ap")" = 0 ] && echo sta || echo ap )"
            fi
            sleep 2
        done;;
    *) exit 2;;
esac
