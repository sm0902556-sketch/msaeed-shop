package com.msaeed.ghost;

// صياد الشبح - المبرمج محمد سعيد - 01123840671
// نفس منطق نسخة الكمبيوتر: اكتشاف الأكسسات، فحص الشبح، الفصل، تركيب السكريبت.

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
            case "config": {
                if (b.has("config")) saveConfig(b.getJSONObject("config"));
                JSONObject c = new JSONObject(cfg.toString());
                boolean has = !c.optString("password").isEmpty();
                c.put("password", ""); c.remove("aps"); c.put("native", true);
                JSONObject o = new JSONObject();
                o.put("config", c); o.put("has_password", has); o.put("ap_count", cfg.optJSONArray("aps").length()); o.put("urls", new JSONArray());
                return o.toString();
            }
        }
        if (!busy.compareAndSet(false, true)) return err("فيه عملية شغالة دلوقتي، استنى لما تخلص");
        try {
            switch (path) {
                case "discover": { List<String> f = discover(ranges); return new JSONObject().put("found", f.size()).toString(); }
                case "scan": return scan(ranges, b.optBoolean("rediscover")).toString();
                case "kick": return new JSONObject().put("results", kick(b.optJSONArray("items"))).toString();
                case "install": return new JSONObject().put("results", install(ranges, b.optBoolean("remove"))).toString();
            }
            return err("not found");
        } finally {
            busy.set(false); pText = ""; pDone.set(0); pTotal.set(0);
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
        if (!n.optString("password").isEmpty()) cfg.put("password", n.optString("password"));
        if (n.optBoolean("clear_password")) cfg.put("password", "");
        for (String k : new String[]{"from", "to", "port"}) if (n.optInt(k) > 0) cfg.put(k, n.optInt(k));
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

    private void progress(String t, int total) { pText = t; pDone.set(0); pTotal.set(total); }

    static String rangeOf(String ip) { return ip.substring(0, ip.lastIndexOf('.')); }

    static int ipCmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 4; i++) { int d = Integer.parseInt(x[i]) - Integer.parseInt(y[i]); if (d != 0) return d; }
        return 0;
    }

    static String arErr(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("Auth fail") || m.contains("Auth cancel")) return "اسم المستخدم أو الباسورد غلط";
        if (m.contains("timeout") || m.contains("timed out")) return "الأكسس مش بيرد";
        if (m.contains("refused")) return "الـ SSH مقفول على الأكسس";
        if (m.contains("unreachable") || m.contains("No route")) return "مش قادر أوصل للأكسس";
        return m;
    }

    static class Res { String ip, out = "", err = ""; boolean ok; }

    private Res ssh(String ip, String cmd, String stdin, int timeout) {
        Res r = new Res(); r.ip = ip;
        Session s = null; ChannelExec ch = null;
        final String pass = cfg.optString("password", "");
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
                if (System.currentTimeMillis() > dead) throw new Exception("timeout");
                Thread.sleep(40);
            }
            r.out = bo.toString("UTF-8"); r.err = be.toString("UTF-8"); r.ok = true;
        } catch (Throwable e) {
            r.ok = false; r.err = arErr(e);
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
        progress("بدوّر على الأكسسات في الرينجات", ips.size());
        final int port = cfg.optInt("port", 22);
        List<String> found = probeAll(ips, port, 80, 1200);
        if (found.isEmpty()) { // الشبكة كانت بطيئة: نعيد بوقت انتظار أطول
            progress("مالقيتش أكسسات، بعيد التدوير بوقت أطول", ips.size());
            found = probeAll(ips, port, 40, 2500);
        }
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
        "[ -x /usr/sbin/msaeed-ghost ] && echo \"G 1\"\n" +
        "for i in $(iw dev 2>/dev/null | awk '$1==\"Interface\"{print $2}'); do\n" +
        "  if [ -e /sys/class/net/$i/brport/port_no ]; then\n" +
        "    echo \"I $i $(basename $(readlink /sys/class/net/$i/brport/bridge)) $(($(cat /sys/class/net/$i/brport/port_no)))\"\n" +
        "  fi\n" +
        "  iw dev $i station dump 2>/dev/null | awk -v i=$i '/^Station/{m=$2;a=\"\";s=\"\"} /inactive time:/{a=$3} /^[ \\t]*signal:/{s=$2} /connected time:/{print \"S\",i,m,a,s,$3}'\n" +
        "done\n" +
        "for b in $(ls /sys/class/net/); do\n" +
        "  [ -r /sys/class/net/$b/brforward ] && hexdump -v -e '16/1 \"%02x \" \"\\n\"' /sys/class/net/$b/brforward 2>/dev/null | awk -v b=$b '$8==\"00\"{print \"F\",b,$1\":\"$2\":\"$3\":\"$4\":\"$5\":\"$6,$13$7}'\n" +
        "done\n" +
        "true";

    static class Sta { String iface, mac, signal, ip, host; int inactive, connected; boolean wired; }
    static class Iface { String br; int port; }
    static class AP { String ip, host = "", err = ""; boolean ok, installed; Map<String, Iface> ifaces = new LinkedHashMap<>(); List<Sta> st = new ArrayList<>(); Map<String, Integer> fdb = new HashMap<>(); }

    static AP parse(Res r) {
        AP ap = new AP(); ap.ip = r.ip; ap.ok = r.ok; ap.err = r.err;
        if (!r.ok) return ap;
        for (String line : r.out.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 0) continue;
            try {
                switch (p[0]) {
                    case "H": ap.host = line.trim().substring(1).trim(); break;
                    case "G": ap.installed = true; break;
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
        List<String> targets = new ArrayList<>();
        JSONArray aps = cfg.optJSONArray("aps");
        Set<String> rs = new HashSet<>(ranges);
        for (int i = 0; aps != null && i < aps.length(); i++) if (rs.contains(rangeOf(aps.optString(i)))) targets.add(aps.optString(i));
        if (rediscover || targets.isEmpty()) targets = discover(ranges);
        progress("بفحص الأكسسات", targets.size());
        List<Res> res = pool(targets, 25, ip -> ssh(ip, SCAN_CMD, null, 12000));
        List<AP> ok = new ArrayList<>();
        JSONArray failed = new JSONArray(), apList = new JSONArray();
        int installed = 0;
        for (int i = 0; i < targets.size(); i++) {
            Res r = res.get(i);
            if (r == null) { r = new Res(); r.ip = targets.get(i); r.err = "error"; }
            AP ap = parse(r);
            if (!ap.ok) { failed.put(new JSONObject().put("ip", ap.ip).put("err", ap.err)); continue; }
            ok.add(ap);
            if (ap.installed) installed++;
            apList.put(new JSONObject().put("ip", ap.ip).put("host", ap.host).put("n", ap.st.size()).put("installed", ap.installed));
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
        o.put("scanned", targets.size()); o.put("okCount", ok.size()); o.put("failed", failed);
        o.put("installed", installed); o.put("clients", byMac.size()); o.put("ghosts", ghosts);
        o.put("dups", dups); o.put("all", all); o.put("aps", apList);
        return o;
    }

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
