package com.marko.slimtube;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Downloads through yt-dlp (youtubedl-android). The user confirms for every download that they
 * are allowed to download it; files go to Download/MarkTube.
 *
 * No ffmpeg is bundled (it would add ~35 MB): video is fetched as separate H.264 video and AAC
 * audio streams and joined with Android's own MediaMuxer.
 */
final class Downloads {
    static final int VIDEO_720 = 1, VIDEO_1080 = 2, AUDIO = 3; // also the radio-button ids (must be > 0)
    static final int QUEUED = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4;

    static final class Job {
        final int id;
        final String url;
        final String title;
        final int mode;
        final String processId;
        /** yt-dlp's JSON from "Proveri snimak": the download reuses it instead of asking YouTube again. */
        final File infoJson;
        volatile int state = QUEUED;
        volatile float progress = -1;
        volatile String status = "Na čekanju";
        volatile Uri result;
        volatile String resultPath;
        volatile boolean cancelled;

        Job(int id, String url, String title, int mode, File infoJson) {
            this.id = id;
            this.infoJson = infoJson;
            this.url = url;
            this.title = title;
            this.mode = mode;
            this.processId = "dl" + id;
        }

        boolean active() {
            return state == QUEUED || state == RUNNING;
        }
    }

    static final List<Job> jobs = new CopyOnWriteArrayList<>();
    /** Two at a time: most of a download is yt-dlp talking to YouTube, not the transfer itself. */
    private static final ExecutorService worker = Executors.newFixedThreadPool(2);
    /** Stream links in a saved info expire after a few hours; older info is not reused. */
    private static final long INFO_MAX_AGE = 2L * 3600 * 1000;
    private static int nextId;
    private static boolean ready;

    private Downloads() { }

    static boolean anyActive() {
        for (Job j : jobs) if (j.active()) return true;
        return false;
    }

    static Job running() {
        for (Job j : jobs) if (j.state == RUNNING) return j;
        return null;
    }

    /** Unpacks Python + yt-dlp on first use. */
    static synchronized void ensureReady(Context ctx) throws Exception {
        if (!ready) {
            YoutubeDL.getInstance().init(ctx.getApplicationContext());
            if (!anyActive()) cleanWork(ctx.getApplicationContext());
            ready = true;
        }
    }

