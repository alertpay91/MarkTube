package com.marko.slimtube;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Holds the single Gecko engine + YouTube tab for the whole process, so playback
 * survives the activity going to the background or being recreated.
 */
public class App extends Application {
    static final String TAG = "MarkTube";
    static final String HOME = "https://m.youtube.com/";

    /** {addon id, addons.mozilla.org slug} – installed (AMO-signed) on first start, updated later. */
    private static final String[][] EXTENSIONS = {
            {"uBlock0@raymondhill.net", "ublock-origin"},
            {"sponsorBlocker@ajay.app", "sponsorblock"},
    };
    /**
     * Installed by older versions. Background play is done by the helper now (bg.js): this
     * add-on together with uBlock Origin delayed every video start by about 5 seconds.
     */
    private static final String[] REMOVED_EXTENSIONS = {"video-bg-play@timdream.org"};

    private static GeckoRuntime runtime;
    private static Context appContext;
    private static GeckoSession session;

    // Current media state, read by PlaybackService.
    static MediaSession media;
    static String title;
    static String artist;
    static Bitmap artwork;
    static boolean playing;
    // Position for the notification seek bar (milliseconds) and when it was reported.
    static long durationMs = -1;
    static long positionMs = -1;
    static float rate = 1f;
    static long positionAt;

    // Built-in helper extension (assets/extensions/helper) used by the back button.
    private static WebExtension helper;
    private static WebExtension.Port helperPort;
    private static final Map<Integer, Consumer<Boolean>> pendingBack = new HashMap<>();
    private static int nextBackId;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Set by MainActivity: the page's "Preuzmi" tab was tapped (argument = page URL). */
    static Consumer<String> onDownload;

    @Override
    public void onCreate() {
        super.onCreate();
        // Gecko's content/GPU processes run this too; only the main process checks for updates.
        if (android.os.Build.VERSION.SDK_INT < 28 || getPackageName().equals(getProcessName())) {
            Updater.check(this);
        }
    }

    static GeckoRuntime runtime(Context ctx) {
        if (runtime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    // Dark from the first frame: no white page while YouTube is still loading.
                    .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                    .build();
            appContext = ctx.getApplicationContext();
            runtime = GeckoRuntime.create(ctx.getApplicationContext(), settings);
            setupExtensions(runtime);
        }
        return runtime;
    }

    /** True while MainActivity is on screen. */
    static boolean foreground;

    static void setForeground(boolean fg) {
        foreground = fg;
        if (!hasSession()) return;
        updateActive();
        if (!fg) session.setFocused(false);
        // The helper's checks (download buttons, sponsored Shorts) are only needed on screen.
        WebExtension.Port port = helperPort;
        if (port != null) {
            try { port.postMessage(new JSONObject().put("type", "fg").put("value", fg)); } catch (Exception ignored) { }
        }
    }

    /**
     * The page is active on screen and while it plays in the background (an inactive page
     * stops playing after a moment). Paused in the background, it goes inactive after a few
     * seconds: Gecko then stops drawing it and slows its timers. Before, it stayed active and
     * used ~18% of a core while paused, for as long as the process lived.
     */
    /** A page made inactive halfway through loading stays blank, so it may finish first. */
    private static boolean loading;

    private static final Runnable deactivate = () -> {
        if (!foreground && !playing && !loading && hasSession()) {
            Log.i(TAG, "page inactive (paused in background)");
            session.setActive(false);
        }
    };

    static void updateActive() {
        if (!hasSession()) return;
        main.removeCallbacks(deactivate);
        if (foreground || playing) session.setActive(true);
        else main.postDelayed(deactivate, 3000);
    }

    static boolean hasSession() {
        return session != null && session.isOpen();
    }

