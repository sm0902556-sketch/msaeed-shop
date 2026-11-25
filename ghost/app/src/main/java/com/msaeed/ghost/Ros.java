package com.msaeed.ghost;

// RouterOS API client (port 8728) - المبرمج محمد سعيد

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Ros implements AutoCloseable {
    private final Socket s;
    private final InputStream in;
    private final OutputStream out;
    private Map<String, String> done = new HashMap<>();

    public Ros(String host, int port, String user, String pass) throws Exception {
        s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
        } catch (Exception e) {
            throw new Exception("مش قادر أوصل للمايكروتك على " + host + ":" + port + " — اتأكد إن خدمة api شغالة من IP > Services");
        }
        s.setSoTimeout(20000);
        in = s.getInputStream();
        out = s.getOutputStream();
        try {
            run("/login", "=name=" + user, "=password=" + pass);
            String chal = done.get("ret");
            if (chal != null) { // RouterOS أقدم من 6.43
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update((byte) 0);
                md.update(pass.getBytes(StandardCharsets.UTF_8));
                md.update(hex(chal));
                run("/login", "=name=" + user, "=response=00" + toHex(md.digest()));
            }
        } catch (Exception e) {
            close();
            throw new Exception("يوزر أو باسورد المايكروتك غلط");
        }
    }

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private void writeLen(ByteArrayOutputStream o, int l) {
        if (l < 0x80) o.write(l);
        else if (l < 0x4000) { l |= 0x8000; o.write(l >> 8); o.write(l & 0xFF); }
        else if (l < 0x200000) { l |= 0xC00000; o.write(l >> 16); o.write((l >> 8) & 0xFF); o.write(l & 0xFF); }
        else if (l < 0x10000000) { l |= 0xE0000000; o.write(l >>> 24); o.write((l >> 16) & 0xFF); o.write((l >> 8) & 0xFF); o.write(l & 0xFF); }
        else { o.write(0xF0); o.write(l >>> 24); o.write((l >> 16) & 0xFF); o.write((l >> 8) & 0xFF); o.write(l & 0xFF); }
    }

    private int rb() throws Exception { int c = in.read(); if (c < 0) throw new Exception("المايكروتك قفل الاتصال"); return c; }

    private int readLen() throws Exception {
        int b = rb();
        if ((b & 0x80) == 0) return b;
        if ((b & 0xC0) == 0x80) return ((b & ~0xC0) << 8) | rb();
        if ((b & 0xE0) == 0xC0) return ((b & ~0xE0) << 16) | (rb() << 8) | rb();
        if ((b & 0xF0) == 0xE0) return ((b & ~0xF0) << 24) | (rb() << 16) | (rb() << 8) | rb();
        return (rb() << 24) | (rb() << 16) | (rb() << 8) | rb();
    }

    private List<String> sentence() throws Exception {
        List<String> w = new ArrayList<>();
        while (true) {
            int l = readLen();
            if (l == 0) return w;
            byte[] b = new byte[l];
            int got = 0;
            while (got < l) { int n = in.read(b, got, l - got); if (n < 0) throw new Exception("المايكروتك قفل الاتصال"); got += n; }
            w.add(new String(b, StandardCharsets.UTF_8));
        }
    }

    static Map<String, String> attrs(List<String> w) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < w.size(); i++) {
            String x = w.get(i);
            if (!x.startsWith("=")) continue;
            int k = x.indexOf('=', 1);
            if (k < 0) m.put(x.substring(1), ""); else m.put(x.substring(1, k), x.substring(k + 1));
        }
        return m;
    }

    public List<Map<String, String>> run(String... words) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String w : words) { byte[] b = w.getBytes(StandardCharsets.UTF_8); writeLen(o, b.length); o.write(b, 0, b.length); }
        o.write(0);
        out.write(o.toByteArray());
        out.flush();
        List<Map<String, String>> rows = new ArrayList<>();
        String trap = null;
        while (true) {
            List<String> w = sentence();
            if (w.isEmpty()) continue;
            String t = w.get(0);
            if ("!re".equals(t)) rows.add(attrs(w));
            else if ("!trap".equals(t)) trap = attrs(w).get("message");
            else if ("!fatal".equals(t)) throw new Exception("المايكروتك: " + String.join(" ", w));
            else if ("!done".equals(t)) { done = attrs(w); if (trap != null) throw new Exception(trap); return rows; }
        }
    }

    @Override
    public void close() { try { s.close(); } catch (Exception e) { } }
}
