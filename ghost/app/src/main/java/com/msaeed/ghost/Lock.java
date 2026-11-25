package com.msaeed.ghost;

// المستخدمين (زي برنامج الإخوة) - المبرمج محمد سعيد - 01123840671
// المدير ثابت "M saeed" وبيضيف عملاء باسم وباسورد. العميل بيستخدم البرنامج كله،
// والمدير بس اللي يقدر يضيف أو يوقف أو يمسح أو يغيّر باسورد حد.
// الباسوردات بتتحفظ بصمة (PBKDF2 + ملح عشوائي) مش الباسورد نفسه.

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.Iterator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Lock {
    static final String OWNER = "M saeed";
    static final int ITER = 120000;
    private static volatile String who = null;          // الداخل دلوقتي (بيتمسح لما البرنامج يتقفل)
    private static int fails = 0;
    private static long blockedUntil = 0;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("users", Context.MODE_PRIVATE); }

    private static JSONObject users(Context c) {
        try { return new JSONObject(p(c).getString("users", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static void saveUsers(Context c, JSONObject u) { p(c).edit().putString("users", u.toString()).apply(); }

    static boolean isSet(Context c) { return users(c).has(OWNER); }

    static boolean isOpen(Context c) {
        if (who == null) return false;
        JSONObject r = users(c).optJSONObject(who);
        return r != null && !r.optBoolean("off");
    }

    static boolean isOwner(Context c) { return isOpen(c) && OWNER.equals(who); }

    static String state(Context c) { return !isSet(c) ? "setup" : isOpen(c) ? "open" : "locked"; }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static byte[] unhex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return b; }

    static String alg() {
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); return "PBKDF2WithHmacSHA256"; }
        catch (Exception e) { return "PBKDF2WithHmacSHA1"; }
    }

    static String hash(String pw, byte[] salt, String alg) throws Exception {
        return hex(SecretKeyFactory.getInstance(alg).generateSecret(new PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).getEncoded());
    }

    static boolean same(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0; for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    static JSONObject record(String pw, boolean off) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        String a = alg();
        return new JSONObject().put("salt", hex(salt)).put("alg", a).put("hash", hash(pw, salt, a)).put("off", off);
    }

    static boolean check(JSONObject r, String pw) throws Exception {
        if (r == null) return false;
        return same(hash(pw == null ? "" : pw, unhex(r.getString("salt")), r.optString("alg", "PBKDF2WithHmacSHA256")), r.getString("hash"));
    }

    static String badPw(String pw) { return pw == null || pw.length() < 4 ? "الباسورد لازم يبقى 4 حروف أو أرقام على الأقل" : null; }

    // أول تشغيل: باسورد المدير M saeed
    static synchronized String setup(Context c, String pw) throws Exception {
        if (isSet(c)) return "حساب المدير متعمل بالفعل";
        String b = badPw(pw); if (b != null) return b;
        JSONObject u = users(c); u.put(OWNER, record(pw, false)); saveUsers(c, u);
        who = OWNER;
        return "";
    }

    static synchronized String login(Context c, String user, String pw) throws Exception {
        long now = System.currentTimeMillis();
        if (now < blockedUntil) return "محاولات كتير غلط، استنى " + ((blockedUntil - now) / 1000 + 1) + " ثانية";
        String name = user == null ? "" : user.trim();
        JSONObject u = users(c);
        JSONObject r = u.optJSONObject(name);
        if (r == null || !check(r, pw)) {
            Thread.sleep(800);
            if (++fails >= 5) { fails = 0; blockedUntil = now + 60000; return "محاولات كتير غلط، البرنامج اتقفل دقيقة"; }
            return "اسم المستخدم أو الباسورد غلط";
        }
        fails = 0;
        if (r.optBoolean("off")) return "off";
        who = name;
        return "";
    }

    static String whoami(Context c) throws Exception {
        return new JSONObject().put("name", isOpen(c) ? who : "").put("owner", isOwner(c)).toString();
    }

    static String list(Context c) throws Exception {
        JSONArray a = new JSONArray();
        if (!isOwner(c)) return a.toString();
        JSONObject u = users(c);
        a.put(new JSONObject().put("name", OWNER).put("owner", true).put("off", false));
        for (Iterator<String> it = u.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!OWNER.equals(k)) a.put(new JSONObject().put("name", k).put("owner", false).put("off", u.optJSONObject(k).optBoolean("off")));
        }
        return a.toString();
    }

    static synchronized String addUser(Context c, String name, String pw) throws Exception {
        if (!isOwner(c)) return "المدير بس اللي يقدر يضيف عملاء";
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 30) return "اكتب اسم العميل";
        JSONObject u = users(c);
        if (u.has(name)) return "الاسم ده موجود بالفعل";
        String b = badPw(pw); if (b != null) return b;
        u.put(name, record(pw, false)); saveUsers(c, u);
        return "";
    }

    static synchronized String setOff(Context c, String name, boolean off) throws Exception {
        if (!isOwner(c)) return "المدير بس اللي يقدر يوقف عملاء";
        if (OWNER.equals(name)) return "مينفعش توقف حساب المدير";
        JSONObject u = users(c); JSONObject r = u.optJSONObject(name);
        if (r == null) return "العميل ده مش موجود";
        r.put("off", off); u.put(name, r); saveUsers(c, u);
        return "";
    }

    static synchronized String delUser(Context c, String name) throws Exception {
        if (!isOwner(c)) return "المدير بس اللي يقدر يمسح عملاء";
        if (OWNER.equals(name)) return "مينفعش تمسح حساب المدير";
        JSONObject u = users(c); u.remove(name); saveUsers(c, u);
        return "";
    }

    // المدير يغيّر باسورد أي حد؛ العميل يغيّر بتاعه بس بالباسورد القديم
    static synchronized String setPassword(Context c, String name, String oldPw, String newPw) throws Exception {
        if (!isOpen(c)) return "سجّل دخول الأول";
        JSONObject u = users(c); JSONObject r = u.optJSONObject(name);
        if (r == null) return "المستخدم ده مش موجود";
        boolean self = name.equals(who);
        if (!self && !isOwner(c)) return "مينفعش تغيّر باسورد حد تاني";
        if (self && !check(r, oldPw)) { Thread.sleep(800); return "الباسورد الحالي غلط"; }
        String b = badPw(newPw); if (b != null) return b;
        u.put(name, record(newPw, r.optBoolean("off"))); saveUsers(c, u);
        return "";
    }

    static void logout() { who = null; }
}
