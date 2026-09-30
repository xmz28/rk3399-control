#!/system/bin/sh

BASE=/data/local/rk3399-control
ROOT=/data/local/alpine-nas
NAS=/data/local/rk3399-nas
HOTSPOT=/data/local/rk3399-hotspot
FTP_PREF=/data/data/org.primftpd/shared_prefs/org.primftpd_preferences.xml

enabled() { [ "$(cat "$BASE/enabled/$1" 2>/dev/null)" != "0" ]; }
config_port() {
    VALUE=$(cat "$BASE/config/${1}_port" 2>/dev/null)
    case "$VALUE" in ''|*[!0-9]*) echo "$2" ;; *) echo "$VALUE" ;; esac
}
port_open() {
    HEX=$(printf '%04X' "$1")
    grep -i ":$HEX " /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -q ' 0A '
}
udp_port_open() {
    HEX=$(printf '%04X' "$1")
    grep -i -q ":$HEX " /proc/net/udp /proc/net/udp6 2>/dev/null
}
is_mounted() { mount | grep -q " $1 "; }

kill_script() {
    MATCH="$1"
    # Scan cmdlines once. Spawning one `tr` for every process can hold the
    # controller for minutes on this Android 7 build.
    for CMDLINE in $(grep -l "$MATCH" /proc/[0-9]*/cmdline 2>/dev/null); do
        PID=${CMDLINE#/proc/}; PID=${PID%/cmdline}
        [ "$PID" = "$$" ] || kill "$PID" 2>/dev/null || true
    done
}

eth_set_role() {
    printf '%s' "$1" > /data/local/tmp/ethernet_role
    setprop persist.rk3399.eth_role "$1"
}

eth_stop_lan_dhcp() {
    PID_FILE="$ROOT/run/dnsmasq-eth0.pid"
    if [ -s "$PID_FILE" ]; then kill "$(cat "$PID_FILE")" 2>/dev/null || true; fi
    rm -f "$PID_FILE"
}

eth_start_lan_dhcp() {
    eth_stop_lan_dhcp
    mkdir -p "$ROOT/run" "$ROOT/var/lib/misc"
    # The AP dnsmasq already owns 127.0.0.1:53.  Excluding loopback lets the
    # Ethernet instance bind only eth0 while both DHCP servers stay online.
    chroot "$ROOT" /usr/sbin/dnsmasq --conf-file=/etc/dnsmasq-eth0.conf \
        --except-interface=lo </dev/null >/dev/null 2>&1
}

eth_clear_auto_lan() {
    eth_stop_lan_dhcp
    ip addr del 192.168.1.1/24 dev eth0 2>/dev/null || true
    ip route del 192.168.1.0/24 dev eth0 table 97 2>/dev/null || true
    ip route del 192.168.1.0/24 dev eth0 table 1002 2>/dev/null || true
    ip route flush cache 2>/dev/null || true
}

eth_configure_auto_lan() {
    ip addr flush dev eth0 scope global
    ip addr add 192.168.1.1/24 dev eth0
    ip link set eth0 up
    ip route replace 192.168.1.0/24 dev eth0 src 192.168.1.1 table 97
    ip route replace 192.168.1.0/24 dev eth0 src 192.168.1.1 table 1002
    ip route flush cache
    eth_start_lan_dhcp
    eth_set_role lan
}

eth_has_upstream() {
    ip -4 addr show dev eth0 scope global 2>/dev/null | grep -v ' 192.168.1.1/' | grep -q ' inet '
}

run_eth_cli() {
    APK=$(cat "$BASE/config/app_apk" 2>/dev/null)
    [ -f "$APK" ] || { echo "App APK path unavailable"; return 4; }
    CLASSPATH="$APK" app_process /system/bin com.example.rk3399_control.EthernetRootCli "$@"
}

stop_pppoe() {
    /system/bin/pppoe-stop /data/misc/ppp/pppoe.conf >/dev/null 2>&1 || true
    setprop net.pppoe.status disconnected
}

apply_ethernet() {
    MODE=$(cat "$BASE/config/eth_mode" 2>/dev/null)
    [ -n "$MODE" ] || MODE=auto
    kill_script ethernet_role.sh
    rm -f "$BASE/config/eth_probe_started"
    case "$MODE" in
        auto)
            stop_pppoe; setprop persist.net.ethernet.mode normal; eth_stop_lan_dhcp
            run_eth_cli dhcp || return $?
            eth_set_role probing
            ;;
        lan)
            stop_pppoe; setprop persist.net.ethernet.mode normal
            run_eth_cli static 192.168.1.1 24 - - || return $?
            eth_start_lan_dhcp
            eth_set_role lan
            ;;
        wan_dhcp)
            stop_pppoe; setprop persist.net.ethernet.mode normal; eth_stop_lan_dhcp
            run_eth_cli dhcp || return $?
            eth_set_role wan-dhcp
            ;;
        wan_static)
            stop_pppoe; setprop persist.net.ethernet.mode normal; eth_stop_lan_dhcp
            IP=$(cat "$BASE/config/eth_ip"); PREFIX=$(cat "$BASE/config/eth_prefix")
            GW=$(cat "$BASE/config/eth_gateway"); DNS1=$(cat "$BASE/config/eth_dns1"); DNS2=$(cat "$BASE/config/eth_dns2")
            run_eth_cli static "$IP" "$PREFIX" "$GW" "${DNS1:--}" "${DNS2:--}" || return $?
            eth_set_role wan-static
            ;;
        pppoe)
            eth_stop_lan_dhcp; setprop persist.net.ethernet.mode pppoe
            USER=$(cat "$BASE/config/pppoe_user"); PASS=$(cat "$BASE/config/pppoe_password")
            [ -n "$USER" ] && [ -n "$PASS" ] || { echo 'PPPoE account/password missing'; return 5; }
            stop_pppoe
            _account="$USER" _password="$PASS" _iface=eth0 _demand=no _dns1=server _dns2= _firewall=NONE /system/bin/pppoe-setup >/dev/null 2>&1 || return 6
            sed -i 's/^DEFAULTROUTE=.*/DEFAULTROUTE=yes/' /data/misc/ppp/pppoe.conf
            chmod 0600 /data/misc/ppp/pppoe.conf /data/misc/ppp/pap-secrets /data/misc/ppp/chap-secrets 2>/dev/null || true
            /system/bin/pppoe-start /data/misc/ppp/pppoe.conf
            eth_set_role pppoe
            ;;
        *) echo "Unknown ethernet mode: $MODE"; return 3 ;;
    esac
}

