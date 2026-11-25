// فحص عميل معلق - M saeed Stuck Client Finder
// المبرمج محمد سعيد - 01123840671
'use strict';
const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { exec } = require('child_process');
const { Client } = require('ssh2');
const HTML = require('../app/src/main/assets/index.html');
const INSTALLER = require('../app/src/main/assets/msaeed-ghost-install.sh');

let isSea = false;
try { isSea = require('node:sea').isSea(); } catch (e) {}
const baseDir = isSea ? path.dirname(process.execPath) : (process.env.GH_DIR || __dirname);
const cfgPath = path.join(baseDir, 'ghost_hunter.json');

const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev';
const APP_CODE = typeof __APP_CODE__ !== 'undefined' ? __APP_CODE__ : 0;
const UPD = 'https://github.com/sm0902556-sketch/msaeed-shop/releases/download/ghost-latest/';
const https = require('https');
const { spawn } = require('child_process');
const DEF_RANGES = [15, 16, 17, 18, 20, 30, 35, 40, 50, 60, 70, 80, 90, 100].map(n => '192.168.' + n);
let cfg = { ranges: DEF_RANGES, from: 2, to: 254, user: 'root', password: '', port: 22, pin: '', listen_port: 8091, aps: [] };
try { Object.assign(cfg, JSON.parse(fs.readFileSync(cfgPath, 'utf8'))); } catch (e) {}
const save = () => { try { fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2)); } catch (e) {} };

const MIN_INACTIVE = 15000, MIN_CONNECTED = 20;
const tokens = new Set();
let progress = { busy: false, text: '', done: 0, total: 0 };

// ---------- helpers ----------
async function pool(items, n, fn) {
  const out = new Array(items.length); let i = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) { const k = i++; out[k] = await fn(items[k], k); progress.done++; }
  }));
  return out;
}

function probe(ip, port, timeout = 900) {
  return new Promise(res => {
    const s = net.connect({ host: ip, port });
    const end = ok => { s.destroy(); res(ok); };
    s.setTimeout(timeout, () => end(false));
    s.on('connect', () => end(true)); s.on('error', () => end(false));
  });
}

function arErr(e) {
  const m = String(e.message || e);
  if (/authentication methods failed/i.test(m)) return 'اسم المستخدم أو الباسورد غلط';
  if (/cmdtimeout/.test(m)) return 'دخلت على الأكسس بس الأمر ماخلصش في الوقت';
  if (/handshake|timed out|timeout/i.test(m)) return 'الأكسس مارجّعش رد SSH في الوقت';
  if (/ECONNREFUSED/.test(m)) return 'الـ SSH مقفول على الأكسس';
  if (/EHOSTUNREACH|ENETUNREACH/.test(m)) return 'مش قادر أوصل للأكسس';
  return m;
}

function sshRun(ip, cmd, stdin, timeout = 12000) {
  return new Promise(res => {
    const c = new Client(); let out = '', err = '', done = false;
    const t0 = Date.now(); const fin = e => { if (done) return; done = true; clearTimeout(t); try { c.end(); } catch (x) {} res({ ip, ok: !e, out, err: e ? arErr(e) : err, raw: e ? String(e.message || e) : '', ms: Date.now() - t0 }); };
    let ready = false; const t = setTimeout(() => fin(new Error(ready ? 'cmdtimeout' : 'handshake timeout')), timeout);
    c.on('ready', () => (ready = true) && c.exec(cmd, (e, s) => {
      if (e) return fin(e);
      s.on('data', d => out += d); s.stderr.on('data', d => err += d);
      s.on('close', () => fin());
      if (stdin) s.end(stdin); else s.end();
    }));
    c.on('error', e => fin(e));
    c.on('keyboard-interactive', (n, i, l, prompts, f) => f(prompts.map(() => cfg.password || '')));
    try {
      c.connect({
        host: ip, port: +cfg.port || 22, username: cfg.user || 'root', password: cfg.password || undefined,
        tryKeyboard: true, readyTimeout: timeout,
        algorithms: { kex: { append: ['diffie-hellman-group14-sha1', 'diffie-hellman-group1-sha1'] } }
      });
    } catch (e) { fin(e); }
  });
}

const okRange = r => /^\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(r);
function ipsOf(ranges) {
  const out = []; const a = Math.max(1, +cfg.from || 2), b = Math.min(254, +cfg.to || 254);
  for (const r of ranges) if (okRange(r)) for (let i = a; i <= b; i++) out.push(r + '.' + i);
  return out;
}
const rangeOf = ip => ip.split('.').slice(0, 3).join('.');

async function discover(ranges) {
  const ips = ipsOf(ranges);
  const port = +cfg.port || 22;
  progress = { busy: true, text: 'البحث الأول على الرينج', done: 0, total: ips.length };
  const r1 = await pool(ips, 120, ip => probe(ip, port, 1200));
  const found = ips.filter((_, i) => r1[i]);
  const rest = ips.filter((_, i) => !r1[i]);
  if (rest.length) {
    progress = { busy: true, text: `البحث التاني (لقيت ${found.length} لحد دلوقتي)`, done: 0, total: rest.length };
    const r2 = await pool(rest, 120, ip => probe(ip, port, 2500));
    found.push(...rest.filter((_, i) => r2[i]));
  }
  found.sort(ipSort);
  // حدّث الكاش: شيل القديم من الرينجات دي وحط الجديد
  const set = new Set(ranges);
  cfg.aps = cfg.aps.filter(ip => !set.has(rangeOf(ip))).concat(found).sort(ipSort);
  save();
  return found;
}
const ipSort = (a, b) => a.split('.').map(Number).reduce((s, n, i) => s || n - b.split('.').map(Number)[i], 0);

