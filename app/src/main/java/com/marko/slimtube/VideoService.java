package com.marko.slimtube;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Media service of the native player: notification, lock-screen controls, headphone buttons,
 * and it keeps the sound going with the screen off. MainActivity connects to it.
 */
public class VideoService extends MediaSessionService {
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        // Paused: leave the foreground after 3 min (default 10), so Android may freeze the app
        // instead of letting the page's scripts run on. Measured on a Pixel 8: ~3% of a core
        // while it stayed in the foreground, 0 after.
        setForegroundServiceTimeoutMs(180_000);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, NativePlayer.get(this))
                .setId("marktube-native")
                .setSessionActivity(open)
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // App swiped away from recents: stop, like the web player did.
        Player p = session != null ? session.getPlayer() : null;
        if (p != null) p.pause();
        NativePlayer.stop();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (session != null) session.release();
        session = null;
        super.onDestroy();
    }
}