manage_ethernet() {
    MODE=$(cat "$BASE/config/eth_mode" 2>/dev/null); [ -n "$MODE" ] || MODE=auto
    CARRIER=$(cat /sys/class/net/eth0/carrier 2>/dev/null)
    case "$MODE" in
        auto)
            if [ "$CARRIER" != 1 ]; then
                eth_clear_auto_lan; rm -f "$BASE/config/eth_probe_started"; eth_set_role down; return
            fi
            if eth_has_upstream; then
                eth_stop_lan_dhcp; rm -f "$BASE/config/eth_probe_started"; eth_set_role wan; return
            fi
            if ip -4 addr show dev eth0 2>/dev/null | grep -q ' 192.168.1.1/24'; then
                [ -s "$ROOT/run/dnsmasq-eth0.pid" ] || eth_start_lan_dhcp
                eth_set_role lan; return
            fi
            NOW=$(date +%s); START=$(cat "$BASE/config/eth_probe_started" 2>/dev/null)
            if [ -z "$START" ]; then printf '%s' "$NOW" > "$BASE/config/eth_probe_started"; START=$NOW; fi
            WAIT=$(cat "$BASE/config/eth_auto_wait" 2>/dev/null); case "$WAIT" in ''|*[!0-9]*) WAIT=6;; esac
            ELAPSED=$((NOW - START)); eth_set_role probing
            [ "$ELAPSED" -lt "$WAIT" ] || { eth_configure_auto_lan; rm -f "$BASE/config/eth_probe_started"; }
            ;;
        lan)
            if [ "$CARRIER" = 1 ]; then [ -s "$ROOT/run/dnsmasq-eth0.pid" ] || eth_start_lan_dhcp; eth_set_role lan; else eth_set_role down; fi
            ;;
        wan_dhcp)
            if [ "$CARRIER" != 1 ]; then eth_set_role down; elif eth_has_upstream; then eth_set_role wan-dhcp; else eth_set_role connecting; fi
            ;;
        wan_static)
            [ "$CARRIER" = 1 ] && eth_set_role wan-static || eth_set_role down
            ;;
        pppoe)
            STATUS=$(getprop net.pppoe.status)
            [ "$CARRIER" = 1 ] && eth_set_role "pppoe-${STATUS:-disconnected}" || eth_set_role down
            ;;
    esac
}