// ---------- scan ----------
const SCAN_CMD = `
echo "H $(uci -q get system.@system[0].hostname || cat /proc/sys/kernel/hostname)"
[ -x /usr/sbin/msaeed-ghost ] && echo "G $(pgrep -f /usr/sbin/msaeed-ghost >/dev/null 2>&1 && echo 1 || echo 0) $(grep -o 'GHOST_VER=[0-9]*' /usr/sbin/msaeed-ghost | cut -d= -f2)"
echo "U $(cut -d. -f1 /proc/uptime) $(cut -d' ' -f2 /proc/loadavg) $(awk '/MemAvailable/{a=$2}/MemTotal/{t=$2}END{print a+0,t+0}' /proc/meminfo) $(ubus list 'hostapd.*' 2>/dev/null | wc -l) $(dmesg 2>/dev/null | grep -ciE 'mcu.*(timeout|time ?out)|tx hang|firmware (crash|error)|wdt.*reset|message .* timeout') $(iw dev 2>/dev/null | grep -c 'type AP')"
for i in $(iw dev 2>/dev/null | awk '$1=="Interface"{print $2}'); do
  if [ -e /sys/class/net/$i/brport/port_no ]; then
    echo "I $i $(basename $(readlink /sys/class/net/$i/brport/bridge)) $(($(cat /sys/class/net/$i/brport/port_no)))"
  fi
  iw dev $i station dump 2>/dev/null | awk -v i=$i '/^Station/{m=$2;a="";s=""} /inactive time:/{a=$3} /^[ \\t]*signal:/{s=$2} /connected time:/{print "S",i,m,a,s,$3}'
done
for b in $(ls /sys/class/net/); do
  [ -r /sys/class/net/$b/brforward ] && hexdump -v -e '16/1 "%02x " "\\n"' /sys/class/net/$b/brforward 2>/dev/null | awk -v b=$b '$8=="00"{print "F",b,$1":"$2":"$3":"$4":"$5":"$6,$13$7}'
done
true`;

function parseAP(r) {
  const ap = { ip: r.ip, ok: r.ok, err: r.err, raw: r.raw, host: '', installed: false, ifaces: {}, stations: [], fdb: {} };
  if (!r.ok) return ap;
  for (const line of r.out.split('\n')) {
    const p = line.trim().split(/\s+/);
    if (p[0] === 'H') ap.host = p.slice(1).join(' ');
    else if (p[0] === 'G') { ap.installed = true; ap.running = p[1] !== '0'; ap.gver = +(p[2] || 1); }
    else if (p[0] === 'U' && p.length >= 8) Object.assign(ap, { up: +p[1], load: +p[2], memA: +p[3], memT: +p[4], hapd: +p[5], derr: +p[6], wifi: +p[7] });
    else if (p[0] === 'I' && p.length >= 4) ap.ifaces[p[1]] = { br: p[2], port: +p[3] };
    else if (p[0] === 'S' && p.length >= 6) ap.stations.push({ iface: p[1], mac: p[2].toLowerCase(), inactive: +p[3] || 0, signal: p[4], connected: +p[5] || 0 });
    else if (p[0] === 'F' && p.length >= 4) ap.fdb[p[1] + ' ' + p[2]] = parseInt(p[3], 16);
  }
  for (const s of ap.stations) {
    const ifc = ap.ifaces[s.iface];
    s.wired = false;
    if (ifc) {
      const port = ap.fdb[ifc.br + ' ' + s.mac];
      const wifiPorts = Object.values(ap.ifaces).filter(x => x.br === ifc.br).map(x => x.port);
      s.wired = port !== undefined && !wifiPorts.includes(port);
    }
  }
  return ap;
}

function analyze(aps) {
  const byMac = {};
  for (const ap of aps) for (const s of ap.stations) (byMac[s.mac] = byMac[s.mac] || []).push({ ...s, ip: ap.ip, host: ap.host });
  const ghosts = [], dups = [];
  for (const [mac, list] of Object.entries(byMac)) {
    if (list.length >= 2) {
      const best = list.reduce((a, b) => (b.inactive < a.inactive ? b : a));
      for (const e of list) {
        if (e === best) continue;
        if (e.inactive >= MIN_INACTIVE && e.inactive > best.inactive + 5000) {
          ghosts.push({ ...e, real: best.ip, realHost: best.host, reason: 'two' });
        } else dups.push({ ...e, real: best.ip, realHost: best.host });
      }
    } else {
      const e = list[0];
      if (e.wired && e.inactive >= MIN_INACTIVE && e.connected >= MIN_CONNECTED) ghosts.push({ ...e, real: '', reason: 'wired' });
    }
  }
  return { ghosts, dups, clients: Object.keys(byMac).length, byMac };
}

