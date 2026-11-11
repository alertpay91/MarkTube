package com.marko.slimtube;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the direct picture and sound addresses of a video (NewPipeExtractor), so the native
 * player can play it instead of YouTube's web player.
 */
final class Streams {
    /** Tallest picture fetched. H.264 is preferred: every phone decodes it in hardware. */
    static final int MAX_HEIGHT = 720;
    /** Addresses stay valid for about 6 hours; reuse them for a while. */
    private static final long CACHE_MS = 2 * 60 * 60 * 1000L;

    final String id, title, channel, video, audio, next;
    final long durationMs;
    final long at = System.currentTimeMillis();

    private Streams(String id, String title, String channel, String video, String audio, String next, long durationMs) {
        this.id = id;
        this.title = title;
        this.channel = channel;
        this.video = video;
        this.audio = audio;
        this.next = next;
        this.durationMs = durationMs;
    }

    private static boolean ready;
    private static final Map<String, Streams> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Future<Streams>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    static synchronized void init() {
        if (ready) return;
        NewPipe.init(NetDownloader.get(), new Localization("en", "GB"), new ContentCountry("RS"));
        ready = true;
    }

    /** Starts looking up a video in the background (as early as possible: on the tap). */
    static Future<Streams> prefetch(String id) {
        Streams c = CACHE.get(id);
        if (c != null && System.currentTimeMillis() - c.at < CACHE_MS) {
            return java.util.concurrent.CompletableFuture.completedFuture(c);
        }
        return PENDING.computeIfAbsent(id, k -> IO.submit(() -> {
            try {
                return load(k);
            } finally {
                PENDING.remove(k);
            }
        }));
    }

    /** Blocking. */
    static Streams get(String id) throws IOException {
        try {
            return prefetch(id).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(String.valueOf(t), t);
        } catch (InterruptedException e) {
            throw new IOException("interrupted", e);
        }
    }

    static void forget(String id) {
        CACHE.remove(id);
    }

    private static int videoScore(VideoStream v) {
        String codec = v.getCodec();
        return v.getHeight() + (codec != null && codec.startsWith("avc1") ? 10000 : 0);
    }

    private static Streams load(String id) throws IOException {
        init();
        long t0 = System.currentTimeMillis();
        try {
            StreamInfo si = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
            AudioStream best = null;
            int bestScore = Integer.MIN_VALUE;
            for (AudioStream a : si.getAudioStreams()) {
                if (a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !a.isUrl()) continue;
                int score = a.getAverageBitrate();
                if (a.getFormat() == MediaFormat.M4A) score += 1000;
                AudioTrackType tt = a.getAudioTrackType();
                if (tt == null || tt == AudioTrackType.ORIGINAL) score += 5000; // not a dubbed track
                if (score > bestScore) {
                    bestScore = score;
                    best = a;
                }
            }
            VideoStream video = null;
            for (VideoStream v : si.getVideoOnlyStreams()) {
                if (v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !v.isUrl()) continue;
                if (v.getFormat() != MediaFormat.MPEG_4 || v.getHeight() > MAX_HEIGHT) continue;
                if (video == null || videoScore(v) > videoScore(video)) video = v;
            }
            String audioUrl = best != null ? best.getContent() : null;
            String videoUrl = video != null ? video.getContent() : null;
            if (audioUrl == null) {
                // only a stream with picture and sound together
                for (VideoStream v : si.getVideoStreams()) {
                    if (v.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP && v.isUrl()) {
                        audioUrl = v.getContent();
                        videoUrl = null;
                    }
                }
            }
            if (audioUrl == null) throw new IOException("no playable stream");
            String next = null;
            if (si.getRelatedItems() != null) {
                for (InfoItem it : si.getRelatedItems()) {
                    if (it instanceof StreamInfoItem) {
                        next = it.getUrl();
                        break;
                    }
                }
            }
            Streams s = new Streams(id, si.getName(), si.getUploaderName(), videoUrl, audioUrl, next,
                    si.getDuration() * 1000L);
            CACHE.put(id, s);
            android.util.Log.i(App.TAG, "streams " + id + " in " + (System.currentTimeMillis() - t0) + " ms, video "
                    + (video != null ? video.getResolution() + " " + video.getCodec() : "none"));
            return s;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }
}
