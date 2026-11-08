package com.marko.slimtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Keeps downloads running while the app is in the background and shows their progress. */
public class DownloadService extends Service {
    private static final String CHANNEL = "downloads";
    private static final int NOTIF_ID = 2;
    private static final int DONE_ID = 3;
    private static boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, DownloadService.class);
            if (running) ctx.startService(i);
            else ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.w(App.TAG, "could not start download service", e);
        }
    }

    static void refresh(Context ctx) {
        if (running) start(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Preuzimanja", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        update();
        return START_NOT_STICKY;
    }

    private void update() {
        handler.removeCallbacks(tick);
        if (!Downloads.anyActive()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            Downloads.Job last = Downloads.jobs.isEmpty() ? null : Downloads.jobs.get(0);
            if (last != null && last.state == Downloads.DONE) {
                getSystemService(NotificationManager.class).notify(DONE_ID, base()
                        .setContentTitle("Preuzeto")
                        .setContentText(last.title)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setAutoCancel(true)
                        .build());
            }
            stopSelf();
            return;
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, build());
        handler.postDelayed(tick, 1000);
    }

    private Notification.Builder base() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setContentIntent(open).setShowWhen(false);
    }

    private Notification build() {
        Downloads.Job j = Downloads.running();
        Notification.Builder b = base()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(j != null ? j.title : "Preuzimanje")
                .setContentText(j != null ? j.status : "Na čekanju");
        if (j != null) {
            if (j.progress >= 0) b.setProgress(100, Math.round(j.progress), false);
            else b.setProgress(0, 0, true);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