async function scan(ranges, rediscover) {
  const prev = cfg.aps.filter(ip => ranges.includes(rangeOf(ip)));
  const targets = [...new Set([...(await discover(ranges)), ...prev])].sort(ipSort);
  cfg.aps = [...new Set([...cfg.aps, ...prev])].sort(ipSort); save();
  progress = { busy: true, text: 'بفحص الأكسسات', done: 0, total: targets.length };
  const res = await pool(targets, 40, ip => sshRun(ip, SCAN_CMD, null, 12000));
  const retry = res.map((r, i) => i).filter(i => !res[i].ok && !/الباسورد/.test(res[i].err));
  if (retry.length) {
    progress = { busy: true, text: 'بعيد المحاولة مع الأكسسات اللي مردتش', done: 0, total: retry.length };
    const again = await pool(retry, 15, i => sshRun(targets[i], SCAN_CMD, null, 20000));
    retry.forEach((i, k) => { res[i] = again[k]; });
  }
  const aps = res.map(parseAP);
  const ok = aps.filter(a => a.ok);
  const an = analyze(ok);
  const all = [];
  for (const [mac, l] of Object.entries(an.byMac)) for (const e of l) all.push({ mac, ip: e.ip, host: e.host, iface: e.iface, inactive: e.inactive, signal: e.signal });
  let mtErr;
  if (cfg.mt_on && cfg.mt_ip) {
    progress = { busy: true, text: 'بجيب IP العملاء من المايكروتك', done: 0, total: 1 };
    try {
      const cm = await clientMap();
      for (const l of [an.ghosts, an.dups, all]) for (const o of l) Object.assign(o, cm[o.mac] || {});
    } catch (e) { mtErr = e.message; }
  }
  return {
    mtErr, scanned: targets.length, okCount: ok.length,
    failed: aps.filter(a => !a.ok).map(a => ({ ip: a.ip, err: a.err, raw: a.raw })),
    installed: ok.filter(a => a.installed).length,
    clients: an.clients, ghosts: an.ghosts, dups: an.dups, all,
    aps: ok.map(a => ({ ip: a.ip, host: a.host, n: a.stations.length, installed: a.installed, running: !!a.running, gver: a.gver || 0, up: a.up, load: a.load, memA: a.memA, memT: a.memT, hapd: a.hapd, derr: a.derr, wifi: a.wifi ?? -1 }))
  };
}

const reMac = /^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/, reIf = /^[\w.-]{1,20}$/, reIp = /^\d{1,3}(\.\d{1,3}){3}$/;
async function kick(items) {
  items = (items || []).filter(x => reMac.test(x.mac) && reIf.test(x.iface) && reIp.test(x.ip));
  progress = { busy: true, text: 'بفصل العملاء المعلقين', done: 0, total: items.length };
  return pool(items, 10, async x => {
    const cmd = `ubus call hostapd.${x.iface} del_client '{"addr":"${x.mac}","reason":3,"deauth":true,"ban_time":0}' >/dev/null 2>&1 || iw dev ${x.iface} station del ${x.mac} && echo KICKED`;
    const r = await sshRun(x.ip, cmd);
    return { ip: x.ip, mac: x.mac, ok: r.ok && r.out.includes('KICKED'), err: r.err };
  });
}

const UNINSTALL = `/etc/init.d/msaeed-ghost stop 2>/dev/null; /etc/init.d/msaeed-ghost disable 2>/dev/null
rm -f /usr/sbin/msaeed-ghost /etc/init.d/msaeed-ghost
sed -i '/msaeed-ghost/d' /etc/sysupgrade.conf 2>/dev/null
echo "msaeed-ghost removed OK"`;

async function install(ranges, remove) {
  const targets = await discover(ranges);
  progress = { busy: true, text: remove ? 'بشيل السكريبت' : 'بركّب السكريبت', done: 0, total: targets.length };
  return pool(targets, 20, async ip => {
    const r = await sshRun(ip, 'sh -s', remove ? UNINSTALL : INSTALLER, 20000);
    const ok = r.ok && /OK/.test(r.out);
    return { ip, ok, err: ok ? '' : (r.err || r.out).slice(0, 200) };
  });
}

