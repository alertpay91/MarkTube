package com.marko.slimtube;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * The native video player that replaces YouTube's web player on video pages. ExoPlayer hands
 * the hardware decoder's pictures straight to the screen, instead of the browser putting the
 * whole page together again for every frame (about a third of the CPU on a Pixel 4). Off
 * screen only the sound is fetched and played.
 */
@OptIn(markerClass = UnstableApi.class)
final class NativePlayer {
    private static ExoPlayer exo;
    private static Player player; // exo with a "next" that follows YouTube's autoplay
    private static Context ctx;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Video playing (or loading) now, null when none. */
    static String currentId;
    private static int generation;
    private static boolean retried;
    private static boolean videoWanted = true;

    /** Set by MainActivity. */
    static Runnable onNext;            // "next" pressed, or the video ended
    static Consumer<String> onFailed;  // this video can't be played natively: use the web player
    static Runnable onFirstFrame;

    static Player get(Context c) {
        if (exo == null) {
            ctx = c.getApplicationContext();
            exo = new ExoPlayer.Builder(ctx)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(), true)
                    .setHandleAudioBecomingNoisy(true)
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .setSeekBackIncrementMs(10_000)
                    .setSeekForwardIncrementMs(10_000)
                    // 15-30 s ahead instead of 50 s: less data that may never be watched
                    .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
                            .build())
                    .build();
            exo.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED && onNext != null) onNext.run();
                }

                @Override
                public void onIsPlayingChanged(boolean playing) {
                    if (playing) {
                        stuckMs = 0;
                        PlaybackService.dismiss(ctx); // one player notification, not two
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    if (playAt > 0) {
                        Log.i(App.TAG, "first frame " + (android.os.SystemClock.elapsedRealtime() - playAt) + " ms after the tap");
                        playAt = 0;
                    }
                    if (onFirstFrame != null) onFirstFrame.run();
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.w(App.TAG, "native player error", error);
                    String id = currentId;
                    if (id == null) return;
                    Streams.forget(id);
                    if (!retried) {
                        // addresses expire after some hours: look them up again once
                        retried = true;
                        long pos = exo.getCurrentPosition();
                        load(id, pos, exo.getPlayWhenReady());
                    } else if (onFailed != null) {
                        onFailed.accept(id);
                    }
                }
            });
            player = new ForwardingPlayer(exo) {
                @NonNull
                @Override
                public Commands getAvailableCommands() {
                    return super.getAvailableCommands().buildUpon()
                            .add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build();
                }

                @Override
                public boolean isCommandAvailable(int command) {
                    return command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                            || super.isCommandAvailable(command);
                }

                @Override
                public boolean hasNextMediaItem() {
                    return true;
                }

                @Override
                public void seekToNext() {
                    if (onNext != null) onNext.run();
                }

                @Override
                public void seekToNextMediaItem() {
                    if (onNext != null) onNext.run();
                }
            };
        }
        return player;
    }

    static ExoPlayer exo() {
        return exo;
    }

    // ---- Never leave the user looking at a spinner: if loading stalls, hand over ----

    /** Set by MainActivity: true while the app is on screen. */
    static boolean onScreen = true;
    private static long stuckMs;
    private static final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (exo == null || currentId == null) return;
            boolean waiting = exo.getPlayWhenReady() && (exo.getPlaybackState() == Player.STATE_BUFFERING
                    || (exo.getPlaybackState() == Player.STATE_IDLE && exo.getMediaItemCount() == 0));
            stuckMs = waiting ? stuckMs + 2000 : 0;
            if (stuckMs >= 10_000) {
                stuckMs = 0;
                String id = currentId;
                Log.w(App.TAG, "native player stuck " + id + (onScreen ? ": web player takes over" : ": looking it up again"));
                if (onScreen && onFailed != null) {
                    onFailed.accept(id);
                } else {
                    Streams.forget(id);
                    load(id, exo.getCurrentPosition(), true);
                }
            }
            main.postDelayed(this, 2000);
        }
    };

    /** Where the video was when it was handed to the web player. */
    static long position() {
        return exo == null ? 0 : exo.getCurrentPosition();
    }

    /** Starts playing a video (looked up first, if it has not been already). */
    /** Where recently watched videos were left, so Back to one continues it. */
    private static final java.util.LinkedHashMap<String, Long> lastPos = new java.util.LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
            return size() > 30;
        }
    };
    private static long playAt;

    private static void rememberPosition() {
        if (exo == null || currentId == null) return;
        long pos = exo.getCurrentPosition(), dur = exo.getDuration();
        if (pos > 5000 && (dur <= 0 || pos < dur - 10_000)) lastPos.put(currentId, pos);
        else lastPos.remove(currentId);
    }

    static void play(Context c, String id, long startMs) {
        get(c);
        if (id.equals(currentId)) return;
        rememberPosition();
        if (startMs <= 0 && lastPos.containsKey(id)) startMs = lastPos.get(id);
        playAt = android.os.SystemClock.elapsedRealtime();
        currentId = id;
        retried = false;
        exo.stop();
        exo.clearMediaItems();
        stuckMs = 0;
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 2000);
        load(id, startMs, true);
        loadSponsorSegments(id);
    }

    /** Same video, same moment, streams picked again (after the 30/60 fps switch). */
    static void reload() {
        if (currentId == null || exo == null) return;
        Streams.forget(currentId);
        long pos = exo.getCurrentPosition();
        boolean pw = exo.getPlayWhenReady();
        load(currentId, pos, pw);
    }

    private static void load(String id, long startMs, boolean playWhenReady) {
        int gen = ++generation;
        Streams.prefetch(id);
        new Thread(() -> {
            Streams s;
            try {
                s = Streams.get(id);
            } catch (Exception e) {
                Log.w(App.TAG, "streams failed " + id, e);
                main.post(() -> {
                    if (gen == generation && onFailed != null) onFailed.accept(id);
                });
                return;
            }
            main.post(() -> {
                if (gen != generation) return;
                start(s, startMs, playWhenReady);
            });
        }, "streams").start();
    }

    private static void start(Streams s, long startMs, boolean playWhenReady) {
        ProgressiveMediaSource.Factory fa = new ProgressiveMediaSource.Factory(http(s.audio));
        ProgressiveMediaSource.Factory fv = s.video != null ? new ProgressiveMediaSource.Factory(http(s.video)) : null;
        MediaItem.Builder item = new MediaItem.Builder()
                .setMediaId(s.id)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.channel)
                        .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/" + s.id + "/hqdefault.jpg"))
                        .build());
        MediaSource audio = fa.createMediaSource(item.setUri(s.audio).build());
        MediaSource source = fv == null ? audio
                : new MergingMediaSource(fv.createMediaSource(item.setUri(s.video).build()), audio);
        exo.setMediaSource(source, Math.max(0, startMs));
        applyVideoWanted();
        exo.prepare();
        exo.setPlayWhenReady(playWhenReady);
        sendSkips(s.id);
    }

    /** YouTube only serves a stream to the kind of client it was handed out for. */
    private static androidx.media3.datasource.DataSource.Factory http(String url) {
        String ua = NetDownloader.UA;
        try {
            if (org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                ua = org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent(
                        org.schabi.newpipe.extractor.localization.Localization.DEFAULT);
            }
        } catch (Throwable ignored) {
        }
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", "https://www.youtube.com/");
        return new ChunkedDataSource.Factory(new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000));
    }

    /** Off screen nobody sees the picture: stop fetching and decoding it. */
    static void setVideoWanted(boolean on) {
        videoWanted = on;
        if (exo != null) applyVideoWanted();
    }

    private static void applyVideoWanted() {
        exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoWanted).build());
    }

    static void stop() {
        rememberPosition();
        currentId = null;
        main.removeCallbacks(watchdog);
        generation++;
        if (exo != null) {
            exo.stop();
            exo.clearMediaItems();
        }
    }

    static boolean isPlaying() {
        return exo != null && exo.isPlaying();
    }

    // ---- SponsorBlock (the add-on only sees the web player) ----

    private static final String CATEGORIES = "[\"sponsor\",\"selfpromo\",\"interaction\"]";

    private static void loadSponsorSegments(String id) {
        new Thread(() -> {
            try {
                // Only the first 4 characters of the hashed id are sent, for privacy.
                byte[] h = MessageDigest.getInstance("SHA-256").digest(id.getBytes("UTF-8"));
                String prefix = String.format("%02x%02x", h[0], h[1]);
                URL u = new URL("https://sponsor.ajay.app/api/skipSegments/" + prefix
                        + "?categories=" + Uri.encode(CATEGORIES));
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                }
                JSONArray all = new JSONArray(buf.toString("UTF-8"));
                for (int i = 0; i < all.length(); i++) {
                    JSONObject v = all.getJSONObject(i);
                    if (!id.equals(v.optString("videoID"))) continue;
                    JSONArray segs = v.getJSONArray("segments");
                    for (int k = 0; k < segs.length(); k++) {
                        JSONObject seg = segs.getJSONObject(k);
                        JSONArray t = seg.getJSONArray("segment");
                        long from = (long) (t.getDouble(0) * 1000), to = (long) (t.getDouble(1) * 1000);
                        if (to - from < 1000) continue;
                        main.post(() -> addSkip(id, from, to));
                    }
                }
            } catch (Exception e) {
                Log.i(App.TAG, "sponsorblock: " + e);
            }
        }, "sponsorblock").start();
    }

    private static final java.util.List<long[]> segments = new java.util.ArrayList<>();
    private static String segmentsFor;

    private static void addSkip(String id, long from, long to) {
        if (exo == null || !id.equals(currentId)) return;
        if (!id.equals(segmentsFor)) { segments.clear(); segmentsFor = id; }
        segments.add(new long[]{from, to});
        MediaItem cur = exo.getCurrentMediaItem();
        if (cur != null && id.equals(cur.mediaId)) sendSkip(id, from, to);
    }

    private static void sendSkips(String id) {
        if (!id.equals(segmentsFor)) return;
        for (long[] seg : segments) sendSkip(id, seg[0], seg[1]);
    }

    /** The player itself jumps over the segment when it gets there: no polling. */
    private static void sendSkip(String id, long from, long to) {
        exo.createMessage((type, payload) -> {
                    if (id.equals(currentId) && exo.getCurrentPosition() < to - 500) {
                        Log.i(App.TAG, "sponsorblock skip " + from + "-" + to);
                        exo.seekTo(to);
                    }
                })
                .setLooper(Looper.getMainLooper())
                .setPosition(from)
                .setDeleteAfterDelivery(false)
                .send();
    }
}
