package com.msaeed.ghost;

// بيخلي الفحص والإصلاح يكمّلوا في الخلفية لو خرجت من البرنامج
// المبرمج محمد سعيد - 01123840671

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class KeepAlive extends Service {
    static final String CH = "work";
    static final int ID = 7;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private static volatile boolean running;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAlive.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, KeepAlive.class)); } catch (Exception e) { }
    }

    static void text(Context c, String t) {
        if (!running) return;
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(ID, build(c, t)); } catch (Exception e) { }
    }

    static Notification build(Context c, String t) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(new NotificationChannel(CH, "شغل في الخلفية", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("فحص عميل معلق شغال")
                .setContentText(t == null || t.isEmpty() ? "بيكمّل في الخلفية..." : t)
                .setOngoing(true).setContentIntent(pi).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(this, "بيكمّل في الخلفية...");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        running = true;
        try {
            if (wake == null) {
                wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "msaeed:work");
                wake.acquire(15 * 60 * 1000L);
            }
            if (wifi == null) {
                wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "msaeed:wifi");
                wifi.acquire();
            }
        } catch (Exception e) { }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