prepare_mounts() {
    mkdir -p /data/media/0/NAS "$ROOT/proc" "$ROOT/sys" "$ROOT/dev/pts" "$ROOT/run" "$ROOT/mnt/nas"
    is_mounted "$ROOT/proc" || mount -t proc proc "$ROOT/proc" 2>/dev/null
    is_mounted "$ROOT/sys" || mount -t sysfs sysfs "$ROOT/sys" 2>/dev/null
    is_mounted "$ROOT/dev" || mount --bind /dev "$ROOT/dev" 2>/dev/null
    is_mounted "$ROOT/dev/pts" || mount --bind /dev/pts "$ROOT/dev/pts" 2>/dev/null
    is_mounted "$ROOT/mnt/nas" || mount --bind /data/media/0/NAS "$ROOT/mnt/nas" 2>/dev/null
}

manage_nas() {
    prepare_mounts
    HTTP_PORT=$(config_port http 3923)
    IPERF_PORT=$(config_port iperf 5201)

    if enabled samba; then
        port_open 445 || chroot "$ROOT" /usr/sbin/smbd -D -s /etc/samba/smb.conf
    else
        killall smbd 2>/dev/null || true
    fi

    if enabled http; then
        port_open "$HTTP_PORT" || chroot "$ROOT" /usr/bin/python3 /opt/copyparty-sfx.py -c /etc/copyparty.conf >"$ROOT/var/log/copyparty.log" 2>&1 &
    else
        kill_script copyparty-sfx.py
    fi

    if enabled iperf; then
        port_open "$IPERF_PORT" || chroot "$ROOT" /usr/bin/iperf3 -s -p "$IPERF_PORT" >"$ROOT/var/log/iperf3.log" 2>&1 &
    else
        kill_script /usr/bin/iperf3
    fi

    if enabled mdns; then
        AVAHI_PID=$(cat "$ROOT/run/avahi-daemon/pid" 2>/dev/null)
        if [ -z "$AVAHI_PID" ] || ! kill -0 "$AVAHI_PID" 2>/dev/null; then
            rm -f "$ROOT/run/avahi-daemon/pid"
            mkdir -p "$ROOT/run/avahi-daemon"
            chroot "$ROOT" /usr/sbin/avahi-daemon --daemonize --no-chroot --no-drop-root --file=/etc/avahi/avahi-daemon.conf
        fi
    else
        AVAHI_PID=$(cat "$ROOT/run/avahi-daemon/pid" 2>/dev/null)
        [ -z "$AVAHI_PID" ] || kill "$AVAHI_PID" 2>/dev/null || true
    fi
}

