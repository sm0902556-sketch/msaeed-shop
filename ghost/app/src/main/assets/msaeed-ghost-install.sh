#!/bin/sh
# تركيب msaeed-ghost على الأكسس - المبرمج محمد سعيد 01123840671
cat > /usr/sbin/msaeed-ghost <<'MSAEED_EOF'
#!/bin/sh
# msaeed-ghost - Ghost client killer for OpenWrt access points
# المبرمج محمد سعيد - 01123840671
#
# المشكلة: العميل بيتنقل من أكسس لأكسس تاني، والأكسس القديم بيفضل شايله
# متوصل عنده (شبح)، فالعميل يبقى "متصل ولا يتوفر إنترنت".
#
# الحل: الأكسس يبص على كل عميل متوصل عليه. لو العميل ساكت عنده من فترة،
# وفي نفس الوقت جدول الـ bridge بيقول إن الـ MAC بتاعه جاي من الكابل
# (يعني بيتكلم من أكسس تاني)، يبقى ده شبح فيتفصل من هنا بس.
# العميل نفسه مش بيتأثر لأنه متوصل على الأكسس التاني.

INTERVAL=${INTERVAL:-10}            # كل كام ثانية يفحص
MIN_INACTIVE=${MIN_INACTIVE:-15000} # العميل ساكت على الأكسس ده بقاله كام ms
MIN_CONNECTED=${MIN_CONNECTED:-20}  # متوصل بقاله كام ثانية على الأقل
SYS=${SYS:-/sys/class/net}

log() { logger -t msaeed-ghost "$*" 2>/dev/null || echo "msaeed-ghost: $*"; }

# يطبع رقم الـ port اللي الـ MAC متعلم عليه في الـ bridge (decimal)، أو مايطبعش حاجة
fdb_port() { # $1=bridge $2=mac
	[ -r "$SYS/$1/brforward" ] || return
	hexdump -v -e '16/1 "%02x " "\n"' "$SYS/$1/brforward" 2>/dev/null |
	awk -v m="$2" '{
		mac = $1 ":" $2 ":" $3 ":" $4 ":" $5 ":" $6
		if (mac == m && $8 == "00") { print $13 $7; exit }
	}' | while read -r hx; do [ -n "$hx" ] && echo $((0x$hx)); done
}

kick() { # $1=iface $2=mac
	if ubus list "hostapd.$1" >/dev/null 2>&1; then
		ubus call "hostapd.$1" del_client \
			"{\"addr\":\"$2\",\"reason\":3,\"deauth\":true,\"ban_time\":0}" >/dev/null 2>&1 && return 0
	fi
	iw dev "$1" station del "$2" >/dev/null 2>&1
}

check_once() {
	wifi_ifaces=$(iw dev 2>/dev/null | awk '$1=="Interface"{print $2}')
	for ifc in $wifi_ifaces; do
		[ -e "$SYS/$ifc/brport/bridge" ] || continue
		br=$(basename "$(readlink "$SYS/$ifc/brport/bridge")")

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
		' | while read -r mac ina con; do
			[ "$ina" -ge "$MIN_INACTIVE" ] 2>/dev/null || continue
			[ "$con" -ge "$MIN_CONNECTED" ] 2>/dev/null || continue
			port=$(fdb_port "$br" "$mac")
			[ -n "$port" ] || continue
			case "$wports" in *" $port "*) continue ;; esac
			# الـ MAC جاي من الكابل = العميل على أكسس تاني = شبح هنا
			if kick "$ifc" "$mac"; then
				log "kicked ghost $mac from $ifc (idle ${ina}ms, seen on bridge port $port)"
			fi
		done
	done
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
echo "msaeed-ghost installed OK"
