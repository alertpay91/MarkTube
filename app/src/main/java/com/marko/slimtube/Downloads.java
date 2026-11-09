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
import com.yausername.youtubedl_android.mapper.VideoInfo;

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
        volatile int state = QUEUED;
        volatile float progress = -1;
        volatile String status = "Na čekanju";
        volatile Uri result;
        volatile String resultPath;
        volatile boolean cancelled;

        Job(int id, String url, String title, int mode) {
            this.id = id;
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
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
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

    /** Unpacks Python + yt-dlp on first use and updates yt-dlp at most once a day. */
    static synchronized void ensureReady(Context ctx) throws Exception {
        Context app = ctx.getApplicationContext();
        if (!ready) {
            YoutubeDL.getInstance().init(app);
            ready = true;
        }
        SharedPreferences p = app.getSharedPreferences("downloads", Context.MODE_PRIVATE);
        long last = p.getLong("ytdlpUpdate", 0);
        if (System.currentTimeMillis() - last > 24L * 3600 * 1000) {
            try {
                // YouTube changes often; an old yt-dlp stops working quickly.
                YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
                Log.i(App.TAG, "yt-dlp update: " + s + " " + YoutubeDL.getInstance().versionName(app));
                p.edit().putLong("ytdlpUpdate", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(App.TAG, "yt-dlp update failed", e);
            }
        }
    }

    /** Title, channel and licence of one video (blocking). */
    static VideoInfo info(Context ctx, String url) throws Exception {
        ensureReady(ctx);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-playlist");
        return YoutubeDL.getInstance().getInfo(r);
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

    static synchronized Job enqueue(Context ctx, String url, String title, int mode) {
        Job job = new Job(++nextId, url, title, mode);
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
        File dir = new File(ctx.getCacheDir(), "dl/" + job.id);
        try {
            ensureReady(ctx);
            deleteTree(dir);
            dir.mkdirs();
            if (job.mode == AUDIO) {
                fetch(job, dir, "ba[ext=m4a]/ba", "Zvuk");
                File f = only(dir);
                job.status = "Čuvanje…";
                save(ctx, job, f, ext(f.getName()));
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
                File out;
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
                    out = new File(dir, "joined.mp4");
                    mux(v, a, out);
                } else {
                    out = only(dir);
                }
                job.status = "Čuvanje…";
                save(ctx, job, out, ext(out.getName()));
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
        YoutubeDLRequest r = new YoutubeDLRequest(job.url);
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
    static void mux(File video, File audio, File out) throws IOException {
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
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
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

    /** Copies the finished file to Download/MarkTube (MediaStore on Android 10+). */
    private static void save(Context ctx, Job job, File src, String ext) throws IOException {
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
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("ne mogu da pišem fajl");
                copy(in, out);
            } catch (IOException e) {
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
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
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
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }
}