set_ftp_boot_pref() {
    [ -f "$FTP_PREF" ] || return 0
    grep -q "name=\"startOnBootPref\" value=\"$1\"" "$FTP_PREF" && return 0
    TMP="$BASE/config/ftp-boot.$$"
    sed "s/name=\"startOnBootPref\" value=\"[a-z]*\"/name=\"startOnBootPref\" value=\"$1\"/" "$FTP_PREF" > "$TMP"
    cat "$TMP" > "$FTP_PREF"
    rm -f "$TMP"
}

manage_ftp() {
    FTP_PORT=$(config_port ftp 12345)
    SFTP_PORT=$(config_port sftp 1234)
    if enabled ftp; then
        if ! port_open "$FTP_PORT" && ! port_open "$SFTP_PORT"; then
            am start --user 0 -n org.primftpd/org.primftpd.ui.StartServerAndExitActivity >/dev/null 2>&1 || true
        fi
        set_ftp_boot_pref true
    else
        am start --user 0 -n org.primftpd/org.primftpd.ui.StopServerAndExitActivity >/dev/null 2>&1 || am force-stop org.primftpd
        set_ftp_boot_pref false
    fi
}

manage_speed() {
    if enabled speed; then
        if ! port_open 3000; then
            am start --user 0 -n com.openspeedtest.server/.MainActivity >/dev/null 2>&1 || true
            (sleep 5; SIZE=$(wm size 2>/dev/null | tail -n 1 | sed 's/.*: //'); W=${SIZE%x*}; H=${SIZE#*x}; case "$W:$H" in *[!0-9:]*|:*) W=1920; H=1080;; esac; input tap $((W / 2)) $((H * 66 / 100))) &
        fi
    else
        am force-stop com.openspeedtest.server 2>/dev/null || true
    fi
}

wifi_sta() {
    stop wifi_hotspot 2>/dev/null || true
    kill_script wifi_hotspot.sh
    for PID in $(pidof hostapd 2>/dev/null); do kill "$PID" 2>/dev/null || true; done
    PID_FILE="$ROOT/run/dnsmasq-wlan0.pid"
    [ ! -s "$PID_FILE" ] || kill "$(cat "$PID_FILE")" 2>/dev/null || true
    rm -f "$PID_FILE" /data/local/tmp/wifi_hotspot.pid
    ip addr flush dev wlan0 scope global 2>/dev/null || true
    svc wifi enable 2>/dev/null || true
}

wifi_ap() {
    if [ -f /system/etc/init/wifi-hotspot.rc ]; then
        start wifi_hotspot 2>/dev/null || true
    elif ! pidof hostapd >/dev/null 2>&1; then
        HOTSPOT_PID=$(cat /data/local/tmp/wifi_hotspot.pid 2>/dev/null)
        if [ -z "$HOTSPOT_PID" ] || [ ! -r "/proc/$HOTSPOT_PID/stat" ] || grep -q ') Z ' "/proc/$HOTSPOT_PID/stat"; then
            kill_script wifi_hotspot.sh
            nohup /system/bin/wifi_hotspot.sh >"$BASE/logs/hotspot.log" 2>&1 &
        fi
    fi
}

manage_wifi() {
    if [ "$(cat "$BASE/config/wifi_ap" 2>/dev/null)" = "0" ]; then
        # STA must not be torn down every supervisor tick: that used to flush
        # DHCP addresses every 15 seconds and made joining a network impossible.
        if [ "$(getprop init.svc.wifi_hotspot)" = running ] || pidof hostapd >/dev/null 2>&1 ||
            [ "$(settings get global wifi_on 2>/dev/null)" != 1 ]; then
            wifi_sta
        fi
    else
        wifi_ap
    fi
}

stop_legacy_watchdogs() {
    kill_script rk3399-nas/scripts/nas_services.sh
    kill_script rk3399-nas/scripts/openspeedtest_guard.sh
    kill_script ethernet_role.sh
}

run_once() {
    stop_legacy_watchdogs
    manage_nas
    manage_ftp
    manage_speed
    manage_wifi
}

print_status() {
    FTP_PORT=$(config_port ftp 12345); SFTP_PORT=$(config_port sftp 1234)
    HTTP_PORT=$(config_port http 3923); IPERF_PORT=$(config_port iperf 5201)
    echo "ROOT=$(id -u)"
    echo "MODEL=$(getprop ro.product.model)"
    echo "ANDROID=$(getprop ro.build.version.release) / API $(getprop ro.build.version.sdk) / $(getprop ro.product.cpu.abi)"
    echo "BOOT_MODE=$(cat "$NAS/boot-mode" 2>/dev/null)"
    echo "ETH_ROLE=$(cat /data/local/tmp/ethernet_role 2>/dev/null)"
    echo "ETH_MODE=$(cat "$BASE/config/eth_mode" 2>/dev/null)"
    echo "ETH_AUTO_WAIT=$(cat "$BASE/config/eth_auto_wait" 2>/dev/null)"
    echo "ETH_GATEWAY=$(ip -4 route show default 2>/dev/null | head -n 1)"
    echo "PPPOE_STATUS=$(getprop net.pppoe.status)"
    echo "ETH_IP=$(ip -4 addr show dev eth0 2>/dev/null | grep ' inet ' | head -n 1 | sed 's/^[[:space:]]*//')"
    echo "WLAN_IP=$(ip -4 addr show dev wlan0 2>/dev/null | grep ' inet ' | head -n 1 | sed 's/^[[:space:]]*//')"
    if pidof hostapd >/dev/null 2>&1; then WIFI_NOW=AP; else WIFI_NOW=STA; fi
    echo "WIFI_MODE=$WIFI_NOW"
    SSID_NOW=$(iw dev wlan0 info 2>/dev/null | sed -n 's/^[[:space:]]*ssid //p' | head -n 1)
    if [ "$WIFI_NOW" = AP ]; then
        [ -n "$SSID_NOW" ] || SSID_NOW=$(sed -n 's/^ssid=//p' "$HOTSPOT/hostapd.conf" 2>/dev/null | head -n 1)
    else
        SSID_NOW=$(wpa_cli -i wlan0 status 2>/dev/null | sed -n 's/^ssid=//p' | head -n 1)
    fi
    CHANNEL_NOW=$(iw dev wlan0 info 2>/dev/null | grep 'channel ' | head -n 1 | sed 's/.*channel \([0-9][0-9]*\).*/\1/')
    if [ "$WIFI_NOW" = AP ]; then
        [ -n "$CHANNEL_NOW" ] || CHANNEL_NOW=$(sed -n 's/^channel=//p' "$HOTSPOT/hostapd.conf" 2>/dev/null | head -n 1)
    fi
    BAND_NOW=2g
    case "$CHANNEL_NOW" in 36|40|44|48|52|56|60|64|149|153|157|161|165) BAND_NOW=5g;; esac
    echo "SSID=$SSID_NOW"
    echo "BAND=$BAND_NOW"
    echo "CHANNEL=$CHANNEL_NOW"
    echo "AP_SSID=$(sed -n 's/^ssid=//p' "$HOTSPOT/hostapd.conf" 2>/dev/null | head -n 1)"
    AP_CHANNEL=$(sed -n 's/^channel=//p' "$HOTSPOT/hostapd.conf" 2>/dev/null | head -n 1)
    echo "AP_CHANNEL=$AP_CHANNEL"
    case "$AP_CHANNEL" in 36|40|44|48|52|56|60|64|149|153|157|161|165) echo 'AP_BAND=5g';; *) echo 'AP_BAND=2g';; esac
    usb_status
    port_open 445; R=$?; enabled samba; E=$?; echo "SERVICE|samba|Samba|445|$((1-R))|$((1-E))|0|1"
    port_open "$HTTP_PORT"; R=$?; enabled http; E=$?; echo "SERVICE|http|HTTP 文件管理|$HTTP_PORT|$((1-R))|$((1-E))|1|1"
    port_open "$FTP_PORT"; R=$?; enabled ftp; E=$?; echo "SERVICE|ftp|FTP / SFTP|$FTP_PORT|$((1-R))|$((1-E))|1|1"
    port_open "$SFTP_PORT"; R=$?; enabled ftp; E=$?; echo "SERVICE|sftp|SFTP（同上）|$SFTP_PORT|$((1-R))|$((1-E))|1|0"
    port_open 3000; R=$?; enabled speed; E=$?; echo "SERVICE|speed|OpenSpeedTest|3000|$((1-R))|$((1-E))|0|1"
    port_open "$IPERF_PORT"; R=$?; enabled iperf; E=$?; echo "SERVICE|iperf|iperf3 Server|$IPERF_PORT|$((1-R))|$((1-E))|1|1"
    udp_port_open 5353; R=$?; enabled mdns; E=$?; echo "SERVICE|mdns|mDNS / a.local|5353|$((1-R))|$((1-E))|0|1"
}

