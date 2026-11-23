package com.msaeed.shop;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.AuthenticationFailedException;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

public class BackupWorker extends Worker {
    static final String PERIODIC = "gmail-backup";

    public BackupWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("mail", Context.MODE_PRIVATE); }

    static Constraints net() { return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(); }

    static void schedule(Context c) {
        SharedPreferences p = prefs(c);
        WorkManager wm = WorkManager.getInstance(c);
        boolean ok = p.getBoolean("enabled", false) && !p.getString("email", "").isEmpty() && !p.getString("pass", "").isEmpty();
        if (!ok) { wm.cancelUniqueWork(PERIODIC); return; }
        int every = Math.max(1, p.getInt("every", 2));
        PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(BackupWorker.class, every, TimeUnit.DAYS)
                .setConstraints(net()).build();
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, r);
    }

    static void sendNow(Context c) {
        WorkManager.getInstance(c).enqueue(new OneTimeWorkRequest.Builder(BackupWorker.class).setConstraints(net()).build());
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences p = prefs(c);
        final String email = p.getString("email", "");
        final String pass = p.getString("pass", "");
        File data = new File(c.getFilesDir(), "backup.json");
        if (email.isEmpty() || pass.isEmpty()) return Result.failure();
        if (!data.exists()) { p.edit().putString("err", "مفيش بيانات لسه للنسخ").apply(); return Result.failure(); }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        try {
            Properties pr = new Properties();
            pr.put("mail.smtp.host", "smtp.gmail.com");
            pr.put("mail.smtp.port", "465");
            pr.put("mail.smtp.auth", "true");
            pr.put("mail.smtp.ssl.enable", "true");
            pr.put("mail.smtp.connectiontimeout", "30000");
            pr.put("mail.smtp.timeout", "60000");
            Session s = Session.getInstance(pr, new javax.mail.Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(email, pass); }
            });
            MimeMessage m = new MimeMessage(s);
            m.setFrom(new InternetAddress(email));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(email));
            m.setSubject("نسخة احتياطية - محل الإخوة - " + day, "UTF-8");
            MimeBodyPart body = new MimeBodyPart();
            body.setText("نسخة احتياطية أوتوماتيك من برنامج محل الإخوة.\nلو حبيت ترجّعها: افتح البرنامج > الإعدادات > استرجاع نسخة > واختار الملف المرفق.\n\nبرمجة M saeed - 01123840671", "UTF-8");
            MimeBodyPart att = new MimeBodyPart();
            att.setDataHandler(new DataHandler(new FileDataSource(data)));
            att.setFileName("elekhwa-backup-" + day + ".json");
            Multipart mp = new MimeMultipart();
            mp.addBodyPart(body);
            mp.addBodyPart(att);
            m.setContent(mp);
            Transport.send(m);
            p.edit().putLong("last", System.currentTimeMillis()).putString("err", "").apply();
            return Result.success();
        } catch (AuthenticationFailedException e) {
            p.edit().putString("err", "الجيميل رفض الدخول: اتأكد من الإيميل وكلمة سر التطبيقات").apply();
            return Result.failure();
        } catch (Exception e) {
            p.edit().putString("err", "النسخة متبعتتش (هتتحاول تاني): " + e.getClass().getSimpleName()).apply();
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }
}
