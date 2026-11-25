package com.msaeed.ghost;

// إيقاف البرنامج عند عميل من بعيد (لو مادفعش) - المبرمج محمد سعيد - 01123840671
//
// كل نسخة ليها "رقم جهاز". البرنامج بيقرا ملف ghost-control.json من GitHub،
// ولو رقم الجهاز في قايمة blocked البرنامج بيتقفل. لو ماقدرش يشيّك أكتر من
// GRACE_DAYS يوم بيتقفل لحد ما يتوصل بالنت. الإيقاف بيفضل محفوظ حتى من غير نت.

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

public class License {
    static final String URL_CTL = "https://raw.githubusercontent.com/sm0902556-sketch/msaeed-shop/ghost-hunter/ghost-control.json";
    static final long GRACE = 10L * 24 * 3600 * 1000;
    static final String DEF_MSG = "البرنامج متوقف. كلّم المبرمج محمد سعيد على واتساب 01123840671";

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("lic", Context.MODE_PRIVATE); }

    static synchronized String id(Context c) {
        String id = p(c).getString("id", "");
        if (id.isEmpty()) {
            String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder s = new StringBuilder("MS-");
            for (int i = 0; i < 6; i++) s.append(abc.charAt(r.nextInt(abc.length())));
            id = s.toString();
            // جهاز جديد: مقفول لحد أول تحقق ناجح
            p(c).edit().putString("id", id).putLong("lastOk", System.currentTimeMillis()).putBoolean("checked", false).apply();
        }
        return id;
    }

    // ok | blocked | pending | offline
    static String state(Context c) {
        id(c);
        SharedPreferences s = p(c);
        if (s.getBoolean("blocked", false)) return "blocked";
        if (!s.getBoolean("checked", true)) return "pending";                       // جهاز جديد ماتحققش لسه
        if (s.getBoolean("allowlist", false) && !s.getBoolean("approved", false)) return "pending";  // مستني تفعيل
        if (System.currentTimeMillis() - s.getLong("lastOk", 0) > GRACE) return "offline";
        return "ok";
    }

    static boolean ok(Context c) { return "ok".equals(state(c)); }

    static String info(Context c) {
        try {
            SharedPreferences s = p(c);
            return new JSONObject().put("state", state(c)).put("id", id(c)).put("msg", s.getString("msg", DEF_MSG)).toString();
        } catch (Exception e) { return "{}"; }
    }

    static boolean has(JSONArray a, String me) {
        for (int i = 0; a != null && i < a.length(); i++) if (me.equalsIgnoreCase(a.optString(i).trim())) return true;
        return false;
    }

    // بيشيّك على الملف؛ لو مفيش نت بيسيب الحالة زي ما هي
    static String check(Context c) {
        String me = id(c);
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(URL_CTL + "?t=" + System.currentTimeMillis()).openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(15000); h.setRequestProperty("Cache-Control", "no-cache");
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            boolean blocked = has(j.optJSONArray("blocked"), me);
            boolean allowlist = j.optBoolean("allowlist", false);
            boolean approved = has(j.optJSONArray("allowed"), me);
            p(c).edit().putBoolean("blocked", blocked).putBoolean("allowlist", allowlist).putBoolean("approved", approved)
                    .putBoolean("checked", true).putLong("lastOk", System.currentTimeMillis())
                    .putString("msg", j.optString("message", DEF_MSG)).apply();
        } catch (Exception e) { }
        return info(c);
    }
}
