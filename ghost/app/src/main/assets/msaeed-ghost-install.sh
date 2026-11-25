#!/bin/sh
# تركيب msaeed-ghost على الأكسس - المبرمج محمد سعيد 01123840671
cat > /usr/sbin/msaeed-ghost <<'MSAEED_EOF'
#!/bin/sh
# msaeed-ghost - Ghost client killer for OpenWrt access points
# المبرمج محمد سعيد - 01123840671
GHOST_VER=2
#
# المشكلة: العميل بيتنقل من أكسس لأكسس تاني، والأكسس القديم بيفضل شايله
# متوصل عنده (عميل معلّق)، فالعميل يبقى "متصل ولا يتوفر إنترنت".
#
# الحل: الأكسس يبص على كل عميل متوصل عليه. لو جدول الـ bridge قال ولو مرة
# إن الماك بتاعه جاي من الكابل (يعني بيتكلم من أكسس تاني)، السكريبت بيفتكر
# ده 5 دقايق. ولو العميل في نفس الوقت ساكت على الأكسس ده، يبقى معلّق فيتفصل
# من هنا بس. العميل نفسه مش بيتأثر لأنه متوصل على الأكسس التاني.

INTERVAL=${INTERVAL:-5}             # كل كام ثانية يفحص
MIN_INACTIVE=${MIN_INACTIVE:-12000} # العميل ساكت على الأكسس ده بقاله كام ms
MIN_CONNECTED=${MIN_CONNECTED:-20}  # متوصل بقاله كام ثانية على الأقل
REMEMBER=${REMEMBER:-300}           # يفتكر إن العميل اتشاف من الكابل كام ثانية
SYS=${SYS:-/sys/class/net}
STATE=${STATE:-/tmp/msaeed-ghost.seen}
TMP=/tmp/msaeed-ghost.$$

log() { logger -t msaeed-ghost "$*" 2>/dev/null || echo "msaeed-ghost: $*"; }

# جدول الـ bridge: سطر لكل ماك "mac port"
fdb_dump() { # $1=bridge
	if command -v hexdump >/dev/null 2>&1 && [ -r "$SYS/$1/brforward" ]; then
		hexdump -v -e '16/1 "%02x " "\n"' "$SYS/$1/brforward" 2>/dev/null |
		awk '$8=="00"{print $1":"$2":"$3":"$4":"$5":"$6, $13$7}' |
		while read -r m hx; do echo "$m $((0x$hx))"; done
	elif command -v brctl >/dev/null 2>&1; then
		brctl showmacs "$1" 2>/dev/null | awk 'NR>1 && $3=="no"{print tolower($2), $1}'
	fi
}

kick() { # $1=iface $2=mac
	if ubus list "hostapd.$1" >/dev/null 2>&1; then
		ubus call "hostapd.$1" del_client \
			"{\"addr\":\"$2\",\"reason\":3,\"deauth\":true,\"ban_time\":0}" >/dev/null 2>&1 && return 0
	fi
	iw dev "$1" station del "$2" >/dev/null 2>&1
}

seen_set() { # $1=mac $2=time  (سجّل إنه اتشاف من الكابل)
	{ grep -v "^$1 " "$STATE" 2>/dev/null; echo "$1 $2"; } > "$STATE.n"; mv "$STATE.n" "$STATE"
}
seen_del() { grep -q "^$1 " "$STATE" 2>/dev/null && { grep -v "^$1 " "$STATE" > "$STATE.n"; mv "$STATE.n" "$STATE"; }; }
seen_get() { awk -v m="$1" '$1==m{print $2; exit}' "$STATE" 2>/dev/null; }

check_once() {
	now=$(cut -d. -f1 /proc/uptime)
	touch "$STATE"
	# انسى القديم
	awk -v n="$now" -v r="$REMEMBER" 'n-$2<=r' "$STATE" > "$STATE.n" 2>/dev/null && mv "$STATE.n" "$STATE"

	wifi_ifaces=$(iw dev 2>/dev/null | awk '$1=="Interface"{print $2}')
	done_br=" "
	for ifc in $wifi_ifaces; do
		[ -e "$SYS/$ifc/brport/bridge" ] || continue
		br=$(basename "$(readlink "$SYS/$ifc/brport/bridge")")
		case "$done_br" in *" $br "*) ;; *) fdb_dump "$br" > "$TMP.$br"; done_br="$done_br$br " ;; esac

		# أرقام بورتات الواي فاي اللي على نفس الـ bridge (2.4 و 5G وغيرهم)
		wports=" "
		for w in $wifi_ifaces; do
			[ -e "$SYS/$w/brport/port_no" ] || continue
			wports="$wports$(($(cat "$SYS/$w/brport/port_no"))) "
		done

		iw dev "$ifc" station dump 2>/dev/null | awk '
			/^Station/          { mac=$2; ina=""; con="" }
			/inactive time:/    { ina=$3 }
			/connected time:/   { con=$3; if (mac!="" && ina!="") print mac, ina, con; mac="" }
		' > "$TMP.st"
		while read -r mac ina con; do
			port=$(awk -v m="$mac" '$1==m{print $2; exit}' "$TMP.$br")
			if [ -n "$port" ]; then
				case "$wports" in
					*" $port "*) seen_del "$mac" ;;            # العميل بيتكلم من هنا: مش معلّق
					*) seen_set "$mac" "$now" ;;              # جاي من الكابل: على أكسس تاني
				esac
			fi
			[ "$ina" -ge "$MIN_INACTIVE" ] 2>/dev/null || continue
			[ "$con" -ge "$MIN_CONNECTED" ] 2>/dev/null || continue
			t=$(seen_get "$mac")
			[ -n "$t" ] || continue
			if kick "$ifc" "$mac"; then
				seen_del "$mac"
				log "kicked ghost $mac from $ifc (idle ${ina}ms, seen on cable $((now - t))s ago)"
			fi
		done < "$TMP.st"
	done
	rm -f "$TMP".*
}

case "$1" in
	once) check_once ;;
	*) while true; do check_once; sleep "$INTERVAL"; done ;;
esac
MSAEED_EOF
cat > /etc/init.d/msaeed-ghost <<'MSAEED_EOF'
#!/bin/sh /etc/rc.common
# المبرمج محمد سعيد - 01123840671
START=99
USE_PROCD=1

start_service() {
	procd_open_instance
	procd_set_param command /usr/sbin/msaeed-ghost
	procd_set_param respawn 3600 5 0
	procd_close_instance
}
MSAEED_EOF
chmod +x /usr/sbin/msaeed-ghost /etc/init.d/msaeed-ghost
grep -q msaeed-ghost /etc/sysupgrade.conf 2>/dev/null || printf '/usr/sbin/msaeed-ghost\n/etc/init.d/msaeed-ghost\n' >> /etc/sysupgrade.conf
/etc/init.d/msaeed-ghost enable
/etc/init.d/msaeed-ghost restart
sleep 1
pgrep -f /usr/sbin/msaeed-ghost >/dev/null && echo "msaeed-ghost installed OK (running)" || echo "msaeed-ghost installed OK (not running!)"