// ---------- RouterOS API (IP العملاء من المايكروتك) ----------
function rosClient(host, port, user, pass) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host, port });
    let buf = Buffer.alloc(0), waiters = [];
    sock.setTimeout(20000, () => sock.destroy(new Error('timeout')));
    const encLen = l => l < 0x80 ? Buffer.from([l]) : l < 0x4000 ? Buffer.from([(l >> 8) | 0x80, l & 255])
      : l < 0x200000 ? Buffer.from([(l >> 16) | 0xC0, (l >> 8) & 255, l & 255]) : Buffer.from([((l >>> 24) & 0x0F) | 0xE0, (l >> 16) & 255, (l >> 8) & 255, l & 255]);
    function readSentence() { // returns words or null if incomplete
      let o = 0; const words = [];
      while (true) {
        if (o >= buf.length) return null;
        const b = buf[o]; let len, n;
        if ((b & 0x80) === 0) { len = b; n = 1; } else if ((b & 0xC0) === 0x80) { if (o + 2 > buf.length) return null; len = ((b & 0x3F) << 8) | buf[o + 1]; n = 2; }
        else if ((b & 0xE0) === 0xC0) { if (o + 3 > buf.length) return null; len = ((b & 0x1F) << 16) | (buf[o + 1] << 8) | buf[o + 2]; n = 3; }
        else { if (o + 4 > buf.length) return null; len = ((b & 0x0F) << 24) | (buf[o + 1] << 16) | (buf[o + 2] << 8) | buf[o + 3]; n = 4; }
        o += n; if (len === 0) { buf = buf.slice(o); return words; }
        if (o + len > buf.length) return null;
        words.push(buf.slice(o, o + len).toString('utf8')); o += len;
      }
    }
    let cur = null;
    function pump() { let w; while (cur && (w = readSentence())) {
      const a = {}; for (const x of w.slice(1)) if (x[0] === '=') { const k = x.indexOf('=', 1); if (k < 0) a[x.slice(1)] = ''; else a[x.slice(1, k)] = x.slice(k + 1); }
      if (w[0] === '!re') cur.rows.push(a); else if (w[0] === '!trap') cur.trap = a.message || 'error';
      else if (w[0] === '!fatal') { const c = cur; cur = null; c.rej(new Error(w.join(' '))); }
      else if (w[0] === '!done') { const c = cur; cur = null; c.trap ? c.rej(new Error(c.trap)) : c.res({ rows: c.rows, done: a }); next(); }
    } }
    function next() { if (!cur && waiters.length) { cur = waiters.shift(); sock.write(cur.data); pump(); } }
    const run = (...words) => new Promise((res, rej) => {
      waiters.push({ data: Buffer.concat(words.map(w => { const b = Buffer.from(w, 'utf8'); return Buffer.concat([encLen(b.length), b]); }).concat([Buffer.from([0])])), rows: [], res, rej }); next();
    });
    sock.on('data', d => { buf = Buffer.concat([buf, d]); pump(); });
    sock.on('error', e => { if (cur) cur.rej(e); reject(new Error(`مش قادر أوصل للمايكروتك على ${host}:${port} — اتأكد إن خدمة api شغالة من IP > Services`)); });
    sock.on('connect', async () => {
      try {
        const r = await run('/login', '=name=' + user, '=password=' + pass);
        if (r.done.ret) {
          const h = crypto.createHash('md5'); h.update(Buffer.from([0])); h.update(pass); h.update(Buffer.from(r.done.ret, 'hex'));
          await run('/login', '=name=' + user, '=response=00' + h.digest('hex'));
        }
        resolve({ run: async (...w) => (await run(...w)).rows, close: () => sock.destroy() });
      } catch (e) { sock.destroy(); reject(new Error('يوزر أو باسورد المايكروتك غلط')); }
    });
  });
}
async function mtOpen() {
  if (!cfg.mt_ip) throw new Error('اكتب IP المايكروتك في الإعدادات');
  return rosClient(cfg.mt_ip, +cfg.mt_port || 8728, cfg.mt_user || 'admin', cfg.mt_pass || '');
}
async function clientMap() {
  const m = {}; const r = await mtOpen();
  try {
    for (const x of await r.run('/ip/dhcp-server/lease/print', '=.proplist=mac-address,address,host-name'))
      m[(x['mac-address'] || '').toLowerCase()] = { cip: x.address || '', cuser: '', cname: x['host-name'] || '' };
    try {
      for (const x of await r.run('/ip/hotspot/host/print', '=.proplist=mac-address,address')) {
        const v = m[(x['mac-address'] || '').toLowerCase()] ||= { cip: '', cuser: '', cname: '' }; if (!v.cip) v.cip = x.address || '';
      }
      for (const x of await r.run('/ip/hotspot/active/print', '=.proplist=mac-address,address,user')) {
        const v = m[(x['mac-address'] || '').toLowerCase()] ||= { cip: '', cuser: '', cname: '' }; v.cip = x.address || v.cip; v.cuser = x.user || '';
      }
    } catch (e) {}
  } finally { r.close(); }
  return m;
}

// أمر بيتنفّذ بعد d ثانية وبيكمّل حتى لو اتصال الـ SSH اتقفل
function delayedCmd(d, act) {
  const job = `sleep ${d}; ${act === 'reboot' ? 'reboot' : 'wifi'}`;
  return `if command -v start-stop-daemon >/dev/null 2>&1; then start-stop-daemon -S -b -m -p /tmp/msaeed-act.$$.pid -x /bin/sh -- -c '${job}'; ` +
         `else (trap '' HUP INT TERM; ${job}) >/dev/null 2>&1 </dev/null & fi; echo DONE_OK`;
}

