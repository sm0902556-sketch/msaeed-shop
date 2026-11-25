package com.msaeed.ghost;

// فحص عميل معلق - المبرمج محمد سعيد - 01123840671
//
// التحديث زي برنامج مصنع الأمانة: الواجهة (index.html) بتتسحب من GitHub
// وتتحفظ على الفون وتشتغل على طول من غير تثبيت APK.
// لو التحديث محتاج تغيير في جزء الجافا (ENGINE)، البرنامج بيفتح لينك
// التحميل في المتصفح بدل ما يثبّت بنفسه.

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    // زوّد الرقم ده (وفي ghost/engine-version.txt) لما تغيّر في كود الجافا
    static final int ENGINE = 16;
    static final String UPD = "https://github.com/sm0902556-sketch/msaeed-shop/releases/download/ghost-latest/";

    private WebView web;
    private Engine engine;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private static volatile String bgScan = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new Engine(this);
        prefs = getSharedPreferences("upd", MODE_PRIVATE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF0F0D08);
        web.setWebChromeClient(new WebChromeClient());
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String path = r.getUrl().getPath();
                if ("/assets/index.html".equals(path) && useCache()) {
                    try {
                        return new WebResourceResponse("text/html", "utf-8", new FileInputStream(webFile()));
                    } catch (Exception e) { }
                }
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://appassets.androidplatform.net/")) return false;
                openUrl(u);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        // إذن الإشعارات (أندرويد 13+) عشان إشعار الشغل في الخلفية
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    private File webFile() { return new File(getFilesDir(), "index.html"); }

    private int apkCode() {
        try { PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0); return p.versionCode; } catch (Exception e) { return 0; }
    }

    // نستخدم النسخة المتحملة لو هي أحدث من اللي جوه الـ APK
    private boolean useCache() { return webFile().exists() && prefs.getInt("web", 0) > apkCode(); }

    private int currentCode() { return useCache() ? prefs.getInt("web", 0) : apkCode(); }

    private void openUrl(String u) {
        runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { } });
    }

    private HttpURLConnection open(String u, int depth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code / 100 == 3 && depth < 5) { String loc = c.getHeaderField("Location"); c.disconnect(); return open(loc, depth + 1); }
        if (code != 200) throw new Exception("HTTP " + code);
        return c;
    }

    private byte[] get(String u) throws Exception {
        HttpURLConnection c = open(u + (u.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis(), 0);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
        return b.toByteArray();
    }

    // auto=true: تحديث صامت عند الفتح
    private synchronized String checkUpdate(boolean auto) throws Exception {
        JSONObject v = new JSONObject(new String(get(UPD + "version.json"), StandardCharsets.UTF_8));
        int code = v.optInt("code"), eng = v.optInt("engine", 1);
        JSONObject r = new JSONObject();
        r.put("name", v.optString("name")); r.put("notes", v.optString("notes"));
        if (code <= currentCode()) return r.put("latest", true).toString();
        if (eng > ENGINE) return r.put("apk", true).put("url", UPD + "MsaeedGhostHunter.apk").toString();
        byte[] html = get(UPD + "index.html");
        String h = new String(html, StandardCharsets.UTF_8);
        if (html.length < 2000 || !(h.contains("صياد الشبح") || h.contains("فحص عميل معلق"))) throw new Exception("bad file");
        File tmp = new File(getFilesDir(), "index.html.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(html); }
        if (!tmp.renameTo(webFile())) throw new Exception("save failed");
        prefs.edit().putInt("web", code).putString("webName", v.optString("name")).putString("webNotes", v.optString("notes")).putBoolean("justUpdated", true).apply();
        runOnUiThread(() -> web.reload());
        return r.put("updated", true).toString();
    }

    public class Bridge {
        @JavascriptInterface
        public String appVersion() {
            return useCache() ? prefs.getString("webName", "") : "1." + apkCode();
        }

        // بيرجع ملاحظات التحديث مرة واحدة بعد ما البرنامج يتحدّث
        @JavascriptInterface
        public String justUpdated() {
            if (!prefs.getBoolean("justUpdated", false)) return "";
            prefs.edit().putBoolean("justUpdated", false).apply();
            try { return new JSONObject().put("name", prefs.getString("webName", "")).put("notes", prefs.getString("webNotes", "")).toString(); }
            catch (Exception e) { return ""; }
        }

        // فحص بعد الريستارت بيتعمل من الجافا عشان يحصل حتى لو البرنامج في الخلفية
        @JavascriptInterface
        public void scheduleScan(final String rangesJson, final double atMillis) {
            KeepAlive.start(MainActivity.this);
            pool.submit(() -> {
                try {
                    long wait = (long) atMillis - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    KeepAlive.text(MainActivity.this, "بعمل فحص بعد الريستارت...");
                    String r = null;
                    for (int i = 0; i < 12; i++) { // لو فيه فحص تاني شغال، استنى
                        r = engine.handle("scan", "{\"ranges\":" + rangesJson + "}");
                        if (!r.contains("فيه عملية شغالة")) break;
                        Thread.sleep(10000);
                    }
                    bgScan = r;
                    final String js = "window.__bgScan&&window.__bgScan(" + JSONObject.quote(r) + ")";
                    runOnUiThread(() -> web.evaluateJavascript(js, null));
                    String msg = "افتح البرنامج تشوف النتيجة";
                    try {
                        JSONObject j = new JSONObject(r);
                        msg = j.optInt("okCount") + " أكسس رد" + (j.optJSONArray("failed") != null && j.optJSONArray("failed").length() > 0 ? " و" + j.optJSONArray("failed").length() + " لسه مارجعوش" : "") + " — افتح البرنامج تشوف النتيجة";
                    } catch (Exception e) { }
                    KeepAlive.done(MainActivity.this, "الفحص بعد الريستارت خلص", msg);
                } catch (Exception e) {
                } finally { KeepAlive.stop(MainActivity.this); }
            });
        }

        // ---------- المستخدمين (المدير M saeed + العملاء) ----------
        @JavascriptInterface public String lockState() { return Lock.state(MainActivity.this); }
        @JavascriptInterface public String lockOwner() { return Lock.OWNER; }
        @JavascriptInterface public String lockSetup(String pw) { try { return Lock.setup(MainActivity.this, pw); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public String lockLogin(String user, String pw) { try { return Lock.login(MainActivity.this, user, pw); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public String lockWho() { try { return Lock.whoami(MainActivity.this); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public String lockUsers() { try { return Lock.list(MainActivity.this); } catch (Exception e) { return "[]"; } }
        @JavascriptInterface public String lockAdd(String n, String pw) { try { return Lock.addUser(MainActivity.this, n, pw); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public String lockOff(String n, boolean off) { try { return Lock.setOff(MainActivity.this, n, off); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public String lockDel(String n) { try { return Lock.delUser(MainActivity.this, n); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public String lockPass(String n, String o, String pw) { try { return Lock.setPassword(MainActivity.this, n, o, pw); } catch (Exception e) { return "حصلت مشكلة"; } }
        @JavascriptInterface public void lockLogout() { Lock.logout(); }

        @JavascriptInterface
        public String takeBgScan() { String r = bgScan; bgScan = null; return r == null ? "" : r; }

        @JavascriptInterface
        public void openLink(String u) { if (u != null && u.startsWith("https://")) openUrl(u); }

        @JavascriptInterface
        public void call(final String id, final String path, final String body) {
            pool.submit(() -> {
                String r;
                try {
                    if ("upd_check".equals(path)) r = checkUpdate(false);
                    else if (!Lock.isOpen(MainActivity.this)) r = Engine.err("locked");   // القفل في الجافا نفسه
                    else r = engine.handle(path, body);
                } catch (Throwable t) {
                    r = "upd_check".equals(path) ? Engine.err("مفيش نت أو مقدرتش أوصل لسيرفر التحديث") : Engine.err(String.valueOf(t.getMessage()));
                }
                final String js = "window.__cb(" + JSONObject.quote(id) + "," + JSONObject.quote(r) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }
    }

    @Override
    public void onBackPressed() {
        // زرار الرجوع بيخرّجك من غير ما يقفل البرنامج، فالفحص يكمّل
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override
    protected void onPause() { super.onPause(); web.resumeTimers(); } // العدّاد يكمّل في الخلفية
}
