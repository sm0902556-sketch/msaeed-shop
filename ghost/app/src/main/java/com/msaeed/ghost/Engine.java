package com.msaeed.ghost;

// فحص عميل معلق - المبرمج محمد سعيد - 01123840671
// نفس منطق نسخة الكمبيوتر: اكتشاف الأكسسات، فحص المعلّقين، الفصل، تركيب السكريبت.

import android.content.Context;
import android.content.SharedPreferences;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class Engine {
    static final int MIN_INACTIVE = 15000, MIN_CONNECTED = 20;
    static final String[] DEF_RANGES = {"15", "16", "17", "18", "20", "30", "35", "40", "50", "60", "70", "80", "90", "100"};
    static final Pattern RANGE = Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    static final Pattern MAC = Pattern.compile("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$");
    static final Pattern IFACE = Pattern.compile("^[\\w.-]{1,20}$");
    static final Pattern IP = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final Context ctx;
    private final SharedPreferences prefs;
    private JSONObject cfg;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile String pText = "";
    private final AtomicInteger pDone = new AtomicInteger(), pTotal = new AtomicInteger();

    static {
        JSch.setConfig("StrictHostKeyChecking", "no");
        JSch.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        try {
            JSch.setConfig("kex", JSch.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group1-sha1");
            JSch.setConfig("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa");
        } catch (Exception e) { }
    }

    public Engine(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        try { cfg = new JSONObject(prefs.getString("json", "{}")); } catch (Exception e) { cfg = new JSONObject(); }
        try {
            if (!cfg.has("ranges")) {
                JSONArray a = new JSONArray();
                for (String r : DEF_RANGES) a.put("192.168." + r);
                cfg.put("ranges", a);
            }
            if (!cfg.has("from")) cfg.put("from", 2);
            if (!cfg.has("to")) cfg.put("to", 254);
            if (!cfg.has("user")) cfg.put("user", "root");
            if (!cfg.has("password")) cfg.put("password", "");
            if (!cfg.has("port")) cfg.put("port", 22);
            if (!cfg.has("aps")) cfg.put("aps", new JSONArray());
        } catch (Exception e) { }
    }

    private synchronized void save() { prefs.edit().putString("json", cfg.toString()).apply(); }

    // الباسورد اللي نجح لكل أكسس، عشان مانجربش كل الباسوردات كل مرة
    private final Map<String,String> ipPass = new ConcurrentHashMap<>();
    // قائمة الباسوردات اللي البرنامج يجربها (الرئيسي + الإضافيين)
    private List<String> passList() {
        List<String> out = new ArrayList<>();
        if (cfg.optBoolean("multi", false)) {
            LinkedHashMap<String,Boolean> m = new LinkedHashMap<>();
            String main = cfg.optString("password", "");
            if (!main.isEmpty()) m.put(main, true);
            JSONArray ex = cfg.optJSONArray("passwords");
            if (ex != null) for (int i = 0; i < ex.length(); i++) {
                String v = ex.optString(i, "");
                if (!v.isEmpty()) m.put(v, true);
            }
            out.addAll(m.keySet());
        }
        out.add("");   // الفاضي بيتجرّب دايماً (وهو الوحيد لو البحث بأكتر من باسورد مقفول)
        return out;
    }

    static String err(String m) {
        try { return new JSONObject().put("error", m).toString(); } catch (Exception e) { return "{\"error\":\"error\"}"; }
    }

    // ---------------- router ----------------
    public String handle(String path, String body) throws Exception {
        JSONObject b = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
        List<String> ranges = new ArrayList<>();
        JSONArray ra = b.optJSONArray("ranges");
        if (ra != null) { for (int i = 0; i < ra.length(); i++) if (RANGE.matcher(ra.optString(i)).matches()) ranges.add(ra.optString(i)); }
        else ranges = cfgRanges();

        switch (path) {
            case "pin": return "{\"ok\":true}";
            case "progress": {
                JSONObject p = new JSONObject();
                p.put("busy", busy.get()); p.put("text", pText); p.put("done", pDone.get()); p.put("total", pTotal.get());
                return p.toString();
            }
            case "diag": return diag(b.optString("ip")).toString();
            case "ap_action": {
                String ip = b.optString("ip"), act = b.optString("action");
                if (!IP.matcher(ip).matches()) return err("IP غلط");
                int d = Math.max(1, Math.min(120, b.optInt("delay", 1))); // تأخير بالثواني قبل التنفيذ
                String cmd = null;
                if ("wifi".equals(act) || "reboot".equals(act)) { // بيكمّل حتى لو اتصال الـ SSH اتقفل
                    String job = "sleep " + d + "; " + ("reboot".equals(act) ? "reboot" : "wifi");
                    cmd = "if command -v start-stop-daemon >/dev/null 2>&1; then start-stop-daemon -S -b -m -p /tmp/msaeed-act.$$.pid -x /bin/sh -- -c '" + job + "'; "
                        + "else (trap '' HUP INT TERM; " + job + ") >/dev/null 2>&1 </dev/null & fi; echo DONE_OK";
                }
                if (cmd == null) return err("أمر غير معروف");
                Res r = ssh(ip, cmd, null, 30000);
                return (r.ok && r.out.contains("DONE_OK")) ? "{\"ok\":true}" : err(r.err.isEmpty() ? "مارجعش رد" : r.err);
            }
            case "mt_test": {
                try (Ros r = mtOpen()) {
                    List<Map<String, String>> id = r.run("/system/identity/print");
                    return new JSONObject().put("ok", true).put("name", id.isEmpty() ? "" : id.get(0).get("name")).toString();
                } catch (Exception e) { return err(e.getMessage()); }
            }
            case "config": {
                if (b.has("config")) saveConfig(b.getJSONObject("config"));
                JSONObject c = new JSONObject(cfg.toString());
                JSONArray pls = new JSONArray();
                String mainp = c.optString("password", ""); if (!mainp.isEmpty()) pls.put(mainp);
                JSONArray exj = c.optJSONArray("passwords"); if (exj != null) for (int i = 0; i < exj.length(); i++) { String v = exj.optString(i, ""); if (!v.isEmpty()) pls.put(v); }
                boolean has = pls.length() > 0;
                c.put("password", ""); c.put("passwords", pls); c.remove("aps"); c.put("native", true);
                boolean hasMt = !c.optString("mt_pass").isEmpty(); c.put("mt_pass", "");
                JSONObject o = new JSONObject();
                o.put("config", c); o.put("has_password", has); o.put("has_mt_password", hasMt); o.put("ap_count", cfg.optJSONArray("aps").length()); o.put("urls", new JSONArray());
                return o.toString();
            }
        }
        if (!busy.compareAndSet(false, true)) return err("فيه عملية شغالة دلوقتي، استنى لما تخلص");
        KeepAlive.start(ctx);   // يكمّل في الخلفية لو خرجت من البرنامج
        try {
            switch (path) {
                case "discover": { List<String> f = discover(ranges); return new JSONObject().put("found", f.size()).toString(); }
                case "scan": return scan(ranges, b.optBoolean("rediscover")).toString();
                case "kick": return new JSONObject().put("results", kick(b.optJSONArray("items"))).toString();
                case "client_diag": return clientDiag(b.optString("q")).toString();
                case "mt_fix": return mtFix(b.optString("mac"), b.optJSONArray("ips")).toString();
                case "install": return new JSONObject().put("results", install(ranges, b.optBoolean("remove"))).toString();
            }
            return err("not found");
        } finally {
            busy.set(false); pText = ""; pDone.set(0); pTotal.set(0);
            KeepAlive.stop(ctx);
        }
    }

    private void saveConfig(JSONObject n) throws Exception {
        JSONArray r = n.optJSONArray("ranges");
        if (r != null) {
            JSONArray out = new JSONArray();
            for (int i = 0; i < r.length(); i++) { String s = r.optString(i).trim(); if (RANGE.matcher(s).matches()) out.put(s); }
            cfg.put("ranges", out);
        }
        if (n.has("user")) cfg.put("user", n.optString("user").trim());
        if (n.has("multi")) { cfg.put("multi", n.optBoolean("multi")); ipPass.clear(); }
        if (n.has("passwords")) {
            JSONArray pin = n.optJSONArray("passwords");
            JSONArray pout = new JSONArray();
            if (pin != null) for (int i = 0; i < pin.length(); i++) {
                String v = pin.optString(i, "").trim();
                if (!v.isEmpty()) pout.put(v);
            }
            cfg.put("passwords", pout);
            cfg.put("password", pout.length() > 0 ? pout.getString(0) : "");
            ipPass.clear();
        }
        for (String k : new String[]{"from", "to", "port", "mt_port"}) if (n.optInt(k) > 0) cfg.put(k, n.optInt(k));
        if (n.has("mt_on")) cfg.put("mt_on", n.optBoolean("mt_on"));
        if (n.has("mt_ip")) cfg.put("mt_ip", n.optString("mt_ip").trim());
        if (n.has("mt_user")) cfg.put("mt_user", n.optString("mt_user").trim());
        if (!n.optString("mt_pass").isEmpty()) cfg.put("mt_pass", n.optString("mt_pass"));
        if (n.optBoolean("mt_clear")) cfg.put("mt_pass", "");
        save();
    }

    private List<String> cfgRanges() {
        List<String> l = new ArrayList<>();
        JSONArray a = cfg.optJSONArray("ranges");
        for (int i = 0; a != null && i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    // ---------------- helpers ----------------
    interface Job<T, R> { R run(T t) throws Exception; }

    private <T, R> List<R> pool(List<T> items, int n, Job<T, R> fn) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, Math.min(n, items.size())));
        List<Future<R>> fs = new ArrayList<>();
        for (final T it : items) fs.add(ex.submit(() -> { try { return fn.run(it); } finally { pDone.incrementAndGet(); } }));
        List<R> out = new ArrayList<>();
        for (Future<R> f : fs) { try { out.add(f.get()); } catch (Exception e) { out.add(null); } }
        ex.shutdown();
        ex.awaitTermination(5, TimeUnit.SECONDS);
        return out;
    }

    private void progress(String t, int total) { pText = t; pDone.set(0); pTotal.set(total); KeepAlive.text(ctx, t); }

    static String rangeOf(String ip) { return ip.substring(0, ip.lastIndexOf('.')); }

    static int ipCmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 4; i++) { int d = Integer.parseInt(x[i]) - Integer.parseInt(y[i]); if (d != 0) return d; }
        return 0;
    }

    static String arErr(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("Auth fail") || m.contains("Auth cancel")) return "اسم المستخدم أو الباسورد غلط";
        if (m.contains("cmdtimeout")) return "دخلت على الأكسس بس الأمر ماخلصش في الوقت";
        if (m.contains("socket is not established")) return "بورت 22 مش بيرد";
        if (m.contains("Algorithm negotiation")) return "الـ SSH بتاع الأكسس قديم ومش متوافق (" + m + ")";
        if (m.contains("timeout") || m.contains("timed out")) return "الأكسس مارجّعش رد SSH في الوقت";
        if (m.contains("refused")) return "الـ SSH مقفول على الأكسس";
        if (m.contains("unreachable") || m.contains("No route")) return "مش قادر أوصل للأكسس";
        return m;
    }

    static class Res { String ip, out = "", err = "", raw = ""; boolean ok; long ms; }

    private Res ssh(String ip, String cmd, String stdin, int timeout) {
        Res r = new Res(); r.ip = ip;
        long t0 = System.currentTimeMillis();
        try { return sshInner(r, ip, cmd, stdin, timeout); } finally { r.ms = System.currentTimeMillis() - t0; }
    }

    private Res sshInner(Res r, String ip, String cmd, String stdin, int timeout) {
        // رتّب الباسوردات: اللي نجح قبل كده على الأكسس ده الأول
        List<String> pl = passList();
        String good = ipPass.get(ip);
        if (good != null) { pl.remove(good); pl.add(0, good); }
        Throwable last = null;
        for (int pi = 0; pi < pl.size(); pi++) {
            Res rr = sshOne(r, ip, cmd, stdin, timeout, pl.get(pi));
            if (rr.ok) { ipPass.put(ip, pl.get(pi)); return rr; }
            // غلط في الباسورد بس؟ جرّب اللي بعده. أي مشكلة تانية (الأكسس مش بيرد) وقّف
            String raw = String.valueOf(rr.raw);
            boolean authFail = raw.contains("Auth fail") || raw.contains("Auth cancel") || raw.contains("USERAUTH");
            if (!authFail) return rr;
            last = null;
        }
        r.ok = false; r.err = "اسم المستخدم أو الباسورد غلط (جربت " + pl.size() + " باسورد)"; r.raw = "Auth fail";
        return r;
    }

    private Res sshOne(Res r, String ip, String cmd, String stdin, int timeout, String pass) {
        Session s = null; ChannelExec ch = null;
        try {
            JSch j = new JSch();
            s = j.getSession(cfg.optString("user", "root"), ip, cfg.optInt("port", 22));
            if (!pass.isEmpty()) s.setPassword(pass);
            s.setUserInfo(new UI(pass));
            s.setTimeout(timeout);
            s.connect(timeout);
            ch = (ChannelExec) s.openChannel("exec");
            ch.setCommand(cmd);
            InputStream in = ch.getInputStream();
            InputStream er = ch.getErrStream();
            OutputStream os = ch.getOutputStream();
            ch.connect(timeout);
            if (stdin != null) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            os.flush(); os.close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long dead = System.currentTimeMillis() + timeout;
            while (true) {
                while (in.available() > 0) { int n = in.read(buf); if (n < 0) break; bo.write(buf, 0, n); }
                while (er.available() > 0) { int n = er.read(buf); if (n < 0) break; be.write(buf, 0, n); }
                if (ch.isClosed() && in.available() == 0) break;
                if (System.currentTimeMillis() > dead) throw new Exception("cmdtimeout");
                Thread.sleep(40);
            }
            r.out = bo.toString("UTF-8"); r.err = be.toString("UTF-8"); r.ok = true;
        } catch (Throwable e) {
            r.ok = false; r.err = arErr(e); r.raw = String.valueOf(e);
        } finally {
            try { if (ch != null) ch.disconnect(); } catch (Exception e) { }
            try { if (s != null) s.disconnect(); } catch (Exception e) { }
        }
        return r;
    }

    static class UI implements UserInfo, UIKeyboardInteractive {
        final String p; UI(String p) { this.p = p; }
        public String getPassphrase() { return null; }
        public String getPassword() { return p; }
        public boolean promptPassword(String m) { return true; }
        public boolean promptPassphrase(String m) { return false; }
        public boolean promptYesNo(String m) { return true; }
        public void showMessage(String m) { }
        public String[] promptKeyboardInteractive(String d, String n, String i, String[] prompt, boolean[] echo) {
            String[] a = new String[prompt.length]; Arrays.fill(a, p); return a;
        }
    }

    // ---------------- discover ----------------
    private List<String> ipsOf(List<String> ranges) {
        int a = Math.max(1, cfg.optInt("from", 2)), z = Math.min(254, cfg.optInt("to", 254));
        List<String> out = new ArrayList<>();
        for (String r : ranges) for (int i = a; i <= z; i++) out.add(r + "." + i);
        return out;
    }

    private List<String> probeAll(List<String> ips, int port, int threads, int timeout) throws Exception {
        List<Boolean> res = pool(ips, threads, ip -> {
            try (Socket so = new Socket()) { so.connect(new InetSocketAddress(ip, port), timeout); return true; }
            catch (Exception e) { return false; }
        });
        List<String> found = new ArrayList<>();
        for (int i = 0; i < ips.size(); i++) if (Boolean.TRUE.equals(res.get(i))) found.add(ips.get(i));
        return found;
    }

    private List<String> discover(List<String> ranges) throws Exception {
        final List<String> ips = ipsOf(ranges);
        final int port = cfg.optInt("port", 22);
        // البحث الأول: كل العناوين
        progress("البحث الأول على الرينج", ips.size());
        List<String> found = probeAll(ips, port, 120, 1200);
        // البحث التاني: العناوين اللي مردتش بس، بوقت انتظار أطول
        Set<String> got = new HashSet<>(found);
        List<String> rest = new ArrayList<>();
        for (String ip : ips) if (!got.contains(ip)) rest.add(ip);
        if (!rest.isEmpty()) {
            progress("البحث التاني (لقيت " + found.size() + " لحد دلوقتي)", rest.size());
            found.addAll(probeAll(rest, port, 120, 2500));
        }
        Collections.sort(found, Engine::ipCmp);
        Set<String> rs = new HashSet<>(ranges);
        List<String> all = new ArrayList<>();
        JSONArray old = cfg.optJSONArray("aps");
        for (int i = 0; old != null && i < old.length(); i++) if (!rs.contains(rangeOf(old.optString(i)))) all.add(old.optString(i));
        all.addAll(found);
        Collections.sort(all, Engine::ipCmp);
        cfg.put("aps", new JSONArray(all));
        save();
        return found;
    }

    // ---------------- scan ----------------
    static final String SCAN_CMD =
        "echo \"H $(uci -q get system.@system[0].hostname || cat /proc/sys/kernel/hostname)\"\n" +
        "[ -x /usr/sbin/msaeed-ghost ] && echo \"G $(pgrep -f /usr/sbin/msaeed-ghost >/dev/null 2>&1 && echo 1 || echo 0) $(grep -o 'GHOST_VER=[0-9]*' /usr/sbin/msaeed-ghost | cut -d= -f2)\"\n" +
        "echo \"U $(cut -d. -f1 /proc/uptime) $(cut -d' ' -f2 /proc/loadavg) $(awk '/MemAvailable/{a=$2}/MemTotal/{t=$2}END{print a+0,t+0}' /proc/meminfo) $(ubus list 'hostapd.*' 2>/dev/null | wc -l) $(dmesg 2>/dev/null | grep -ciE 'mcu.*(timeout|time ?out)|tx hang|firmware (crash|error)|wdt.*reset|message .* timeout') $(iw dev 2>/dev/null | grep -c 'type AP')\"\n" +
        "for i in $(iw dev 2>/dev/null | awk '$1==\"Interface\"{print $2}'); do\n" +
        "  if [ -e /sys/class/net/$i/brport/port_no ]; then\n" +
        "    echo \"I $i $(basename $(readlink /sys/class/net/$i/brport/bridge)) $(($(cat /sys/class/net/$i/brport/port_no)))\"\n" +
        "  fi\n" +
        "  iw dev $i station dump 2>/dev/null | awk -v i=$i '/^Station/{m=$2;a=\"\";s=\"\"} /inactive time:/{a=$3} /^[ \\t]*signal:/{s=$2} /connected time:/{print \"S\",i,m,a,s,$3}'\n" +
        "done\n" +
        "for b in $(ls /sys/class/net/); do\n" +
        "  [ -r /sys/class/net/$b/brforward ] && hexdump -v -e '16/1 \"%02x \" \"\\n\"' /sys/class/net/$b/brforward 2>/dev/null | awk -v b=$b '$8==\"00\"{print \"F\",b,$1\":\"$2\":\"$3\":\"$4\":\"$5\":\"$6,$13$7}'\n" +
        "done\n" +
        "true\n";

    static class Sta { String iface, mac, signal, ip, host; int inactive, connected; boolean wired; }
    static class Iface { String br; int port; }
    static class AP { String ip, host = "", err = ""; boolean ok, installed, running; int gver; long up; double load; int memA, memT, hapd, derr, wifi = -1; Map<String, Iface> ifaces = new LinkedHashMap<>(); List<Sta> st = new ArrayList<>(); Map<String, Integer> fdb = new HashMap<>(); }

    static AP parse(Res r) {
        AP ap = new AP(); ap.ip = r.ip; ap.ok = r.ok; ap.err = r.err;
        if (!r.ok) return ap;
        for (String line : r.out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 0) continue;
            try {
                switch (p[0]) {
                    case "H": ap.host = line.trim().substring(1).trim(); break;
                    case "G": ap.installed = true; ap.running = p.length < 2 || !"0".equals(p[1]); ap.gver = p.length >= 3 ? parseInt(p[2]) : 1; break;
                    case "U": if (p.length >= 8) { ap.up = Long.parseLong(p[1]); ap.load = Double.parseDouble(p[2]); ap.memA = parseInt(p[3]); ap.memT = parseInt(p[4]); ap.hapd = parseInt(p[5]); ap.derr = parseInt(p[6]); ap.wifi = parseInt(p[7]); } break;
                    case "I": if (p.length >= 4) { Iface f = new Iface(); f.br = p[2]; f.port = Integer.parseInt(p[3]); ap.ifaces.put(p[1], f); } break;
                    case "S": if (p.length >= 6) { Sta s = new Sta(); s.iface = p[1]; s.mac = p[2].toLowerCase(); s.inactive = parseInt(p[3]); s.signal = p[4]; s.connected = parseInt(p[5]); ap.st.add(s); } break;
                    case "F": if (p.length >= 4) ap.fdb.put(p[1] + " " + p[2], Integer.parseInt(p[3], 16)); break;
                }
            } catch (Exception e) { }
        }
        for (Sta s : ap.st) {
            s.ip = ap.ip; s.host = ap.host;
            Iface f = ap.ifaces.get(s.iface);
            if (f == null) continue;
            Integer port = ap.fdb.get(f.br + " " + s.mac);
            if (port == null) continue;
            boolean wifi = false;
            for (Iface x : ap.ifaces.values()) if (x.br.equals(f.br) && x.port == port) wifi = true;
            s.wired = !wifi;
        }
        return ap;
    }

    static int parseInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }

    static JSONObject staJson(Sta s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("iface", s.iface); o.put("mac", s.mac); o.put("inactive", s.inactive); o.put("signal", s.signal);
        o.put("connected", s.connected); o.put("wired", s.wired); o.put("ip", s.ip); o.put("host", s.host);
        return o;
    }

    private JSONObject scan(List<String> ranges, boolean rediscover) throws Exception {
        // الأكسسات اللي اتلقت قبل كده بتتفحص برضه حتى لو مردتش في البحث
        Set<String> rs = new HashSet<>(ranges);
        Set<String> prev = new HashSet<>();
        JSONArray aps = cfg.optJSONArray("aps");
        for (int i = 0; aps != null && i < aps.length(); i++) if (rs.contains(rangeOf(aps.optString(i)))) prev.add(aps.optString(i));
        Set<String> tset = new HashSet<>(discover(ranges));
        tset.addAll(prev);
        List<String> targets = new ArrayList<>(tset);
        Collections.sort(targets, Engine::ipCmp);
        if (!prev.isEmpty()) { // رجّع القديم للكاش
            JSONArray now = cfg.optJSONArray("aps"); Set<String> all = new HashSet<>();
            for (int i = 0; i < now.length(); i++) all.add(now.optString(i));
            all.addAll(prev); List<String> l = new ArrayList<>(all); Collections.sort(l, Engine::ipCmp);
            cfg.put("aps", new JSONArray(l)); save();
        }
        progress("بفحص الأكسسات", targets.size());
        List<Res> res = pool(targets, 40, ip -> ssh(ip, SCAN_CMD, null, 12000));
        // إعادة محاولة للأكسسات اللي مردتش: عدد أقل في نفس الوقت ووقت أطول
        List<Integer> retry = new ArrayList<>();
        for (int i = 0; i < res.size(); i++) {
            Res x = res.get(i);
            if (x == null || (!x.ok && !x.err.contains("الباسورد"))) retry.add(i);
        }
        if (!retry.isEmpty()) {
            progress("بعيد المحاولة مع الأكسسات اللي مردتش", retry.size());
            final List<String> tg = targets;
            List<Res> again = pool(retry, 15, i -> ssh(tg.get(i), SCAN_CMD, null, 20000));
            for (int k = 0; k < retry.size(); k++) if (again.get(k) != null) res.set(retry.get(k), again.get(k));
        }
        List<AP> ok = new ArrayList<>();
        JSONArray failed = new JSONArray(), apList = new JSONArray();
        int installed = 0;
        for (int i = 0; i < targets.size(); i++) {
            Res r = res.get(i);
            if (r == null) { r = new Res(); r.ip = targets.get(i); r.err = "error"; }
            AP ap = parse(r);
            if (!ap.ok) { failed.put(new JSONObject().put("ip", ap.ip).put("err", ap.err).put("raw", r.raw)); continue; }
            ok.add(ap);
            if (ap.installed) installed++;
            apList.put(new JSONObject().put("ip", ap.ip).put("host", ap.host).put("n", ap.st.size()).put("installed", ap.installed).put("running", ap.running).put("gver", ap.gver).put("up", ap.up).put("load", ap.load).put("memA", ap.memA).put("memT", ap.memT).put("hapd", ap.hapd).put("derr", ap.derr).put("wifi", ap.wifi));
        }
        Map<String, List<Sta>> byMac = new LinkedHashMap<>();
        for (AP ap : ok) for (Sta s : ap.st) byMac.computeIfAbsent(s.mac, k -> new ArrayList<>()).add(s);
        JSONArray ghosts = new JSONArray(), dups = new JSONArray(), all = new JSONArray();
        for (Map.Entry<String, List<Sta>> e : byMac.entrySet()) {
            List<Sta> l = e.getValue();
            for (Sta s : l) all.put(staJson(s));
            if (l.size() >= 2) {
                Sta best = l.get(0);
                for (Sta s : l) if (s.inactive < best.inactive) best = s;
                for (Sta s : l) {
                    if (s == best) continue;
                    JSONObject o = staJson(s).put("real", best.ip).put("realHost", best.host);
                    if (s.inactive >= MIN_INACTIVE && s.inactive > best.inactive + 5000) ghosts.put(o.put("reason", "two"));
                    else dups.put(o);
                }
            } else {
                Sta s = l.get(0);
                if (s.wired && s.inactive >= MIN_INACTIVE && s.connected >= MIN_CONNECTED)
                    ghosts.put(staJson(s).put("real", "").put("reason", "wired"));
            }
        }
        JSONObject o = new JSONObject();
        if (cfg.optBoolean("mt_on") && !cfg.optString("mt_ip", "").isEmpty()) {
            progress("بجيب IP العملاء من المايكروتك", 1);
            try {
                Map<String, String[]> cm = clientMap();
                addClient(ghosts, cm); addClient(dups, cm); addClient(all, cm);
            } catch (Exception e) { o.put("mtErr", e.getMessage()); }
        }
        o.put("scanned", targets.size()); o.put("okCount", ok.size()); o.put("failed", failed);
        o.put("installed", installed); o.put("clients", byMac.size()); o.put("ghosts", ghosts);
        o.put("dups", dups); o.put("all", all); o.put("aps", apList);
        return o;
    }

    // ---------------- IP العملاء من المايكروتك ----------------
    private Ros mtOpen() throws Exception {
        String ip = cfg.optString("mt_ip", "");
        if (ip.isEmpty()) throw new Exception("اكتب IP المايكروتك في الإعدادات");
        return new Ros(ip, cfg.optInt("mt_port", 8728), cfg.optString("mt_user", "admin"), cfg.optString("mt_pass", ""));
    }

    // mac (lowercase) -> [ip, user, name]
    private Map<String, String[]> clientMap() throws Exception {
        Map<String, String[]> m = new HashMap<>();
        try (Ros r = mtOpen()) {
            for (Map<String, String> x : r.run("/ip/dhcp-server/lease/print", "=.proplist=mac-address,address,host-name")) {
                String mac = String.valueOf(x.get("mac-address")).toLowerCase();
                m.put(mac, new String[]{x.getOrDefault("address", ""), "", x.getOrDefault("host-name", "")});
            }
            try {
                for (Map<String, String> x : r.run("/ip/hotspot/host/print", "=.proplist=mac-address,address")) {
                    String mac = String.valueOf(x.get("mac-address")).toLowerCase();
                    String[] v = m.computeIfAbsent(mac, k -> new String[]{"", "", ""});
                    if (v[0].isEmpty()) v[0] = x.getOrDefault("address", "");
                }
                for (Map<String, String> x : r.run("/ip/hotspot/active/print", "=.proplist=mac-address,address,user")) {
                    String mac = String.valueOf(x.get("mac-address")).toLowerCase();
                    String[] v = m.computeIfAbsent(mac, k -> new String[]{"", "", ""});
                    v[0] = x.getOrDefault("address", v[0]); v[1] = x.getOrDefault("user", "");
                }
            } catch (Exception e) { } // مفيش هوتسبوت
        }
        return m;
    }

    // ---------------- تشخيص عميل ----------------
    static JSONArray rows(List<Map<String, String>> l) { JSONArray a = new JSONArray(); for (Map<String, String> m : l) a.put(new JSONObject(m)); return a; }

    private List<Map<String, String>> q(Ros r, String menu, String filter) {
        try { return r.run(menu + "/print", "?" + filter); } catch (Exception e) { return new ArrayList<>(); }
    }

    private JSONObject clientDiag(String query) throws Exception {
        query = query.trim();
        JSONObject o = new JSONObject().put("q", query);
        String mac = null;
        String qm = query.toLowerCase().replaceAll("[^0-9a-f]", "");
        if (qm.length() == 12 && query.matches("(?i)^[0-9a-f:\\-\\. ]+$")) mac = qm.replaceAll("(..)(?!$)", "$1:");
        boolean mtOn = cfg.optBoolean("mt_on") && !cfg.optString("mt_ip", "").isEmpty();
        JSONObject mt = new JSONObject();
        o.put("mtOn", mtOn);
        if (mtOn) {
            progress("بسأل المايكروتك عن العميل", 1);
            try (Ros r = mtOpen()) {
                if (mac == null) { // دوّر بالـ IP أو اسم الكارت
                    List<Map<String, String>> hit = new ArrayList<>();
                    if (IP.matcher(query).matches()) {
                        for (String m : new String[]{"/ip/hotspot/active", "/ip/hotspot/host", "/ip/dhcp-server/lease", "/ip/arp"}) { hit = q(r, m, "address=" + query); if (!hit.isEmpty()) break; }
                    } else {
                        hit = q(r, "/ip/hotspot/active", "user=" + query);
                        if (hit.isEmpty()) hit = q(r, "/ip/hotspot/cookie", "user=" + query);
                    }
                    for (Map<String, String> h : hit) if (h.get("mac-address") != null) { mac = h.get("mac-address").toLowerCase(); break; }
                }
                if (mac != null) {
                    String M = mac.toUpperCase();
                    mt.put("leases", rows(q(r, "/ip/dhcp-server/lease", "mac-address=" + M)));
                    mt.put("hosts", rows(q(r, "/ip/hotspot/host", "mac-address=" + M)));
                    mt.put("active", rows(q(r, "/ip/hotspot/active", "mac-address=" + M)));
                    mt.put("cookies", rows(q(r, "/ip/hotspot/cookie", "mac-address=" + M)));
                    mt.put("arp", rows(q(r, "/ip/arp", "mac-address=" + M)));
                    Set<String> ips = new HashSet<>();
                    for (String k : new String[]{"leases", "hosts", "active", "arp"}) { JSONArray a = mt.getJSONArray(k); for (int i = 0; i < a.length(); i++) { String ad = a.getJSONObject(i).optString("address"); if (!ad.isEmpty()) ips.add(ad); } }
                    JSONArray conf = new JSONArray();
                    for (String ip : ips) for (String m : new String[]{"/ip/dhcp-server/lease", "/ip/arp"})
                        for (Map<String, String> x : q(r, m, "address=" + ip)) if (!M.equalsIgnoreCase(x.getOrDefault("mac-address", M))) { x.put("_src", m.contains("lease") ? "DHCP" : "ARP"); conf.put(new JSONObject(x)); }
                    mt.put("conflicts", conf);
                }
                mt.put("ok", true);
            } catch (Exception e) { mt.put("ok", false).put("err", e.getMessage()); }
        }
        o.put("mt", mt);
        if (mac == null) {
            o.put("error", mtOn ? "مالقيتش العميل ده على المايكروتك. اكتب الماك بتاعه." : "اكتب الماك بتاع العميل، أو شغّل «المايكروتك» من الإعدادات عشان أدوّر بالـ IP أو اسم الكارت.");
            return o;
        }
        o.put("mac", mac);
        // دوّر عليه في كل الأكسسات اللي البرنامج يعرفها
        List<String> targets = new ArrayList<>();
        JSONArray aps = cfg.optJSONArray("aps");
        for (int i = 0; aps != null && i < aps.length(); i++) targets.add(aps.optString(i));
        o.put("apsKnown", targets.size());
        final String fm = mac;
        final String cmd = "echo \"H $(uci -q get system.@system[0].hostname || cat /proc/sys/kernel/hostname)\"\n" +
            "for i in $(iw dev 2>/dev/null | awk '$1==\"Interface\"{print $2}'); do iw dev $i station get " + fm + " 2>/dev/null | awk -v i=$i '/inactive time:/{a=$3} /^[ \\t]*signal:/{s=$2} /connected time:/{c=$3} END{if(a!=\"\")print \"S\",i,a,s,c}'; done\ntrue";
        progress("بدوّر على العميل في الأكسسات", targets.size());
        List<Res> res = pool(targets, 40, ip -> ssh(ip, cmd, null, 12000));
        JSONArray found = new JSONArray();
        int fail = 0;
        for (Res r : res) {
            if (r == null || !r.ok) { fail++; continue; }
            String host = "";
            for (String line : r.out.split("\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length > 0 && "H".equals(p[0])) host = line.trim().substring(1).trim();
                if (p.length >= 5 && "S".equals(p[0]))
                    found.put(new JSONObject().put("ip", r.ip).put("host", host).put("iface", p[1]).put("inactive", parseInt(p[2])).put("signal", p[3]).put("connected", parseInt(p[4])));
            }
        }
        o.put("aps", found).put("apsFailed", fail);
        return o;
    }

    private JSONObject mtFix(String mac, JSONArray ipsA) throws Exception {
        JSONArray log = new JSONArray();
        if (mac == null || !MAC.matcher(mac).matches()) return new JSONObject().put("error", "ماك غلط");
        String M = mac.toUpperCase();
        progress("بنضّف العميل من المايكروتك", 1);
        try (Ros r = mtOpen()) {
            String[][] menus = {{"/ip/hotspot/active", "خرّجته من الهوتسبوت (Active)", "0"}, {"/ip/hotspot/host", "مسحت الـ Host", "0"},
                                {"/ip/hotspot/cookie", "مسحت الكوكي", "0"}, {"/ip/dhcp-server/lease", "مسحت الـ DHCP Lease", "1"}, {"/ip/arp", "مسحت الـ ARP", "1"}};
            for (String[] m : menus) {
                for (Map<String, String> x : q(r, m[0], "mac-address=" + M)) {
                    if ("1".equals(m[2]) && !"true".equals(x.get("dynamic"))) { log.put("• سبت " + m[0].substring(4) + " الثابت " + x.getOrDefault("address", "") + " زي ما هو"); continue; }
                    try { r.run(m[0] + "/remove", "=.id=" + x.get(".id")); log.put("✓ " + m[1] + " " + x.getOrDefault("address", x.getOrDefault("user", ""))); }
                    catch (Exception e) { log.put("✗ " + m[1] + ": " + e.getMessage()); }
                }
            }
        }
        if (log.length() == 0) log.put("مفيش حاجة كانت محتاجة تتمسح على المايكروتك.");
        return new JSONObject().put("log", log);
    }

    static void addClient(JSONArray a, Map<String, String[]> m) throws Exception {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String[] v = m.get(o.optString("mac"));
            if (v != null) { o.put("cip", v[0]); o.put("cuser", v[1]); o.put("cname", v[2]); }
        }
    }

    // ---------------- اختبار أكسس خطوة بخطوة ----------------
    private JSONObject diag(String ip) throws Exception {
        JSONArray st = new JSONArray();
        JSONObject o = new JSONObject().put("ip", ip).put("steps", st);
        if (!IP.matcher(ip).matches()) { st.put(step(false, "IP غلط")); return o; }
        int port = cfg.optInt("port", 22);
        long t0 = System.currentTimeMillis();
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress(ip, port), 4000);
            st.put(step(true, "بورت " + port + " مفتوح (" + (System.currentTimeMillis() - t0) + " ms)"));
            so.setSoTimeout(8000);
            InputStream in = so.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            long t1 = System.currentTimeMillis();
            try { int c; while (b.size() < 200 && (c = in.read()) >= 0 && c != '\n') b.write(c); } catch (Exception e) { }
            String banner = b.toString("UTF-8").trim();
            long bt = System.currentTimeMillis() - t1;
            if (banner.startsWith("SSH-")) st.put(step(true, "رد بـ " + banner + " (" + bt + " ms)"));
            else if (banner.isEmpty()) { st.put(step(false, "البورت مفتوح بس مارجّعش أي رد SSH خلال 8 ثواني. غالباً الجهاز مش أوبن ورت، أو الـ SSH بتاعه واقف.")); return o; }
            else { st.put(step(false, "الجهاز رد بحاجة مش SSH: " + banner)); return o; }
        } catch (Exception e) {
            st.put(step(false, "بورت " + port + " مش بيرد (" + e.getMessage() + ")")); return o;
        }
        Res r = ssh(ip, "echo LOGIN_OK; cat /etc/openwrt_release 2>/dev/null | grep DISTRIB_DESCRIPTION; uname -m", null, 40000);
        if (!r.ok) { st.put(step(false, r.err + " (" + (r.ms / 1000.0) + " ثانية)")); st.put(step(false, "التفاصيل: " + r.raw)); return o; }
        st.put(step(true, "دخلت ونفذت أمر في " + (r.ms / 1000.0) + " ثانية"));
        String info = r.out.replace("LOGIN_OK", "").trim().replace("\n", " — "); if (!info.isEmpty()) st.put(step(true, info));
        Res sc = ssh(ip, SCAN_CMD, null, 40000);
        if (sc.ok) st.put(step(true, "أمر الفحص خلص في " + (sc.ms / 1000.0) + " ثانية"));
        else st.put(step(false, "أمر الفحص: " + sc.err + " (" + (sc.ms / 1000.0) + " ثانية)"));
        return o;
    }

    static JSONObject step(boolean ok, String t) throws Exception { return new JSONObject().put("ok", ok).put("text", t); }

    // ---------------- kick ----------------
    private JSONArray kick(JSONArray items) throws Exception {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            if (x != null && MAC.matcher(x.optString("mac")).matches() && IFACE.matcher(x.optString("iface")).matches() && IP.matcher(x.optString("ip")).matches()) l.add(x);
        }
        progress("بفصل العملاء المعلقين", l.size());
        List<JSONObject> res = pool(l, 10, x -> {
            String ifc = x.getString("iface"), mac = x.getString("mac");
            String cmd = "ubus call hostapd." + ifc + " del_client '{\"addr\":\"" + mac + "\",\"reason\":3,\"deauth\":true,\"ban_time\":0}' >/dev/null 2>&1 || iw dev " + ifc + " station del " + mac + " && echo KICKED";
            Res r = ssh(x.getString("ip"), cmd, null, 12000);
            return new JSONObject().put("ip", x.getString("ip")).put("mac", mac).put("ok", r.ok && r.out.contains("KICKED")).put("err", r.err);
        });
        JSONArray a = new JSONArray();
        for (JSONObject o : res) if (o != null) a.put(o);
        return a;
    }

    // ---------------- install ----------------
    static final String UNINSTALL =
        "/etc/init.d/msaeed-ghost stop 2>/dev/null; /etc/init.d/msaeed-ghost disable 2>/dev/null\n" +
        "rm -f /usr/sbin/msaeed-ghost /etc/init.d/msaeed-ghost\n" +
        "sed -i '/msaeed-ghost/d' /etc/sysupgrade.conf 2>/dev/null\n" +
        "echo \"msaeed-ghost removed OK\"\n";

    private String installer() throws Exception {
        try (InputStream in = ctx.getAssets().open("msaeed-ghost-install.sh")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private JSONArray install(List<String> ranges, boolean remove) throws Exception {
        final String script = remove ? UNINSTALL : installer();
        List<String> targets = discover(ranges);
        progress(remove ? "بشيل السكريبت" : "بركّب السكريبت", targets.size());
        List<JSONObject> res = pool(targets, 20, ip -> {
            Res r = ssh(ip, "sh -s", script, 20000);
            boolean ok = r.ok && r.out.contains("OK");
            String e = ok ? "" : (r.err.isEmpty() ? r.out : r.err);
            if (e.length() > 200) e = e.substring(0, 200);
            return new JSONObject().put("ip", ip).put("ok", ok).put("err", e);
        });
        JSONArray a = new JSONArray();
        for (JSONObject o : res) if (o != null) a.put(o);
        return a;
    }
}