usb_dt_mode() {
    cat "/sys/firmware/devicetree/base/usb@$1/dwc3@$1/dr_mode" 2>/dev/null | tr -d '\000'
}

usb_attached_devices() {
    USB_COUNT=0
    for USB_VENDOR in $(find /sys/devices/platform/usb@fe900000 -name idVendor 2>/dev/null); do
        [ "$(cat "$USB_VENDOR" 2>/dev/null)" = 1d6b ] || USB_COUNT=$((USB_COUNT + 1))
    done
    echo "$USB_COUNT"
}

usb_status() {
    echo "USB_C_DT=$(usb_dt_mode fe800000)"
    echo "USB_C_MODE=$(cat /sys/devices/platform/usb@fe800000/dwc3_mode 2>/dev/null)"
    USB_A_DT=$(usb_dt_mode fe900000)
    echo "USB_A_DT=$USB_A_DT"
    echo "USB_A_MODE=$(cat /sys/devices/platform/usb@fe900000/dwc3_mode 2>/dev/null)"
    [ "$USB_A_DT" = otg ] && echo 'USB_A_SWITCHABLE=1' || echo 'USB_A_SWITCHABLE=0'
    echo "USB_A_ATTACHED=$(usb_attached_devices)"
    echo "USB_GADGET_STATE=$(cat /sys/class/udc/fe900000.dwc3/state 2>/dev/null)"
    echo "USB_GADGET_UDC=$(cat /config/usb_gadget/g1/UDC 2>/dev/null)"
}