// ---------- تشخيص عميل ----------
async function rq(r, menu, filter) { try { return await r.run(menu + '/print', '?' + filter); } catch (e) { return []; } }
async function clientDiag(query) {
  query = String(query || '').trim();
  const o = { q: query }; let mac = null;
  const qm = query.toLowerCase().replace(/[^0-9a-f]/g, '');
  if (qm.length === 12 && /^[0-9a-f:\-. ]+$/i.test(query)) mac = qm.match(/../g).join(':');
  const mtOn = !!(cfg.mt_on && cfg.mt_ip); o.mtOn = mtOn; const mt = {};
  if (mtOn) {
    progress = { busy: true, text: 'بسأل المايكروتك عن العميل', done: 0, total: 1 };
    let r;
    try {
      r = await mtOpen();
      if (!mac) {
        let hit = [];
        if (reIp.test(query)) { for (const m of ['/ip/hotspot/active', '/ip/hotspot/host', '/ip/dhcp-server/lease', '/ip/arp']) { hit = await rq(r, m, 'address=' + query); if (hit.length) break; } }
        else { hit = await rq(r, '/ip/hotspot/active', 'user=' + query); if (!hit.length) hit = await rq(r, '/ip/hotspot/cookie', 'user=' + query); }
        const h = hit.find(x => x['mac-address']); if (h) mac = h['mac-address'].toLowerCase();
      }
      if (mac) {
        const M = mac.toUpperCase();
        mt.leases = await rq(r, '/ip/dhcp-server/lease', 'mac-address=' + M);
        mt.hosts = await rq(r, '/ip/hotspot/host', 'mac-address=' + M);
        mt.active = await rq(r, '/ip/hotspot/active', 'mac-address=' + M);
        mt.cookies = await rq(r, '/ip/hotspot/cookie', 'mac-address=' + M);
        mt.arp = await rq(r, '/ip/arp', 'mac-address=' + M);
        const ips = new Set([...mt.leases, ...mt.hosts, ...mt.active, ...mt.arp].map(x => x.address).filter(Boolean));
        mt.conflicts = [];
        for (const ip of ips) for (const m of ['/ip/dhcp-server/lease', '/ip/arp'])
          for (const x of await rq(r, m, 'address=' + ip)) if ((x['mac-address'] || M).toUpperCase() !== M) mt.conflicts.push({ ...x, _src: m.includes('lease') ? 'DHCP' : 'ARP' });
      }
      mt.ok = true;
    } catch (e) { mt.ok = false; mt.err = e.message; } finally { if (r) r.close(); }
  }
  o.mt = mt;
  if (!mac) { o.error = mtOn ? 'مالقيتش العميل ده على المايكروتك. اكتب الماك بتاعه.' : 'اكتب الماك بتاع العميل، أو شغّل «المايكروتك» من الإعدادات عشان أدوّر بالـ IP أو اسم الكارت.'; return o; }
  o.mac = mac;
  const targets = cfg.aps.slice(); o.apsKnown = targets.length;
  const cmd = `echo "H $(uci -q get system.@system[0].hostname || cat /proc/sys/kernel/hostname)"
for i in $(iw dev 2>/dev/null | awk '$1=="Interface"{print $2}'); do iw dev $i station get ${mac} 2>/dev/null | awk -v i=$i '/inactive time:/{a=$3} /^[ \\t]*signal:/{s=$2} /connected time:/{c=$3} END{if(a!="")print "S",i,a,s,c}'; done
true`;
  progress = { busy: true, text: 'بدوّر على العميل في الأكسسات', done: 0, total: targets.length };
  const res = await pool(targets, 40, ip => sshRun(ip, cmd, null, 12000));
  o.aps = []; o.apsFailed = 0;
  for (const r of res) {
    if (!r || !r.ok) { o.apsFailed++; continue; }
    let host = '';
    for (const line of r.out.split('\n')) { const p = line.trim().split(/\s+/);
      if (p[0] === 'H') host = line.trim().slice(1).trim();
      if (p[0] === 'S' && p.length >= 5) o.aps.push({ ip: r.ip, host, iface: p[1], inactive: +p[2] || 0, signal: p[3], connected: +p[4] || 0 }); }
  }
  return o;
}
async function mtFix(mac) {
  if (!/^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/.test(mac || '')) return { error: 'ماك غلط' };
  const M = mac.toUpperCase(), log = [];
  progress = { busy: true, text: 'بنضّف العميل من المايكروتك', done: 0, total: 1 };
  const r = await mtOpen();
  try {
    for (const [m, t, dynOnly] of [['/ip/hotspot/active', 'خرّجته من الهوتسبوت (Active)', 0], ['/ip/hotspot/host', 'مسحت الـ Host', 0], ['/ip/hotspot/cookie', 'مسحت الكوكي', 0], ['/ip/dhcp-server/lease', 'مسحت الـ DHCP Lease', 1], ['/ip/arp', 'مسحت الـ ARP', 1]])
      for (const x of await rq(r, m, 'mac-address=' + M)) {
        if (dynOnly && x.dynamic !== 'true') { log.push(`• سبت ${m.slice(4)} الثابت ${x.address || ''} زي ما هو`); continue; }
        try { await r.run(m + '/remove', '=.id=' + x['.id']); log.push(`✓ ${t} ${x.address || x.user || ''}`); } catch (e) { log.push(`✗ ${t}: ${e.message}`); }
      }
  } finally { r.close(); }
  if (!log.length) log.push('مفيش حاجة كانت محتاجة تتمسح على المايكروتك.');
  return { log };
}