    static GeckoSession session(Context ctx) {
        if (!hasSession()) {
            GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                    .usePrivateMode(false)
                    .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                    .build();
            session = new GeckoSession(settings);
            session.setMediaSessionDelegate(new MediaDelegate(ctx.getApplicationContext()));
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(GeckoSession s, String url) { loading = true; }

                @Override
                public void onPageStop(GeckoSession s, boolean success) {
                    loading = false;
                    if (!foreground) updateActive();
                }
            });
            session.open(runtime(ctx));
            attachHelper();
            session.loadUri(HOME);
        }
        return session;
    }

    static void resetSession() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
        }
        session = null;
        helperPort = null;
        media = null;
        playing = false;
    }

    private static void setupExtensions(GeckoRuntime rt) {
        WebExtensionController controller = rt.getWebExtensionController();
        controller.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    WebExtension ext, String[] perms, String[] origins, String[] data) {
                // Only our own fixed list of extensions is ever installed.
                return GeckoResult.fromValue(new WebExtension.PermissionPromptResponse(true, false, false));
            }

            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(
                    WebExtension ext, String[] perms, String[] origins, String[] data) {
                return GeckoResult.allow();
            }

            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(
                    WebExtension ext, String[] perms, String[] origins, String[] data) {
                return GeckoResult.allow();
            }
        });

        controller.ensureBuiltIn("resource://android/assets/extensions/helper/", "helper@slimtube.marko")
                .accept(ext -> {
                    helper = ext;
                    attachHelper();
                }, t -> Log.e(TAG, "helper extension failed", t));

        controller.list().accept(installed -> {
            Set<String> ids = new HashSet<>();
            // Checking all add-ons for updates on every start competes with YouTube's first
            // load; once a day is plenty.
            android.content.SharedPreferences prefs =
                    appContext.getSharedPreferences("app", Context.MODE_PRIVATE);
            boolean checkUpdates = System.currentTimeMillis() - prefs.getLong("extUpdate", 0) > 24L * 3600 * 1000;
            if (checkUpdates) prefs.edit().putLong("extUpdate", System.currentTimeMillis()).apply();
            if (installed != null) {
                for (WebExtension ext : installed) {
                    ids.add(ext.id);
                    Log.i(TAG, "extension installed: " + ext.id + " enabled=" + ext.metaData.enabled);
                    if (java.util.Arrays.asList(REMOVED_EXTENSIONS).contains(ext.id)) {
                        controller.uninstall(ext).accept(v -> Log.i(TAG, "removed " + ext.id),
                                e -> Log.w(TAG, "remove failed " + ext.id, e));
                        continue;
                    }
                    if (!ext.isBuiltIn && !ext.metaData.enabled) {
                        controller.enable(ext, WebExtensionController.EnableSource.USER);
                    }
                    if (checkUpdates && !ext.isBuiltIn) {
                        // Later, when the page has loaded.
                        main.postDelayed(() -> controller.update(ext)
                                .accept(null, e -> Log.w(TAG, "update failed " + ext.id, e)), 20000);
                    }
                }
            }
            List<GeckoResult<WebExtension>> pending = new ArrayList<>();
            for (String[] e : EXTENSIONS) {
                if (!ids.contains(e[0])) {
                    String url = "https://addons.mozilla.org/firefox/downloads/latest/" + e[1] + "/latest.xpi";
                    pending.add(controller.install(url)
                            .<WebExtension>exceptionally(t -> {
                                Log.e(TAG, "install failed " + e[1], t);
                                return GeckoResult.fromValue(null);
                            }));
                }
            }
            if (!pending.isEmpty()) {
                // Reload YouTube once the blockers are active.
                GeckoResult.allOf(pending).accept(list -> {
                    if (list != null) for (WebExtension x : list) {
                        if (x != null) Log.i(TAG, "extension newly installed: " + x.id);
                    }
                    if (hasSession()) session.reload();
                });
            }
        }, t -> Log.e(TAG, "listing extensions failed", t));
    }

    private static void attachHelper() {
        if (helper == null || !hasSession()) return;
        session.getWebExtensionController().setMessageDelegate(helper, new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(WebExtension.Port port) {
                helperPort = port;
                if (!foreground) {
                    try { port.postMessage(new JSONObject().put("type", "fg").put("value", false)); } catch (Exception ignored) { }
                }
                port.setDelegate(new WebExtension.PortDelegate() {
                    @Override
                    public void onPortMessage(Object message, WebExtension.Port p) {
                        if (!(message instanceof JSONObject)) return;
                        JSONObject msg = (JSONObject) message;
                        String type = msg.optString("type");
                        if ("backResult".equals(type)) {
                            Log.i(TAG, "back: page closed " + msg.optString("what", "nothing"));
                            Consumer<Boolean> cb = pendingBack.remove(msg.optInt("id"));
                            if (cb != null) cb.accept(msg.optBoolean("handled"));
                        } else if ("download".equals(type)) {
                            Consumer<String> d = onDownload;
                            if (d != null) d.accept(msg.optString("url"));
                        } else if ("log".equals(type)) {
                            Log.i(TAG, "page: " + msg.optString("text"));
                        } else if ("dump".equals(type)) {
                            Log.i(TAG, "page: " + msg.optJSONObject("data"));
                        }
                    }

                    @Override
                    public void onDisconnect(WebExtension.Port p) {
                        if (helperPort == p) helperPort = null;
                    }
                });
            }
        }, "slimtube");
    }

    /**
     * Asks the page to close an open menu / panel. Calls back with true if it closed something,
     * false otherwise (also after a short timeout, so the back button never feels stuck).
     */
    static void requestPageBack(Consumer<Boolean> callback) {
        WebExtension.Port port = helperPort;
        if (port == null) { callback.accept(false); return; }
        int id = ++nextBackId;
        pendingBack.put(id, callback);
        try {
            port.postMessage(new JSONObject().put("type", "back").put("id", id));
            port.postMessage(new JSONObject().put("type", "dump"));
        } catch (Exception e) {
            pendingBack.remove(id);
            callback.accept(false);
            return;
        }
        main.postDelayed(() -> {
            Consumer<Boolean> cb = pendingBack.remove(id);
            if (cb != null) cb.accept(false);
        }, 300);
    }

    /** Switches tab inside the page (no full reload) when the helper is there, else uses the fallback. */
    static void navigate(String url, Runnable fallback) {
        WebExtension.Port port = helperPort;
        if (port == null) { fallback.run(); return; }
        try {
            port.postMessage(new JSONObject().put("type", "nav").put("url", url));
        } catch (Exception e) {
            fallback.run();
        }
    }

    static long expectedPosition() {
        if (positionMs < 0) return -1;
        if (!playing) return positionMs;
        return positionMs + (long) ((android.os.SystemClock.elapsedRealtime() - positionAt) * rate);
    }

    /** Bridges the page's media session (play/pause/metadata) to the notification service. */
    private static class MediaDelegate implements MediaSession.Delegate {
        private final Context ctx;

        MediaDelegate(Context ctx) { this.ctx = ctx; }

        @Override
        public void onActivated(GeckoSession s, MediaSession ms) {
            media = ms;
        }

        @Override
        public void onDeactivated(GeckoSession s, MediaSession ms) {
            if (media == ms) {
                playing = false;
                PlaybackService.refresh(ctx);
            }
        }

        @Override
        public void onMetadata(GeckoSession s, MediaSession ms, MediaSession.Metadata meta) {
            media = ms;
            title = meta.title;
            durationMs = -1;
            positionMs = -1;
            artist = meta.artist;
            artwork = null;
            PlaybackService.refresh(ctx);
            if (meta.artwork != null) {
                meta.artwork.getBitmap(512).accept(bmp -> {
                    artwork = bmp;
                    PlaybackService.refresh(ctx);
                }, t -> { });
            }
        }

        @Override
        public void onPositionState(GeckoSession s, MediaSession ms, MediaSession.PositionState st) {
            long d = (long) (st.duration * 1000);
            long p = (long) (st.position * 1000);
            boolean jump = Math.abs(p - expectedPosition()) > 2000 || d != durationMs;
            durationMs = d;
            positionMs = p;
            rate = (float) st.playbackRate;
            positionAt = android.os.SystemClock.elapsedRealtime();
            if (jump) PlaybackService.refresh(ctx);
        }

        @Override
        public void onPlay(GeckoSession s, MediaSession ms) {
            Log.i(TAG, "gecko onPlay");
            media = ms;
            playing = true;
            updateActive();
            PlaybackService.start(ctx);
        }

        @Override
        public void onPause(GeckoSession s, MediaSession ms) {
            Log.i(TAG, "gecko onPause");
            playing = false;
            updateActive();
            PlaybackService.refresh(ctx);
        }
    }
}