usb_set() {
    [ "$1" = type_a ] || { echo '此固件只允许切换 USB3 Type-A；Type-C 的设备树固定为 Host'; return 2; }
    case "$2" in host|peripheral|otg) ;; *) echo '模式必须为 host、peripheral 或 otg'; return 2;; esac
    [ "$(usb_dt_mode fe900000)" = otg ] || { echo '设备树未配置 OTG，拒绝运行时切换'; return 2; }
    MODE_FILE=/sys/devices/platform/usb@fe900000/dwc3_mode
    [ -w "$MODE_FILE" ] || { echo '内核没有可写的角色切换接口'; return 2; }
    CURRENT=$(cat "$MODE_FILE")
    [ "$CURRENT" = "$2" ] && { echo "USB3 Type-A 已是 $2"; return 0; }
    if [ "$CURRENT" != host ]; then
        UDC_STATE=$(cat /sys/class/udc/fe900000.dwc3/state 2>/dev/null)
        [ -z "$UDC_STATE" ] || [ "$UDC_STATE" = 'not attached' ] || {
            echo "USB 线仍连接（$UDC_STATE）；先拔下连接电脑的数据线再切换角色"; return 3;
        }
    fi
    if [ "$2" = host ]; then
        [ ! -f /config/usb_gadget/g1/UDC ] || echo '' > /config/usb_gadget/g1/UDC
    elif [ "$CURRENT" = host ] && [ "$(usb_attached_devices)" -gt 0 ]; then
        echo 'USB3 Type-A 有外设连接；请安全卸载并拔下外设后再切换'; return 3
    fi
    if ! echo "$2" > "$MODE_FILE"; then
        if [ "$2" = host ] && [ -e /sys/class/udc/fe900000.dwc3 ] && [ -f /config/usb_gadget/g1/UDC ]; then
            echo fe900000.dwc3 > /config/usb_gadget/g1/UDC 2>/dev/null || true
        fi
        echo '内核拒绝切换 USB 角色'
        return 4
    fi
    sleep 1
    NEW_MODE=$(cat "$MODE_FILE")
    [ "$NEW_MODE" = "$2" ] || { echo "切换未生效，当前为 $NEW_MODE"; return 4; }
    if [ "$2" != host ] && [ -f /config/usb_gadget/g1/UDC ] &&
        [ -z "$(cat /config/usb_gadget/g1/UDC 2>/dev/null)" ] && [ -e /sys/class/udc/fe900000.dwc3 ]; then
        echo fe900000.dwc3 > /config/usb_gadget/g1/UDC || true
    fi
    echo "USB3 Type-A 已切换为 $NEW_MODE；重新插线后再检查设备枚举"
}