// ---------- اختبار أكسس خطوة بخطوة ----------
function banner(ip, port) {
  return new Promise(res => {
    const t0 = Date.now(); const s = net.connect({ host: ip, port }); let b = '', opened = false;
    const end = (r) => { s.destroy(); res(r); };
    s.setTimeout(8000, () => end({ opened, banner: b.trim(), err: 'timeout', ms: Date.now() - t0 }));
    s.on('connect', () => { opened = true; s.setTimeout(8000); });
    s.on('data', d => { b += d; if (b.includes('\n') || b.length > 200) end({ opened, banner: b.split('\n')[0].trim(), ms: Date.now() - t0 }); });
    s.on('error', e => end({ opened, banner: '', err: e.message, ms: Date.now() - t0 }));
  });
}
async function diag(ip) {
  const steps = []; const st = (ok, text) => steps.push({ ok, text });
  if (!reIp.test(ip)) { st(false, 'IP غلط'); return { ip, steps }; }
  const port = +cfg.port || 22;
  const b = await banner(ip, port);
  if (!b.opened) { st(false, `بورت ${port} مش بيرد (${b.err})`); return { ip, steps }; }
  st(true, `بورت ${port} مفتوح`);
  if (!b.banner) { st(false, 'البورت مفتوح بس مارجّعش أي رد SSH خلال 8 ثواني. غالباً الجهاز مش أوبن ورت، أو الـ SSH بتاعه واقف.'); return { ip, steps }; }
  if (!b.banner.startsWith('SSH-')) { st(false, 'الجهاز رد بحاجة مش SSH: ' + b.banner); return { ip, steps }; }
  st(true, `رد بـ ${b.banner} (${b.ms} ms)`);
  const r = await sshRun(ip, 'echo LOGIN_OK; cat /etc/openwrt_release 2>/dev/null | grep DISTRIB_DESCRIPTION; uname -m', null, 40000);
  if (!r.ok) { st(false, `${r.err} (${r.ms / 1000} ثانية)`); st(false, 'التفاصيل: ' + r.raw); return { ip, steps }; }
  st(true, `دخلت ونفذت أمر في ${r.ms / 1000} ثانية`);
  const info = r.out.replace('LOGIN_OK', '').trim().split('\n').join(' — '); if (info) st(true, info);
  const sc = await sshRun(ip, SCAN_CMD, null, 40000);
  st(sc.ok, sc.ok ? `أمر الفحص خلص في ${sc.ms / 1000} ثانية` : `أمر الفحص: ${sc.err} (${sc.ms / 1000} ثانية)`);
  return { ip, steps };
}

// ---------- التحديث الأوتوماتيك ----------
function httpsGet(url, onData, depth = 0) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'User-Agent': 'msaeed-ghost-hunter', 'Cache-Control': 'no-cache' } }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && depth < 6) {
        res.resume(); return resolve(httpsGet(new URL(res.headers.location, url).toString(), onData, depth + 1));
      }
      if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
      const total = +res.headers['content-length'] || 0; const chunks = []; let got = 0;
      res.on('data', d => { chunks.push(d); got += d.length; if (onData) onData(got, total); });
      res.on('end', () => resolve(Buffer.concat(chunks)));
      res.on('error', reject);
    });
    req.setTimeout(60000, () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}
async function updCheck() {
  const v = JSON.parse((await httpsGet(UPD + 'version.json?t=' + Date.now())).toString('utf8'));
  return { pc: true, name: v.name, notes: v.notes, code: v.code, current: APP_VERSION, newer: (+v.code || 0) > APP_CODE };
}
let updating = false;
async function updGo() {
  if (!isSea || process.platform !== 'win32') throw new Error('التحديث الأوتوماتيك شغال في نسخة الويندوز (exe) بس');
  if (updating) throw new Error('التحديث شغال بالفعل');
  updating = true;
  try {
    progress = { busy: true, text: 'بنزّل التحديث', done: 0, total: 100 };
    const exe = process.execPath, tmp = exe + '.new', old = exe + '.old';
    const buf = await httpsGet(UPD + 'MsaeedGhostHunter.exe?t=' + Date.now(), (g, t) => { if (t) progress.done = Math.floor(g * 100 / t); });
    if (buf.length < 20e6 || buf[0] !== 0x4D || buf[1] !== 0x5A) throw new Error('الملف اللي اتنزل مش سليم');
    fs.writeFileSync(tmp, buf);
    try { fs.unlinkSync(old); } catch (e) {}
    fs.renameSync(exe, old);          // ويندوز بيسمح نغيّر اسم البرنامج وهو شغال
    fs.renameSync(tmp, exe);
    setTimeout(() => {
      server.close();
      spawn('cmd.exe', ['/c', `ping -n 3 127.0.0.1 >nul & start "" "${exe}"`], { detached: true, stdio: 'ignore', windowsHide: true, env: { ...process.env, GH_NO_BROWSER: '1' } }).unref();
      setTimeout(() => process.exit(0), 300);
    }, 500);
    return { ok: true };
  } catch (e) { updating = false; throw e; }
  finally { progress = { busy: false, text: '', done: 0, total: 0 }; }
}
// امسح النسخة القديمة بعد التحديث
setTimeout(() => { try { fs.unlinkSync(process.execPath + '.old'); } catch (e) {} }, 5000);

// ---------- http ----------
function lanUrls() {
  const out = [];
  for (const l of Object.values(os.networkInterfaces())) for (const a of l || [])
    if (a.family === 'IPv4' && !a.internal) out.push(`http://${a.address}:${cfg.listen_port}`);
  return out;
}

