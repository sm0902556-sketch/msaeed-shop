package com.msaeed.shop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "اختار ملف النسخة"), 42); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("file:")) return false;
                openExternal(u);
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        ReminderReceiver.schedule(this);
        BackupWorker.schedule(this);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 42 && fileCb != null) {
            Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
            fileCb.onReceiveValue(u != null ? new Uri[]{u} : null);
            fileCb = null;
        }
    }

    private void openExternal(String u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, "واتساب مش متسطب على الموبايل", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface
        public void openUrl(final String u) { runOnUiThread(() -> openExternal(u)); }

        @JavascriptInterface
        public void setDue(String json, int hour) {
            getSharedPreferences("shop", MODE_PRIVATE).edit().putString("due", json).putInt("hour", hour).apply();
            ReminderReceiver.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public void saveData(String json) {
            try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "backup.json"))) {
                o.write(json.getBytes("UTF-8"));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setMail(String email, String pass, boolean enabled, int every) {
            SharedPreferences.Editor e = BackupWorker.prefs(MainActivity.this).edit()
                    .putString("email", email.trim()).putBoolean("enabled", enabled).putInt("every", every).putString("err", "");
            if (pass != null && !pass.isEmpty()) e.putString("pass", pass);
            e.apply();
            BackupWorker.schedule(MainActivity.this);
        }

        @JavascriptInterface
        public String mailStatus() {
            SharedPreferences p = BackupWorker.prefs(MainActivity.this);
            try {
                return new JSONObject().put("email", p.getString("email", "")).put("enabled", p.getBoolean("enabled", false))
                        .put("hasPass", !p.getString("pass", "").isEmpty()).put("every", p.getInt("every", 2))
                        .put("last", p.getLong("last", 0)).put("err", p.getString("err", "")).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void sendNow() { BackupWorker.sendNow(MainActivity.this); }

        @JavascriptInterface
        public void share(final String title, final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, title);
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, title));
            });
        }
    }
}