apply_port() {
    ID="$1"; PORT="$2"
    case "$PORT" in ''|*[!0-9]*) echo '端口必须是数字'; return 2;; esac
    [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || { echo '端口范围必须是 1..65535'; return 2; }
    printf '%s' "$PORT" > "$BASE/config/${ID}_port"
    case "$ID" in
        http) sed -i "s/^[[:space:]]*p:.*/  p: $PORT/" "$ROOT/etc/copyparty.conf"; kill_script copyparty-sfx.py ;;
        iperf) kill_script /usr/bin/iperf3 ;;
        ftp|sftp)
            am force-stop org.primftpd
            if [ -f "$FTP_PREF" ]; then
                TMP="$BASE/config/ftp-pref.$$"
                if [ "$ID" = ftp ]; then
                    sed "s|<string name=\"portPref\">[0-9]*</string>|<string name=\"portPref\">$PORT</string>|" "$FTP_PREF" > "$TMP"
                else
                    sed "s|<string name=\"securePortPref\">[0-9]*</string>|<string name=\"securePortPref\">$PORT</string>|" "$FTP_PREF" > "$TMP"
                fi
                cat "$TMP" > "$FTP_PREF"
                rm -f "$TMP"
            fi
            ;;
        *) echo "不支持修改 $ID 端口"; return 2 ;;
    esac
}

