package com.msaeed.ghost;

// صياد الشبح - المبرمج محمد سعيد - 01123840671

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView web;
    private Engine engine;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new Engine(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF0F0D08);
        web.setWebChromeClient(new WebChromeClient()); // confirm() dialogs
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("https://appassets.androidplatform.net/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    public class Bridge {
        @JavascriptInterface
        public String appVersion() { PackageInfo p = pinfo(); return p == null ? "" : p.versionName; }

        @JavascriptInterface
        public void call(final String id, final String path, final String body) {
            pool.submit(() -> {
                String r;
                try {
                    if ("upd_check".equals(path)) r = checkUpdate();
                    else if ("upd_go".equals(path)) r = doUpdate();
                    else r = engine.handle(path, body);
                }
                catch (Throwable t) { r = Engine.err(String.valueOf(t.getMessage())); }
                final String js = "window.__cb(" + JSONObject.quote(id) + "," + JSONObject.quote(r) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            });
        }
    }

    // ---------------- التحديث ----------------
    static final String UPD = "https://github.com/sm0902556-sketch/msaeed-shop/releases/download/ghost-latest/";

    private PackageInfo pinfo() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0); } catch (Exception e) { return null; }
    }

    private HttpURLConnection open(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code / 100 == 3) { String loc = c.getHeaderField("Location"); c.disconnect(); return open(loc); }
        if (code != 200) throw new Exception("HTTP " + code);
        return c;
    }

    private String checkUpdate() {
        try {
            HttpURLConnection c = open(UPD + "version.json?t=" + System.currentTimeMillis());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            JSONObject v = new JSONObject(b.toString("UTF-8"));
            PackageInfo p = pinfo();
            int cur = p == null ? 0 : p.versionCode;
            v.put("current", cur);
            v.put("currentName", p == null ? "" : p.versionName);
            v.put("newer", v.optInt("code") > cur);
            return v.toString();
        } catch (Exception e) {
            return Engine.err("مفيش نت أو مقدرتش أوصل لسيرفر التحديث");
        }
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private String doUpdate() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Exception e) { }
                });
                return "{\"need_permission\":true}";
            }
            File f = new File(getCacheDir(), "update.apk");
            HttpURLConnection c = open(UPD + "MsaeedGhostHunter.apk?t=" + System.currentTimeMillis());
            long total = c.getContentLengthLong(), got = 0; int last = -1;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); got += n;
                    if (total > 0) { int pct = (int) (got * 100 / total); if (pct != last) { last = pct; js("window.__updProg&&window.__updProg(" + pct + ")"); } }
                }
            }
            final Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(u, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) { }
            });
            return "{\"ok\":true}";
        } catch (Exception e) {
            return Engine.err("التحميل فشل: " + e.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
