package com.marko.slimtube;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates in two taps, without an updater app: when a newer release is on GitHub, a small bar
 * shows up for a few seconds while the app is open. "Ažuriraj" downloads the APK with Android's
 * own download manager; Android's installer then asks "Update?" when the file is opened.
 *
 * The app never installs anything itself (no install permission): Play Protect treats
 * self-installing apps as "droppers". Nothing runs in the background either: GitHub is asked
 * at most every few hours, and only while the app is on screen.
 */
final class UpdateBanner {
    private static final String REPO = "alertpay91/MarkTube";
    private static final String APP = "MarkTube";
    private static final String TAG = "MarkTube";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final long SNOOZE_MS = 24L * 60 * 60 * 1000;
    private static final long SHOW_FOR_MS = 15000;
    private static final String PREFS = "update_banner";

    private static volatile boolean busy;
    private static long downloadId = -1;

    private UpdateBanner() { }

    /** Call from the activity's onStart. */
    static void check(Activity a, FrameLayout root) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("lastCheck", 0) < CHECK_EVERY_MS) {
            maybeShow(a, root);
            return;
        }
        if (busy) return;
        busy = true;
        Context app = a.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get(API));
                // Tags are "v1.<versionCode>".
                int code = Integer.parseInt(rel.getString("tag_name")
                        .replaceFirst("^v?1\\.", "").replaceAll("\\D", ""));
                String apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name").endsWith(".apk")) apk = as.optString("browser_download_url");
                }
                SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong("lastCheck", System.currentTimeMillis());
                if (apk != null) {
                    e.putInt("latestCode", code).putString("latestName", rel.optString("tag_name").replaceFirst("^v", ""))
                            .putString("apk", apk);
                }
                e.apply();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!a.isFinishing() && !a.isDestroyed()) maybeShow(a, root);
                });
            } catch (Exception ex) {
                Log.w(TAG, "update check failed: " + ex);
            } finally {
                busy = false;
            }
        }, "update-check").start();
    }

    static int installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void maybeShow(Activity a, FrameLayout root) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int code = p.getInt("latestCode", -1);
        String apk = p.getString("apk", null);
        if (apk == null || code <= installedVersion(a)) return;
        if (p.getInt("snoozedCode", -1) == code && System.currentTimeMillis() < p.getLong("snoozedUntil", 0)) return;
        if (root.findViewWithTag(PREFS) != null) return;
        show(a, root, p.getString("latestName", ""), apk, code);
    }

    private static void show(Activity a, FrameLayout root, String name, String apk, int code) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(a);
        bar.setTag(PREFS);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(d, 16), px(d, 10), px(d, 8), px(d, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0303030);
        bg.setCornerRadius(px(d, 14));
        bar.setBackground(bg);
        bar.setElevation(px(d, 8));
        bar.setClickable(true); // taps on the bar don't fall through to the page

        TextView text = label(a, "Nova verzija " + APP + " " + name, 14, 0xFFF1F1F1);
        bar.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView later = label(a, "Kasnije", 14, 0xFFAAAAAA);
        later.setPadding(px(d, 12), px(d, 8), px(d, 12), px(d, 8));
        bar.addView(later);
        TextView update = label(a, "Ažuriraj", 14, 0xFF3EA6FF);
        update.setTypeface(Typeface.DEFAULT_BOLD);
        update.setPadding(px(d, 12), px(d, 8), px(d, 8), px(d, 8));
        bar.addView(update);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        // Above the bottom tab bars of the sites and the system bar.
        lp.setMargins(px(d, 12), 0, px(d, 12), px(d, 84));
        root.addView(bar, lp);
        bar.setAlpha(0f);
        bar.animate().alpha(1f).setDuration(200).start();

        Handler h = new Handler(Looper.getMainLooper());
        Runnable hide = () -> remove(bar);
        h.postDelayed(hide, SHOW_FOR_MS);

        later.setOnClickListener(v -> {
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("snoozedCode", code)
                    .putLong("snoozedUntil", System.currentTimeMillis() + SNOOZE_MS).apply();
            h.removeCallbacks(hide);
            remove(bar);
        });
        update.setOnClickListener(v -> {
            h.removeCallbacks(hide);
            if (!download(a, apk, name)) {
                remove(bar);
                return;
            }
            text.setText("Preuzimam… Kad završi, otvori fajl i dodirni Ažuriraj.");
            later.setVisibility(View.GONE);
            update.setText("Preuzimanja");
            update.setOnClickListener(w -> {
                openDownloads(a);
                remove(bar);
            });
            listenForDone(a, bar, text);
        });
    }

    private static boolean download(Activity a, String apk, String name) {
        try {
            DownloadManager dm = a.getSystemService(DownloadManager.class);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apk))
                    .setTitle(APP + " " + name)
                    .setDescription("Dodirni da instaliraš novu verziju")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APP + "-" + name + ".apk");
            downloadId = dm.enqueue(r);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "download failed", e);
            // Fall back to the browser.
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(apk)));
            } catch (Exception ignored) {
                Toast.makeText(a, "Preuzimanje nije uspelo", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
    }

    private static void listenForDone(Activity a, LinearLayout bar, TextView text) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != downloadId) return;
                try { a.unregisterReceiver(this); } catch (Exception ignored) { }
                if (bar.isAttachedToWindow()) {
                    text.setText("Preuzeto. Otvori " + APP + " fajl i dodirni Ažuriraj.");
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else a.registerReceiver(r, f);
    }

    private static void openDownloads(Activity a) {
        try {
            a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(a, "Otvori aplikaciju Fajlovi → Preuzimanja", Toast.LENGTH_LONG).show();
        }
    }

    private static void remove(View bar) {
        if (bar.getParent() == null) return;
        bar.animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (bar.getParent() instanceof FrameLayout) ((FrameLayout) bar.getParent()).removeView(bar);
        }).start();
    }

    private static TextView label(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private static int px(float density, int dp) {
        return Math.round(dp * density);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", APP + "-updater");
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
