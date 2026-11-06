package com.marko.slimtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tells the user when a newer MarkTube release is on GitHub (one notification per version;
 * tapping it opens the release page). The app deliberately does not download or install APKs
 * itself: Play Protect treats self-installing apps as "droppers". For fully automatic updates,
 * use an updater app such as Obtainium pointed at the GitHub repository.
 */
final class Updater {
    static final String REPO = "alertpay91/MarkTube";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final String PREFS = "updater";
    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 2;

    private static volatile boolean busy;

    private Updater() { }

    static int installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** At most every few hours: if GitHub has a newer release, show one notification for it. */
    static void check(Context context) {
        final Context ctx = context.getApplicationContext();
        if (busy) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - prefs.getLong("lastNotifyCheck", 0) < CHECK_EVERY_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(API));
                prefs.edit().putLong("lastNotifyCheck", System.currentTimeMillis()).apply();
                // Tags are "v1.<versionCode>" (older ones were "v<versionCode>").
                int code = Integer.parseInt(rel.getString("tag_name")
                        .replaceFirst("^v?1\\.", "").replaceAll("\\D", ""));
                if (code <= installedVersion(ctx) || code == prefs.getInt("notifiedCode", -1)) return;
                prefs.edit().putInt("notifiedCode", code).apply();
                notifyUpdate(ctx, rel.optString("name", "MarkTube"),
                        rel.optString("html_url", "https://github.com/" + REPO + "/releases/latest"));
            } catch (Exception e) {
                Log.w(App.TAG, "update check failed: " + e);
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    private static void notifyUpdate(Context ctx, String name, String url) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ažuriranja",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Nova verzija: " + name)
                .setContentText("Dodirni da preuzmeš novu verziju.")
                .setContentIntent(PendingIntent.getActivity(ctx, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build());
        Log.i(App.TAG, "notified about " + name);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "MarkTube-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