ap_config() {
    SSID="$1"; PASS="$2"; BAND="$3"; CH="$4"; CONF="$HOTSPOT/hostapd.conf"
    [ -f "$CONF" ] || { echo "找不到 $CONF"; return 3; }
    [ ${#PASS} -ge 8 ] && [ ${#PASS} -le 63 ] || { echo '热点密码长度必须是 8..63'; return 3; }
    case "$BAND:$CH" in
        2g:[1-9]|2g:1[0-3]) HW_MODE=g ;;
        5g:36|5g:40|5g:44|5g:48|5g:52|5g:56|5g:60|5g:64|5g:149|5g:153|5g:157|5g:161|5g:165) HW_MODE=a ;;
        *) echo '频段或信道无效'; return 3 ;;
    esac
    SSID_ESC=$(printf '%s' "$SSID" | sed 's/[\\&|]/\\&/g')
    PASS_ESC=$(printf '%s' "$PASS" | sed 's/[\\&|]/\\&/g')
    sed -i "s|^ssid=.*|ssid=$SSID_ESC|; s|^wpa_passphrase=.*|wpa_passphrase=$PASS_ESC|; s|^hw_mode=.*|hw_mode=$HW_MODE|; s|^channel=.*|channel=$CH|" "$CONF"
    # This Android 7 vendor hostapd supports 5 GHz HT20 but does not recognize
    # the upstream ieee80211ac option, even though the kernel reports VHT.
    sed -i '/^ieee80211ac=/d' "$CONF"
    # Keep the supervisor in STA state while the vendor Wi-Fi HAL is reset;
    # otherwise it may start a second hotspot instance during this window.
    printf '0' > "$BASE/config/wifi_ap"
    wifi_sta
    sleep 5
    printf '1' > "$BASE/config/wifi_ap"
    wifi_ap
    AP_TRIES=0
    while [ "$AP_TRIES" -lt 24 ]; do
        pidof hostapd >/dev/null 2>&1 &&
            iw dev wlan0 info 2>/dev/null | grep -q 'type AP' && break
        sleep 1; AP_TRIES=$((AP_TRIES + 1))
    done
    pidof hostapd >/dev/null 2>&1 && iw dev wlan0 info 2>/dev/null | grep -q 'type AP' || {
        echo "热点配置已保存，但 AP 未启动；请查看 Wi-Fi 日志"
        return 4
    }
    echo "热点已启动：SSID=$SSID BAND=$BAND CH=$CH"
}

show_log() {
    case "$1" in
        nas) tail -n 200 "$ROOT/var/log/copyparty.log" 2>/dev/null; tail -n 100 "$ROOT/var/log/iperf3.log" 2>/dev/null ;;
        wifi) tail -n 200 "$BASE/logs/hotspot.log" 2>/dev/null; logcat -d -v time -t 150 2>/dev/null | grep -i -E 'hostapd|wlan0|wifi' ;;
        controller) tail -n 250 "$BASE/logs/controller.log" 2>/dev/null ;;
        *) logcat -d -v time -t 300 2>/dev/null ;;
    esac
}

case "$1" in
    once) run_once ;;
    daemon)
        echo $$ > "$BASE/controller.pid"
        while true; do run_once; sleep 15; done
        ;;
    ethernet-daemon)
        echo $$ > "$BASE/ethernet.pid"
        stop_legacy_watchdogs
        while true; do manage_ethernet; sleep 1; done
        ;;
    status) print_status ;;
    wifi)
        case "$2" in
            sta)
                printf '0' > "$BASE/config/wifi_ap"; wifi_sta
                WIFI_TRIES=0
                while pidof hostapd >/dev/null 2>&1 && [ "$WIFI_TRIES" -lt 10 ]; do
                    sleep 1; WIFI_TRIES=$((WIFI_TRIES + 1))
                done
                pidof hostapd >/dev/null 2>&1 && { echo 'STA 切换未完成'; exit 3; }
                echo '已切换 STA，可扫描附近 Wi-Fi' ;;
            ap)
                printf '1' > "$BASE/config/wifi_ap"; wifi_ap
                WIFI_TRIES=0
                until pidof hostapd >/dev/null 2>&1 || [ "$WIFI_TRIES" -ge 18 ]; do
                    sleep 1; WIFI_TRIES=$((WIFI_TRIES + 1))
                done
                pidof hostapd >/dev/null 2>&1 || { echo 'AP 未启动，请查看 Wi-Fi 日志'; exit 3; }
                echo '热点已启动' ;;
            *) echo '模式必须为 ap 或 sta'; exit 2 ;;
        esac ;;
    ap-config) ap_config "$2" "$3" "$4" "$5" ;;
    apply-port) apply_port "$2" "$3" ;;
    ethernet-apply) apply_ethernet ;;
    usb-status) usb_status ;;
    usb-set) usb_set "$2" "$3" ;;
    log) show_log "$2" ;;
    *) echo 'usage: controller.sh {once|daemon|status|wifi ap|sta|ap-config|apply-port|log}'; exit 1 ;;
esac
