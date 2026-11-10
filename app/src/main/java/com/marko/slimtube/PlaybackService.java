package com.marko.slimtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Foreground "media playback" service: keeps the process alive while the screen is off,
 * shows the media notification / lock-screen controls and forwards button presses to the page.
 */
public class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    private static final String ACTION_PLAY = "play";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_NEXT = "next";
    private static final String ACTION_PREV = "prev";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_REFRESH = "refresh";

    private static boolean running;

    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;

    /** Called when playback starts (normally while the app is in the foreground). */
    static void start(Context ctx) {
        Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_REFRESH);
        try {
            if (running) ctx.startService(i);
            else ctx.startForegroundService(i);
        } catch (Exception e) {
            // Android 12+ refuses to start a foreground service from the background.
            Log.w(App.TAG, "could not start playback service", e);
        }
    }

    /** Updates the notification if the service is already running. */
    static void refresh(Context ctx) {
        if (running) start(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Reprodukcija", NotificationManager.IMPORTANCE_LOW));

        mediaSession = new MediaSession(this, "MarkTube");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { control(ACTION_PLAY); }
            @Override public void onPause() { control(ACTION_PAUSE); }
            @Override public void onSkipToNext() { control(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { control(ACTION_PREV); }
            @Override public void onStop() { control(ACTION_STOP); }
            @Override public void onSeekTo(long pos) {
                org.mozilla.geckoview.MediaSession m = App.media;
                if (m != null) m.seekTo(pos / 1000.0, false);
            }
        });
        mediaSession.setActive(true);

        wakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MarkTube:playback");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        // Always satisfy the startForeground() contract first.
        showNotification();
        if (action != null && !ACTION_REFRESH.equals(action)) control(action);
        return START_NOT_STICKY;
    }

    private void control(String action) {
        org.mozilla.geckoview.MediaSession m = App.media;
        Log.i(App.TAG, "control: " + action + " media=" + (m != null));
        // A page paused in the background has been made inactive; wake it before it plays.
        if (!ACTION_PAUSE.equals(action) && !ACTION_STOP.equals(action) && App.hasSession()) {
            App.session(this).setActive(true);
        }
        switch (action) {
            case ACTION_PLAY: if (m != null) m.play(); break;
            case ACTION_PAUSE: if (m != null) m.pause(); break;
            case ACTION_NEXT: if (m != null) m.nextTrack(); break;
            case ACTION_PREV: if (m != null) m.previousTrack(); break;
            case ACTION_STOP:
                if (m != null) m.pause();
                App.playing = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
        }
    }

    private void showNotification() {
        boolean playing = App.playing;
        String title = App.title != null && !App.title.isEmpty() ? App.title : getString(R.string.app_name);
        String artist = App.artist != null ? App.artist : "";

        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        if (App.durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, App.durationMs);
        if (App.artwork != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, App.artwork);
        mediaSession.setMetadata(meta.build());
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        App.positionMs >= 0 ? App.expectedPosition() : PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? App.rate : 0f)
                .build());

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(App.artwork)
                .setContentIntent(open)
                .setDeleteIntent(pending(ACTION_STOP))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(action(R.drawable.ic_prev, "Prethodni", ACTION_PREV))
                .addAction(playing
                        ? action(R.drawable.ic_pause, "Pauza", ACTION_PAUSE)
                        : action(R.drawable.ic_play, "Pusti", ACTION_PLAY))
                .addAction(action(R.drawable.ic_next, "Sledeći", ACTION_NEXT))
                .addAction(action(R.drawable.ic_close, "Zatvori", ACTION_STOP))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(App.TAG, "startForeground failed", e);
            getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        }

        if (playing) wakeLock.acquire(6 * 60 * 60 * 1000L);
        else if (wakeLock.isHeld()) wakeLock.release();
    }

    private Notification.Action action(int icon, String label, String act) {
        Icon ic = Icon.createWithResource(this, icon);
        return new Notification.Action.Builder(ic, label, pending(act)).build();
    }

    private PendingIntent pending(String act) {
        return PendingIntent.getService(this, act.hashCode(),
                new Intent(this, PlaybackService.class).setAction(act),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away from recents: stop playing.
        control(ACTION_STOP);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock.isHeld()) wakeLock.release();
        mediaSession.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
