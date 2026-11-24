package com.msaeed.shop;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ReminderReceiver extends BroadcastReceiver {
    static final String CH = "due";

    static void schedule(Context c) {
        int hour = c.getSharedPreferences("shop", Context.MODE_PRIVATE).getInt("hour", 10);
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, hour);
        t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_MONTH, 1);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, ReminderReceiver.class), fl);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setInexactRepeating(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int count = 0;
        long sum = 0;
        StringBuilder names = new StringBuilder();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("shop", Context.MODE_PRIVATE).getString("due", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optString("date").compareTo(today) <= 0) {
                    count++;
                    sum += o.optLong("amount");
                    if (names.length() < 120) names.append(o.optString("name")).append("، ");
                }
            }
        } catch (Exception ignored) { }
        if (count == 0) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "مواعيد الأقساط", NotificationManager.IMPORTANCE_HIGH));
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class), fl);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        nb.setSmallIcon(R.mipmap.ic_launcher)
          .setContentTitle("عندك " + count + " قسط مستحق/متأخر (" + sum + " ج)")
          .setContentText(names.toString() + " — افتح البرنامج وابعت التذكير على واتساب")
          .setStyle(new Notification.BigTextStyle().bigText(names.toString() + "\nافتح البرنامج وابعت التذكير على واتساب"))
          .setContentIntent(open)
          .setAutoCancel(true);
        try { nm.notify(7, nb.build()); } catch (SecurityException ignored) { }
    }
}