function body(req) {
  return new Promise(r => { let d = ''; req.on('data', c => { d += c; if (d.length > 1e6) req.destroy(); }); req.on('end', () => { try { r(JSON.parse(d || '{}')); } catch (e) { r({}); } }); });
}
const send = (res, obj, code = 200) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
// ---------- إيقاف البرنامج عند عميل من بعيد (لو مادفعش) ----------
const LIC_URL = 'https://raw.githubusercontent.com/sm0902556-sketch/msaeed-shop/ghost-hunter/ghost-control.json';
const LIC_GRACE = 10 * 24 * 3600 * 1000, LIC_MSG = 'البرنامج متوقف. كلّم المبرمج محمد سعيد على واتساب 01123840671';
function licId() {
  if (!cfg.device_id) { const abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; cfg.device_id = 'MS-' + Array.from(crypto.randomBytes(6), b => abc[b % abc.length]).join('');
    cfg.lic = { blocked: false, lastOk: Date.now(), msg: LIC_MSG }; save(); }
  return cfg.device_id;
}
function licState() { licId(); const l = cfg.lic || {}; return l.blocked ? 'blocked' : (Date.now() - (l.lastOk || 0) > LIC_GRACE ? 'offline' : 'ok'); }
const licInfo = () => ({ state: licState(), id: licId(), msg: (cfg.lic || {}).msg || LIC_MSG });
async function licCheck() {
  const me = licId();
  try {
    const j = JSON.parse((await httpsGet(LIC_URL + '?t=' + Date.now())).toString('utf8'));
    const blocked = (j.blocked || []).some(x => String(x).trim().toUpperCase() === me.toUpperCase());
    cfg.lic = { blocked, lastOk: Date.now(), msg: j.message || LIC_MSG }; save();
  } catch (e) {}
  return licInfo();
}
licCheck(); setInterval(licCheck, 6 * 3600 * 1000);

// ---------- القفل: اسم المستخدم ثابت M saeed والباسورد بصمة PBKDF2 مش الباسورد نفسه ----------
const LOCK_USER = 'M saeed';
let lkFails = 0, lkBlocked = 0;
const tok = req => ((req.headers.cookie || '').match(/gh=([a-f0-9]+)/) || [])[1];
const pwRec = pw => { const salt = crypto.randomBytes(16); return { salt: salt.toString('hex'), hash: crypto.pbkdf2Sync(String(pw), salt, 120000, 32, 'sha256').toString('hex') }; };
const pwOk = pw => { const l = cfg.lock; if (!l || !l.hash) return false;
  const h = crypto.pbkdf2Sync(String(pw || ''), Buffer.from(l.salt, 'hex'), 120000, 32, 'sha256'); const ref = Buffer.from(l.hash, 'hex');
  return h.length === ref.length && crypto.timingSafeEqual(h, ref); };
const authed = req => !!(cfg.lock && cfg.lock.hash) && tokens.has(tok(req));
const newSession = res => { const t = crypto.randomBytes(16).toString('hex'); tokens.add(t); res.setHeader('Set-Cookie', `gh=${t}; Path=/; HttpOnly; SameSite=Strict`); };
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function guard(res, fn) {
  if (progress.busy) return send(res, { error: 'فيه عملية شغالة دلوقتي، استنى لما تخلص' });
  progress.busy = true;
  try { send(res, await fn()); } catch (e) { send(res, { error: String(e.message || e) }); }
  finally { progress = { busy: false, text: '', done: 0, total: 0 }; }
}

