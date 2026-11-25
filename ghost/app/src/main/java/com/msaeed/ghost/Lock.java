package com.msaeed.ghost;

// قفل البرنامج: اسم المستخدم ثابت "M saeed"، والباسورد بيتحفظ بصمة (PBKDF2 + ملح عشوائي) مش الباسورد نفسه
// المبرمج محمد سعيد - 01123840671

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class Lock {
    static final String USER = "M saeed";
    static final int ITER = 120000;
    private static volatile boolean open = false;      // بيتقفل تاني كل ما البرنامج يتقفل
    private static int fails = 0;
    private static long blockedUntil = 0;

    private static SharedPreferences p(Context c) { return c.getSharedPreferences("lock", Context.MODE_PRIVATE); }

    static boolean isSet(Context c) { return !p(c).getString("hash", "").isEmpty(); }

    static boolean isOpen(Context c) { return open && isSet(c); }

    static String state(Context c) { return !isSet(c) ? "setup" : open ? "open" : "locked"; }

    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }

    static byte[] unhex(String h) { byte[] b = new byte[h.length() / 2]; for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); return b; }

    static String hash(String pw, byte[] salt, String alg) throws Exception {
        return hex(SecretKeyFactory.getInstance(alg).generateSecret(new PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).getEncoded());
    }

    static String alg() {
        try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); return "PBKDF2WithHmacSHA256"; }
        catch (Exception e) { return "PBKDF2WithHmacSHA1"; }
    }

    static boolean same(String a, String b) { // مقارنة بوقت ثابت
        if (a.length() != b.length()) return false;
        int r = 0; for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    // أول مرة البرنامج يتفتح: انت بتعمل الباسورد
    static synchronized String setup(Context c, String pw) throws Exception {
        if (isSet(c)) return "الباسورد متعمل بالفعل";
        return save(c, pw);
    }

    private static String save(Context c, String pw) throws Exception {
        if (pw == null || pw.length() < 4) return "الباسورد لازم يبقى 4 حروف أو أرقام على الأقل";
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        String a = alg();
        p(c).edit().putString("salt", hex(salt)).putString("alg", a).putString("hash", hash(pw, salt, a)).apply();
        open = true;
        return "";
    }

    static synchronized boolean check(Context c, String pw) throws Exception {
        SharedPreferences s = p(c);
        return same(hash(pw == null ? "" : pw, unhex(s.getString("salt", "")), s.getString("alg", "PBKDF2WithHmacSHA256")), s.getString("hash", ""));
    }

    // بيرجع "" لو الدخول نجح، أو رسالة الخطأ
    static synchronized String login(Context c, String user, String pw) throws Exception {
        long now = System.currentTimeMillis();
        if (now < blockedUntil) return "محاولات كتير غلط، استنى " + ((blockedUntil - now) / 1000 + 1) + " ثانية";
        if (!USER.equals(user == null ? "" : user.trim()) || !check(c, pw)) {
            Thread.sleep(800);
            if (++fails >= 5) { fails = 0; blockedUntil = now + 60000; return "محاولات كتير غلط، البرنامج اتقفل دقيقة"; }
            return "اسم المستخدم أو الباسورد غلط";
        }
        fails = 0; open = true;
        return "";
    }

    static synchronized String change(Context c, String oldPw, String newPw) throws Exception {
        if (!isOpen(c)) return "سجّل دخول الأول";
        if (!check(c, oldPw)) { Thread.sleep(800); return "الباسورد الحالي غلط"; }
        p(c).edit().remove("hash").apply();
        return save(c, newPw);
    }

    static void logout() { open = false; }
}
