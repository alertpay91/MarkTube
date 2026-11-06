package com.marko.slimtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update from the GitHub releases of the public MarkTube repository.
 * New builds are downloaded in the background (Wi-Fi / unmetered only) and installed while the
 * app is in the background and nothing is playing, so the user never has to do anything.
 * Android asks once ("allow installs from MarkTube"); after that updates are silent.
 */
final class Updater {
    static final String REPO = "alertpay91/MarkTube";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final String PREFS = "updater";
    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 2;

    private static volatile boolean busy;
    private static volatile File ready;
    private static volatile int readyCode;

    private Updater() { }

    static int installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Checks for a new release (at most every few hours) and downloads it in the background. */
    static void check(Context context) {
        final Context ctx = context.getApplicationContext();
        if (busy) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (ready == null && now - prefs.getLong("lastUpToDate", 0) < CHECK_EVERY_MS) return;
        busy = true;
        new Thread(() -> {
            try {
                cleanup(ctx);
                if (ready != null) return;
                JSONObject rel = new JSONObject(get(API));
                int code = Integer.parseInt(rel.getString("tag_name").replaceAll("\\D", ""));
                if (code <= installedVersion(ctx) || code == prefs.getInt("failedCode", -1)) {
                    // Only remembered when nothing is pending, so an interrupted download
                    // is retried at the next start instead of hours later.
                    prefs.edit().putLong("lastUpToDate", System.currentTimeMillis()).apply();
                    return;
                }
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        download(ctx, a.getString("browser_download_url"), a.getLong("size"), code);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(App.TAG, "update check failed: " + e);
            } finally {
                busy = false;
            }
        }, "updater").start();
    }

    private static void download(Context ctx, String url, long size, int code) throws Exception {
        File out = new File(ctx.getCacheDir(), "update-" + code + ".apk");
        if (!(out.exists() && out.length() == size)) {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm == null || cm.isActiveNetworkMetered()) {
                Log.i(App.TAG, "update " + code + " available, waiting for Wi-Fi");
                return;
            }
            Log.i(App.TAG, "downloading update " + code);
            File tmp = new File(ctx.getCacheDir(), "update.part");
            HttpURLConnection c = open(url);
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (tmp.length() != size || !tmp.renameTo(out)) {
                tmp.delete();
                throw new Exception("incomplete download");
            }
        }
        // Must be our own app, and really the advertised version.
        PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
        if (pi == null || !ctx.getPackageName().equals(pi.packageName)
                || (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode) != code) {
            out.delete();
            throw new Exception("downloaded file is not a MarkTube update");
        }
        readyCode = code;
        ready = out;
        Log.i(App.TAG, "update " + code + " ready");
        if (!App.visible) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> installIfIdle(ctx));
        }
    }

    /** Installs a downloaded update, but only while nothing is playing (the app restarts). */
    static void installIfIdle(Context context) {
        final Context ctx = context.getApplicationContext();
        File f = ready;
        if (f == null || App.playing || busy) return;
        ready = null;
        busy = true;
        new Thread(() -> {
            try {
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(ctx.getPackageName());
                params.setSize(f.length());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream o = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        s.fsync(o);
                    }
                    Intent result = new Intent(ctx, Result.class).putExtra("code", readyCode);
                    PendingIntent pending = PendingIntent.getBroadcast(ctx, id, result,
                            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    Log.i(App.TAG, "installing update " + readyCode);
                    s.commit(pending.getIntentSender());
                }
            } catch (Exception e) {
                Log.w(App.TAG, "update install failed: " + e);
            } finally {
                busy = false;
            }
        }, "updater-install").start();
    }

    private static void cleanup(Context ctx) {
        int installed = installedVersion(ctx);
        File[] files = ctx.getCacheDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("update-") && n.endsWith(".apk")) {
                try {
                    int code = Integer.parseInt(n.replaceAll("\\D", ""));
                    if (code <= installed) f.delete();
                } catch (NumberFormatException e) {
                    f.delete();
                }
            }
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "MarkTube-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
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

    /** Result of the install. The very first time Android asks the user once; we show a notification for it. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            int code = intent.getIntExtra("code", -1);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ažuriranja",
                        NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent open = PendingIntent.getActivity(ctx, 0, confirm,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                nm.notify(NOTIF_ID, new Notification.Builder(ctx, CHANNEL)
                        .setSmallIcon(R.drawable.ic_notif)
                        .setContentTitle("Nova verzija MarkTube-a je spremna")
                        .setContentText("Dodirni da instaliraš. Sledeća ažuriranja idu sama.")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                Log.i(App.TAG, "update installed");
            } else {
                Log.w(App.TAG, "update failed: " + status + " "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // Don't retry the same broken build forever; a newer release will be tried.
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("failedCode", code).apply();
                }
                File f = new File(ctx.getCacheDir(), "update-" + code + ".apk");
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) f.delete();
                else { ready = f; readyCode = code; }
            }
        }
    }
}