const server = http.createServer(async (req, res) => {
  const u = req.url.split('?')[0];
  if (u === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(HTML); }
  if (u === '/api/lic') return send(res, req.method === 'POST' ? await licCheck() : licInfo());
  if (licState() !== 'ok' && u.startsWith('/api/') && !['/api/version', '/api/upd_check', '/api/upd_go'].includes(u)) return send(res, { error: 'stopped' }, 403);
  if (u === '/api/lock_state') return send(res, { state: !(cfg.lock && cfg.lock.hash) ? 'setup' : authed(req) ? 'open' : 'locked', user: LOCK_USER });
  if (u === '/api/lock_setup') {
    const b = await body(req);
    if (cfg.lock && cfg.lock.hash) return send(res, { error: 'الباسورد متعمل بالفعل' });
    if (!b.pw || String(b.pw).length < 4) return send(res, { error: 'الباسورد لازم يبقى 4 حروف أو أرقام على الأقل' });
    cfg.lock = pwRec(b.pw); delete cfg.pin; save(); newSession(res); return send(res, { ok: true });
  }
  if (u === '/api/lock_login') {
    const b = await body(req); const now = Date.now();
    if (now < lkBlocked) return send(res, { error: `محاولات كتير غلط، استنى ${Math.ceil((lkBlocked - now) / 1000)} ثانية` });
    if (String(b.user || '').trim() !== LOCK_USER || !pwOk(b.pw)) {
      await sleep(800);
      if (++lkFails >= 5) { lkFails = 0; lkBlocked = now + 60000; return send(res, { error: 'محاولات كتير غلط، البرنامج اتقفل دقيقة' }); }
      return send(res, { error: 'اسم المستخدم أو الباسورد غلط' });
    }
    lkFails = 0; newSession(res); return send(res, { ok: true });
  }
  if (u === '/api/lock_logout') { tokens.delete(tok(req)); return send(res, { ok: true }); }
  if (!u.startsWith('/api/')) { res.writeHead(404); return res.end(); }
  if (!authed(req)) return send(res, { error: 'locked' }, 401);
  const b = req.method === 'POST' ? await body(req) : {};
  const ranges = Array.isArray(b.ranges) ? b.ranges.filter(okRange) : cfg.ranges;
  switch (u) {
    case '/api/progress': return send(res, progress);
    case '/api/lock_change': {
      if (!pwOk(b.old)) { await sleep(800); return send(res, { error: 'الباسورد الحالي غلط' }); }
      if (!b.pw || String(b.pw).length < 4) return send(res, { error: 'الباسورد لازم يبقى 4 حروف أو أرقام على الأقل' });
      cfg.lock = pwRec(b.pw); save(); return send(res, { ok: true });
    }
    case '/api/version': return send(res, { name: APP_VERSION, code: APP_CODE });
    case '/api/upd_check': try { return send(res, await updCheck()); } catch (e) { return send(res, { error: 'مفيش نت أو مقدرتش أوصل لسيرفر التحديث' }); }
    case '/api/upd_go': try { return send(res, await updGo()); } catch (e) { return send(res, { error: e.message }); }
    case '/api/config':
      if (req.method === 'POST') {
        const n = b.config || {};
        if (Array.isArray(n.ranges)) cfg.ranges = n.ranges.map(s => String(s).trim()).filter(okRange);
        for (const k of ['user', 'mt_ip', 'mt_user']) if (typeof n[k] === 'string') cfg[k] = n[k].trim();
        if (typeof n.mt_pass === 'string' && n.mt_pass !== '') cfg.mt_pass = n.mt_pass;
        if (n.mt_clear) cfg.mt_pass = '';
        if (typeof n.mt_on === 'boolean') cfg.mt_on = n.mt_on;
        if (typeof n.password === 'string' && n.password !== '') cfg.password = n.password;
        if (n.clear_password) cfg.password = '';
        for (const k of ['from', 'to', 'port', 'listen_port', 'mt_port']) if (+n[k]) cfg[k] = +n[k];
        save();
      }
      return send(res, { config: { ...cfg, password: '', mt_pass: '', aps: undefined, lock: undefined, pin: undefined }, has_password: !!cfg.password, has_mt_password: !!cfg.mt_pass, ap_count: cfg.aps.length, urls: lanUrls() });
    case '/api/mt_test': try { const r = await mtOpen(); const id = await r.run('/system/identity/print'); r.close(); return send(res, { ok: true, name: (id[0] || {}).name }); } catch (e) { return send(res, { error: e.message }); }
    case '/api/ap_action': {
      const ip = String(b.ip || ''), act = b.action;
      if (!reIp.test(ip)) return send(res, { error: 'IP غلط' });
      const d = Math.max(1, Math.min(120, parseInt(b.delay) || 1)); // تأخير بالثواني قبل التنفيذ
      const cmd = act === 'wifi' || act === 'reboot' ? delayedCmd(d, act) : null;
      if (!cmd) return send(res, { error: 'أمر غير معروف' });
      const r = await sshRun(ip, cmd, null, 30000);
      return send(res, r.ok && r.out.includes('DONE_OK') ? { ok: true } : { error: r.err || 'مارجعش رد' });
    }
    case '/api/diag': return send(res, await diag(String(b.ip || '')));
    case '/api/discover': return guard(res, async () => { const f = await discover(ranges); return { found: f.length, total: cfg.aps.length }; });
    case '/api/scan': return guard(res, () => scan(ranges, !!b.rediscover));
    case '/api/kick': return guard(res, async () => ({ results: await kick(b.items) }));
    case '/api/client_diag': return guard(res, () => clientDiag(b.q));
    case '/api/mt_fix': return guard(res, () => mtFix(b.mac));
    case '/api/install': return guard(res, async () => ({ results: await install(ranges, !!b.remove) }));
  }
  send(res, { error: 'not found' }, 404);
});

function openBrowser(url) {
  const cmd = process.platform === 'win32' ? `start "" "${url}"` : process.platform === 'darwin' ? `open "${url}"`
    : (process.env.PREFIX || '').includes('com.termux') ? `termux-open-url "${url}"` : `xdg-open "${url}"`;
  exec(cmd, () => {});
}

const local = `http://127.0.0.1:${cfg.listen_port}`;
server.on('error', e => {
  if (e.code === 'EADDRINUSE') { openBrowser(local); setTimeout(() => process.exit(0), 1500); }
  else { console.error(e); }
});
server.listen(cfg.listen_port, '0.0.0.0', () => {
  console.log('==============================================');
  console.log('  M saeed Stuck Clients ' + APP_VERSION + '  -  01123840671');
  console.log('==============================================');
  console.log('  PC     : ' + local);
  for (const u of lanUrls()) console.log('  Mobile : ' + u);
  console.log('  Close this window to stop the program.');
  if (!process.env.GH_NO_BROWSER) setTimeout(() => openBrowser(local), 600);
});