    /**
     * Updates yt-dlp at most once a day (YouTube changes often; an old yt-dlp stops working).
     * Called when the download screen is left with nothing running, so a check or a download
     * never has to wait for the update any more.
     */
    static void updateIfDue(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("downloads", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("ytdlpUpdate", 0) < 24L * 3600 * 1000) return;
        if (anyActive()) return;
        new Thread(() -> {
            try {
                ensureReady(app);
                YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
                Log.i(App.TAG, "yt-dlp update: " + s + " " + YoutubeDL.getInstance().versionName(app));
                p.edit().putLong("ytdlpUpdate", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(App.TAG, "yt-dlp update failed", e);
            }
        }, "ytdlp-update").start();
    }

    /** What "Proveri snimak" shows about one video. */
    static final class Info {
        String title, uploader, license;
        int duration;
        /** yt-dlp's full JSON; null for the quick answer, which has only title + channel. */
        File json;
    }

    /**
     * Title and channel in well under a second through YouTube's public oEmbed endpoint, so the
     * user sees at once which video it is; length and licence follow from {@link #info}.
     */
    static Info quickInfo(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(url, "UTF-8")).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            copy(in, b);
            org.json.JSONObject j = new org.json.JSONObject(b.toString("UTF-8"));
            Info i = new Info();
            i.title = j.optString("title", null);
            i.uploader = j.optString("author_name", null);
            return i;
        } finally {
            c.disconnect();
        }
    }

    /** Title, channel, length and licence (blocking, ~10 s); keeps yt-dlp's JSON for the download. */
    static Info info(Context ctx, String url) throws Exception {
        ensureReady(ctx);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-playlist");
        r.addOption("-J");
        long t0 = System.currentTimeMillis();
        String out = YoutubeDL.getInstance().execute(r, null, null).getOut();
        Log.i(App.TAG, "yt-dlp info " + (System.currentTimeMillis() - t0) + " ms");
        String json = out.substring(out.indexOf('{'));
        org.json.JSONObject j = new org.json.JSONObject(json);
        Info i = new Info();
        i.title = j.optString("title", null);
        i.uploader = j.isNull("uploader") ? j.optString("channel", null) : j.optString("uploader", null);
        i.duration = j.optInt("duration", 0);
        i.license = j.isNull("license") ? null : j.optString("license", null);
        File dir = new File(ctx.getCacheDir(), "info");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            if (System.currentTimeMillis() - f.lastModified() > INFO_MAX_AGE) f.delete();
        }
        File f = new File(dir, safeName(j.optString("id", "video")) + ".json");
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(json.getBytes("UTF-8"));
        }
        i.json = f;
        return i;
    }

    /** One playlist: its title and the videos in it (id + title), without downloading anything. */
    static final class Playlist {
        String title;
        final List<String[]> entries = new java.util.ArrayList<>(); // {watch URL, title}
    }

    static final int PLAYLIST_MAX = 200;

    /** Lists a playlist or mix (blocking). Mixes are endless; yt-dlp returns their first batch. */
    static Playlist playlist(Context ctx, String url) throws Exception {
        ensureReady(ctx);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--yes-playlist");
        r.addOption("--flat-playlist");
        r.addOption("--playlist-end", String.valueOf(PLAYLIST_MAX));
        r.addOption("-J");
        String out = YoutubeDL.getInstance().execute(r, null, null).getOut();
        org.json.JSONObject j = new org.json.JSONObject(out.substring(out.indexOf('{')));
        Playlist p = new Playlist();
        p.title = j.optString("title", "Plejlista");
        org.json.JSONArray entries = j.optJSONArray("entries");
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            org.json.JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (id.isEmpty()) continue;
            String t = e.optString("title", "");
            // Deleted / private videos show up with placeholder titles; skip them.
            if (t.equals("[Deleted video]") || t.equals("[Private video]")) continue;
            p.entries.add(new String[]{"https://www.youtube.com/watch?v=" + id, t.isEmpty() ? id : t});
        }
        return p;
    }

    static Job enqueue(Context ctx, String url, String title, int mode) {
        return enqueue(ctx, url, title, mode, null);
    }

    static synchronized Job enqueue(Context ctx, String url, String title, int mode, File infoJson) {
        Job job = new Job(++nextId, url, title, mode, infoJson);
        jobs.add(0, job);
        Context app = ctx.getApplicationContext();
        DownloadService.start(app);
        worker.submit(() -> run(app, job));
        return job;
    }

    static void cancel(Job job) {
        job.cancelled = true;
        if (job.state == QUEUED) {
            job.state = CANCELLED;
            job.status = "Otkazano";
        } else if (job.state == RUNNING) {
            YoutubeDL.getInstance().destroyProcessById(job.processId);
        }
    }

    static void clearFinished() {
        for (Job j : jobs) if (!j.active()) jobs.remove(j);
    }

    // ---- Worker ----

    private static void run(Context ctx, Job job) {
        if (job.cancelled) return;
        job.state = RUNNING;
        job.status = "Priprema…";
        // Not in getCacheDir(): when the phone is nearly full Android empties app caches on its
        // own, and the half-done video vanished in the middle of a download.
        File dir = new File(workRoot(ctx), String.valueOf(job.id));
        try {
            ensureReady(ctx);
            deleteTree(dir);
            dir.mkdirs();
            if (job.mode == AUDIO) {
                fetch(job, dir, "ba[ext=m4a]/ba", "Zvuk");
                File f = only(dir);
                job.status = "Čuvanje…";
                save(ctx, job, ext(f.getName()), fd -> copy(f, fd));
            } else {
                int h = job.mode == VIDEO_1080 ? 1080 : 720;
                boolean split;
                try {
                    // Best H.264 video up to the chosen height + the AAC audio, as two files.
                    fetch(job, dir, "bv[vcodec^=avc1][ext=mp4][height<=" + h + "],ba[ext=m4a]", "Video");
                    split = true;
                } catch (Exception e) {
                    if (job.cancelled) throw e;
                    Log.w(App.TAG, "separate streams unavailable, trying single file", e);
                    deleteTree(dir);
                    dir.mkdirs();
                    fetch(job, dir, "b[ext=mp4][height<=" + h + "]/b[ext=mp4]/b", "Video");
                    split = false;
                }
                if (split) {
                    File v = null, a = null;
                    File[] files = dir.listFiles();
                    if (files != null) for (File f : files) {
                        if (f.getName().endsWith(".m4a")) a = f;
                        else if (f.getName().endsWith(".mp4")) v = f;
                    }
                    if (v == null || a == null) throw new IOException("nedostaje video ili zvuk");
                    job.status = "Spajanje slike i zvuka…";
                    job.progress = -1;
                    // Joined straight into Download/MarkTube: no temporary copy of the whole video.
                    File fv = v, fa = a;
                    save(ctx, job, "mp4", fd -> mux(fv, fa, fd));
                } else {
                    File out = only(dir);
                    job.status = "Čuvanje…";
                    save(ctx, job, ext(out.getName()), fd -> copy(out, fd));
                }
            }
            job.state = DONE;
            job.progress = 100;
            job.status = "Preuzeto — " + (job.resultPath != null ? job.resultPath : "Download/MarkTube");
        } catch (Throwable t) {
            if (job.cancelled) {
                job.state = CANCELLED;
                job.status = "Otkazano";
            } else {
                Log.e(App.TAG, "download failed", t);
                job.state = FAILED;
                job.status = "Greška: " + shortError(t);
            }
        } finally {
            deleteTree(dir);
            DownloadService.refresh(ctx);
        }
    }

    private static void fetch(Job job, File dir, String format, String label) throws Exception {
        File json = job.infoJson;
        if (json != null && json.isFile() && System.currentTimeMillis() - json.lastModified() < INFO_MAX_AGE) {
            try {
                fetch(job, dir, format, label, json);
                return;
            } catch (Exception e) {
                if (job.cancelled) throw e;
                // The links in the saved info may have expired or lack this format: ask YouTube again.
                Log.w(App.TAG, "saved info failed, extracting again", e);
                deleteTree(dir);
                dir.mkdirs();
            }
        }
        fetch(job, dir, format, label, null);
    }

    private static void fetch(Job job, File dir, String format, String label, File json) throws Exception {
        YoutubeDLRequest r;
        if (json != null) {
            // Skips the ~10 s of asking YouTube about the video: "Proveri snimak" already did.
            r = new YoutubeDLRequest(java.util.Collections.emptyList());
            r.addOption("--load-info-json", json.getAbsolutePath());
        } else {
            r = new YoutubeDLRequest(job.url);
        }
        r.addOption("--no-playlist");
        r.addOption("--no-mtime");
        r.addOption("--no-part");
        r.addOption("-f", format);
        r.addOption("-o", new File(dir, "%(format_id)s.%(ext)s").getAbsolutePath());
        final int[] part = {0};
        final float[] last = {0};
        job.status = label + "…";
        YoutubeDL.getInstance().execute(r, job.processId, (progress, eta, line) -> {
            float p = progress;
            if (p >= 0) {
                if (p + 30 < last[0]) part[0]++; // second stream started
                last[0] = p;
                job.progress = p;
                job.status = (part[0] > 0 ? "Zvuk" : label) + " " + Math.round(p) + "%";
            }
            return Unit.INSTANCE;
        });
    }

    /** Joins an H.264 video-only MP4 and an AAC audio file into one MP4, interleaved by time. */
    static void mux(File video, File audio, java.io.FileDescriptor out) throws IOException {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer m = null;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vt = track(ve, "video/");
            int at = track(ae, "audio/");
            if (vt < 0 || at < 0) throw new IOException("neispravan snimak");
            ve.selectTrack(vt);
            ae.selectTrack(at);
            m = new MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vo = m.addTrack(ve.getTrackFormat(vt));
            int ao = m.addTrack(ae.getTrackFormat(at));
            m.start();
            ByteBuffer buf = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean vDone = false, aDone = false;
            while (!vDone || !aDone) {
                long vTime = vDone ? Long.MAX_VALUE : ve.getSampleTime();
                long aTime = aDone ? Long.MAX_VALUE : ae.getSampleTime();
                if (vTime < 0) { vDone = true; continue; }
                if (aTime < 0) { aDone = true; continue; }
                boolean useVideo = vTime <= aTime;
                MediaExtractor ex = useVideo ? ve : ae;
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) {
                    if (useVideo) vDone = true; else aDone = true;
                    continue;
                }
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, size, ex.getSampleTime(), flags);
                m.writeSampleData(useVideo ? vo : ao, buf, info);
                ex.advance();
            }
            m.stop();
        } finally {
            if (m != null) try { m.release(); } catch (Exception ignored) { }
            ve.release();
            ae.release();
        }
    }

    private static int track(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Where yt-dlp puts the streams while downloading (never purged by the system, unlike the cache). */
    private static File workRoot(Context ctx) {
        return new File(ctx.getNoBackupFilesDir(), "dl");
    }

    /** Leftovers of downloads that were killed with the app (called once per process, nothing running yet). */
    private static void cleanWork(Context ctx) {
        deleteTree(workRoot(ctx));
        deleteTree(new File(ctx.getCacheDir(), "dl")); // older versions worked in the cache
    }

    interface Sink {
        void write(java.io.FileDescriptor fd) throws IOException;
    }

    /** Writes the finished file into Download/MarkTube (MediaStore on Android 10+). */
    private static void save(Context ctx, Job job, String ext, Sink sink) throws IOException {
        String name = safeName(job.title) + "." + ext;
        String mime = mime(ext, job.mode == AUDIO);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MarkTube");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("ne mogu da napravim fajl");
            try (android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw")) {
                if (pfd == null) throw new IOException("ne mogu da pišem fajl");
                sink.write(pfd.getFileDescriptor());
            } catch (IOException | RuntimeException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            job.result = uri;
            job.resultPath = "Download/MarkTube/" + name;
        } else {
            File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MarkTube");
            dir.mkdirs();
            File dst = new File(dir, name);
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dst, "rw")) {
                raf.setLength(0);
                sink.write(raf.getFD());
            } catch (IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw e;
            }
            job.resultPath = dst.getAbsolutePath();
        }
    }

    // ---- Small helpers ----

    private static File only(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) throw new IOException("ništa nije preuzeto");
        File best = files[0];
        for (File f : files) if (f.length() > best.length()) best = f;
        return best;
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "bin" : name.substring(i + 1).toLowerCase();
    }

    private static String mime(String ext, boolean audio) {
        switch (ext) {
            case "mp4": return audio ? "audio/mp4" : "video/mp4";
            case "m4a": return "audio/mp4";
            case "webm": return audio ? "audio/webm" : "video/webm";
            case "opus": case "ogg": return "audio/ogg";
            case "mp3": return "audio/mpeg";
            default: return "application/octet-stream";
        }
    }

    static String safeName(String title) {
        String s = title == null || title.trim().isEmpty() ? "MarkTube" : title.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static void copy(File src, java.io.FileDescriptor fd) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(fd)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[256 * 1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String shortError(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        for (String line : m.split("\n")) {
            if (line.contains("ERROR:")) {
                m = line.substring(line.indexOf("ERROR:") + 6).trim();
                break;
            }
        }
        if (m.contains("Requested format is not available")) return "ovaj snimak nema format koji može da se preuzme";
        if (m.contains("Sign in to confirm") || m.contains("not a bot")) return "YouTube traži prijavu za ovaj snimak";
        if (m.contains("Private video")) return "privatan snimak";
        if (m.contains("members-only") || m.contains("Join this channel")) return "snimak je samo za članove kanala";
        if (m.contains("DRM")) return "snimak je zaštićen (DRM) i ne može da se preuzme";
        if (m.contains("ENOSPC") || m.contains("No space left")) return "nema dovoljno mesta na telefonu";
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }
}
