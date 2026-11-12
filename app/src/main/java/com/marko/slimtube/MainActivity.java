package com.marko.slimtube;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    /** Hosts that stay inside the app (YouTube + Google sign-in). Everything else opens in the browser. */
    private static final Pattern ALLOWED_HOST = Pattern.compile(
            "(^|\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|ytimg\\.com|gstatic\\.com"
                    + "|googleusercontent\\.com|google\\.[a-z]{2,3}(\\.[a-z]{2})?)$");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private android.widget.FrameLayout root;
    private GeckoView geckoView;
    private android.widget.LinearLayout tabBar;
    private GeckoSession session;
    private android.widget.LinearLayout column;
    private android.widget.LinearLayout miniBar;
    private android.widget.TextView miniTitle;
    private android.widget.ImageButton miniPlay;
    private android.widget.TextView fpsButton;
    private boolean mini;
    private boolean canGoBack;
    private GeckoSession.HistoryDelegate.HistoryList history;
    private boolean fullScreen;
    /** The page lives on in App when a new window opens: remember where it is. */
    private static String lastUrl = App.HOME;
    private String currentUrl = lastUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // GeckoView maps touches wrongly if it has padding itself, so the system-bar insets
        // are applied to a plain container around it instead.
        root = new android.widget.FrameLayout(this);
        column = new android.widget.LinearLayout(this);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        geckoView = new GeckoView(this);
        // YouTube's dark background until the page has drawn (instead of a white surface).
        geckoView.coverUntilFirstPaint(0xFF0F0F0F);
        buildNativePlayer();
        column.addView(playerBox, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(geckoView, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        buildMiniBar();
        column.addView(miniBar, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        tabBar = buildTabBar();
        column.addView(tabBar, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(column, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF0F0F0F);
        setContentView(root);
        applyInsets();
        attachSession();

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
        // A new window over a page that is already open: show its player / tab bar again.
        updateTabBar();
        updateNative(currentUrl);
        handleIntent(getIntent());
    }

    private void attachSession() {
        App.onDownload = downloadHandler;
        session = App.session(this);
        session.setNavigationDelegate(new NavDelegate());
        session.setHistoryDelegate(new GeckoSession.HistoryDelegate() {
            @Override
            public void onHistoryStateChange(GeckoSession s, GeckoSession.HistoryDelegate.HistoryList list) {
                history = list;
            }
        });
        session.setContentDelegate(new ContentDelegate());
        geckoView.setSession(session);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String url = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getDataString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = URL_IN_TEXT.matcher(text);
                if (m.find()) url = m.group();
            }
        }
        if (url != null && isAllowed(Uri.parse(url))) {
            session.loadUri(url);
        }
    }

    // ---- Back button, like the official app:
    //      fullscreen -> open menu/panel -> page history -> Home tab -> minimize (keeps playing) ----

    private void handleBack() {
        if (nativeFs) {
            setNativeFullscreen(false);
            return;
        }
        if (fullScreen) {
            session.exitFullScreen();
            return;
        }
        if (canGoBack && currentUrl != null && currentUrl.contains("#")) {
            // YouTube opened a menu / sheet and put it in the history (e.g. #bottom-sheet).
            session.goBack();
            return;
        }
        App.requestPageBack(handled -> {
            if (!handled) navigateBack();
        });
    }

    private void navigateBack() {
        // Several videos opened one after another: back goes straight to the page the first
        // one was opened from (Home, search, channel...) instead of through every video.
        if (canGoBack && isWatch(currentUrl) && history != null) {
            int cur = history.getCurrentIndex();
            if (cur > 0 && cur < history.size()) {
                int target = -1;
                for (int i = cur - 1; i >= 0; i--) {
                    String u = history.get(i).getUri();
                    if (!isWatch(u) && (u == null || !u.contains("#"))) { target = i; break; }
                }
                if (target < 0) {
                    session.loadUri(App.HOME);
                    return;
                }
                if (target < cur - 1) {
                    session.gotoHistoryIndex(target);
                    return;
                }
            }
        }
        if (canGoBack) {
            session.goBack();
        } else if (!isHome(currentUrl)) {
            session.loadUri(App.HOME);
        } else {
            moveTaskToBack(true);
        }
    }

    private static boolean isWatch(String url) {
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.startsWith("/watch");
    }

    private static boolean isHome(String url) {
        if (url == null) return true;
        Uri u = Uri.parse(url);
        String path = u.getPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack(); // Android 12 and older
    }

    // ---- Tab bar: YouTube hides its own bottom tabs on Shorts, so we show ours there ----

    private static final String[][] TABS = {
            {"Home", "https://m.youtube.com/"},
            {"Shorts", "https://m.youtube.com/shorts"},
            {"Subscriptions", "https://m.youtube.com/feed/subscriptions"},
            {"You", "https://m.youtube.com/feed/library"},
            {"Preuzmi", null},
    };
    private static final int[] TAB_ICONS = {
            R.drawable.ic_tab_home, R.drawable.ic_tab_shorts, R.drawable.ic_tab_subs, R.drawable.ic_tab_you,
            R.drawable.ic_tab_download,
    };

    private final java.util.function.Consumer<String> downloadHandler = this::openDownloads;

    /** Opens the "Preuzimanje" screen, filled in with the video that is open (if any). */
    void openDownloads(String pageUrl) {
        Intent i = new Intent(this, DownloadActivity.class);
        String page = pageUrl != null ? pageUrl : currentUrl;
        // Keep the whole page link when it belongs to a playlist (list=…), so the download
        // screen can offer the playlist too.
        String url = DownloadActivity.playlistUrl(page) != null ? page : DownloadActivity.videoUrl(page);
        if (url == null) url = DownloadActivity.playlistUrl(currentUrl) != null ? currentUrl : DownloadActivity.videoUrl(currentUrl);
        if (url != null) i.putExtra(DownloadActivity.EXTRA_URL, url);
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.widget.LinearLayout buildTabBar() {
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF0F0F0F);
        bar.setVisibility(View.GONE);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        for (int i = 0; i < TABS.length; i++) {
            final String url = TABS[i][1];
            final boolean isShorts = i == 1;
            final boolean isHomeTab = i == 0;
            android.widget.LinearLayout item = new android.widget.LinearLayout(this);
            item.setOrientation(android.widget.LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER);
            item.setBackgroundResource(ripple.resourceId);
            item.setContentDescription(TABS[i][0]);
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(TAB_ICONS[i]);
            item.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(24), dp(24)));
            android.widget.TextView label = new android.widget.TextView(this);
            label.setText(TABS[i][0]);
            label.setTextColor(0xFFF1F1F1);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
            label.setPadding(0, dp(2), 0, 0);
            label.setGravity(android.view.Gravity.CENTER);
            label.setSingleLine(true);
            item.addView(label, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            if (isShorts) label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            item.setOnClickListener(v -> {
                if (url == null) { openDownloads(currentUrl); return; }
                if (isShorts && isShorts(currentUrl)) return;
                if (isHomeTab) { session.loadUri(url); return; } // Home = always a fresh feed
                App.navigate(url, () -> session.loadUri(url));
            });
            bar.addView(item, new android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return bar;
    }

    private static boolean isShorts(String url) {
        if (url == null) return false;
        String path = Uri.parse(url).getPath();
        return path != null && path.startsWith("/shorts");
    }

    private void updateTabBar() {
        tabBar.setVisibility(!fullScreen && !nativeFs && isShorts(currentUrl) ? View.VISIBLE : View.GONE);
    }

    // ---- Keep the page out from under the status bar, nav bar and keyboard ----

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (fullScreen || nativeFs) {
                v.setPadding(0, 0, 0, 0);
            } else if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else {
                @SuppressWarnings("deprecation")
                int top = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation")
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(0, top, 0, bottom);
            }
            return insets;
        });
    }

    // ---- Fullscreen video ----

    @SuppressWarnings("deprecation")
    private void setFullScreen(boolean fs) {
        fullScreen = fs;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (fs) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fs
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        setRequestedOrientation(fs ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        root.requestApplyInsets();
        updateTabBar();
    }

    // ---- Navigation rules ----

    static boolean isAllowed(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        return ("https".equals(scheme) || "http".equals(scheme))
                && host != null && ALLOWED_HOST.matcher(host.toLowerCase()).find();
    }

    private void openExternally(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private class NavDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession s, boolean value) {
            canGoBack = value;
        }

        @Override
        public void onLocationChange(GeckoSession s, String url,
                                     java.util.List<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     Boolean hasUserGesture) {
            if (url != null) currentUrl = lastUrl = url;
            updateTabBar();
            updateNative(url);
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest req) {
            Uri uri = Uri.parse(req.uri);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            switch (scheme) {
                case "http":
                case "https":
                    if (isAllowed(uri)) {
                        if (req.target == TARGET_WINDOW_NEW) {
                            s.loadUri(req.uri); // no tabs: open "new window" links in place
                            return GeckoResult.deny();
                        }
                        return GeckoResult.allow();
                    }
                    openExternally(uri);
                    return GeckoResult.deny();
                case "about":
                case "data":
                case "blob":
                case "resource":
                case "moz-extension":
                    return GeckoResult.allow();
                default:
                    // vnd.youtube:, intent:, mailto: ... never hand YouTube links to other apps.
                    if (!scheme.startsWith("vnd.youtube") && !"intent".equals(scheme)) openExternally(uri);
                    return GeckoResult.deny();
            }
        }
    }

    private class ContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onFullScreen(GeckoSession s, boolean fs) {
            setFullScreen(fs);
        }

        @Override
        public void onCrash(GeckoSession s) {
            recoverSession();
        }

        @Override
        public void onKill(GeckoSession s) {
            recoverSession();
        }
    }

    private void recoverSession() {
        geckoView.releaseSession();
        App.resetSession();
        canGoBack = false;
        if (fullScreen) setFullScreen(false);
        attachSession();
    }

    // ---- Native video player (normal videos; Shorts stay in the page) ----

    private android.widget.FrameLayout playerBox;
    private androidx.media3.ui.PlayerView playerView;
    private android.widget.ImageView thumb;
    private android.widget.ProgressBar spinner;
    private boolean nativeFs;
    private boolean rotatedToFs;
    /** Videos the native player could not play: these use YouTube's web player. */
    private final java.util.Set<String> webOnly = new java.util.HashSet<>();
    private com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController> controllerFuture;

    private final androidx.media3.common.Player.Listener nativeListener = new androidx.media3.common.Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean playing) {
            playerView.setKeepScreenOn(playing);
            if (playing) spinner.setVisibility(View.GONE);
            updateMini();
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            updateMini();
        }

        @Override
        public void onMediaMetadataChanged(@androidx.annotation.NonNull androidx.media3.common.MediaMetadata m) {
            updateMini();
        }

        @Override
        public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
            if (nativeFs) applyFsOrientation();
        }
    };

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void buildNativePlayer() {
        playerBox = new android.widget.FrameLayout(this) {
            @Override
            protected void onMeasure(int w, int h) {
                if (nativeFs) {
                    super.onMeasure(w, h);
                } else {
                    int width = View.MeasureSpec.getSize(w);
                    super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY));
                }
            }
        };
        playerBox.setBackgroundColor(0xFF000000);
        playerBox.setVisibility(View.GONE);
        playerView = new androidx.media3.ui.PlayerView(this);
        playerView.setShutterBackgroundColor(0xFF000000);
        playerView.setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setShowNextButton(true);
        playerView.setShowPreviousButton(false);
        playerView.setControllerShowTimeoutMs(2500);
        playerView.setFullscreenButtonClickListener(this::setNativeFullscreen);
        // The page's own surface stays alive (and keeps its last picture) while hidden in full
        // screen; the video's surface must be drawn above it, or the old page shows through.
        if (playerView.getVideoSurfaceView() instanceof android.view.SurfaceView) {
            ((android.view.SurfaceView) playerView.getVideoSurfaceView()).setZOrderMediaOverlay(true);
        }
        playerBox.addView(playerView, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // The video's picture while its streams are looked up (about a second).
        thumb = new android.widget.ImageView(this);
        thumb.setVisibility(View.GONE); // shown by showThumb() while a new video loads
        thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        playerBox.addView(thumb, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        spinner = new android.widget.ProgressBar(this);
        spinner.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(dp(48), dp(48));
        sp.gravity = android.view.Gravity.CENTER;
        playerBox.addView(spinner, sp);

        // 30 / 60 fps switch, shown together with the player's controls.
        Streams.allow60 = getSharedPreferences("player", MODE_PRIVATE).getBoolean("fps60", false);
        fpsButton = new android.widget.TextView(this);
        fpsButton.setTextColor(0xFFFFFFFF);
        fpsButton.setTextSize(13);
        fpsButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        fpsButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        fpsButton.setBackgroundColor(0x66000000);
        fpsButton.setText(Streams.allow60 ? "60 fps" : "30 fps");
        fpsButton.setVisibility(View.GONE);
        fpsButton.setOnClickListener(v -> {
            Streams.allow60 = !Streams.allow60;
            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("fps60", Streams.allow60).apply();
            fpsButton.setText(Streams.allow60 ? "60 fps" : "30 fps");
            android.widget.Toast.makeText(this, Streams.allow60
                    ? "60 fps kad video to ima: glatkije, ali troši više baterije"
                    : "30 fps: štedi bateriju", android.widget.Toast.LENGTH_SHORT).show();
            NativePlayer.reload();
        });
        android.widget.FrameLayout.LayoutParams fp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        playerBox.addView(fpsButton, fp);
        playerView.setControllerVisibilityListener((androidx.media3.ui.PlayerView.ControllerVisibilityListener)
                visibility -> fpsButton.setVisibility(mini ? View.GONE : visibility));

        playerView.setPlayer(NativePlayer.get(this));
        NativePlayer.exo().addListener(nativeListener);
        NativePlayer.onFirstFrame = () -> {
            thumb.setVisibility(View.GONE);
            spinner.setVisibility(View.GONE);
        };
        NativePlayer.onNext = this::playNext;
        NativePlayer.onFailed = id -> {
            // e.g. age-restricted: let YouTube's own player have this one
            Log.w(App.TAG, "native failed, web player for " + id);
            long pos = NativePlayer.position();
            webOnly.add(id);
            NativePlayer.stop();
            if (nativeFs) setNativeFullscreen(false);
            showNative(false);
            App.setNative(false);
            if (id.equals(watchId(currentUrl))) {
                // same video, same moment, in YouTube's own player
                App.startWebPlayerSoon();
                session.loadUri("https://m.youtube.com/watch?v=" + id + (pos > 3000 ? "&t=" + (pos / 1000) + "s" : ""));
            }
        };
    }

    private void connectVideoService() {
        if (controllerFuture != null) return;
        androidx.media3.session.SessionToken token = new androidx.media3.session.SessionToken(this,
                new android.content.ComponentName(this, VideoService.class));
        controllerFuture = new androidx.media3.session.MediaController.Builder(this, token).buildAsync();
    }

    static String watchId(String url) {
        if (url == null) return null;
        Uri u = Uri.parse(url);
        String path = u.getPath();
        if (path == null || !path.startsWith("/watch") || !isAllowed(u)) return null;
        String v = u.getQueryParameter("v");
        return v != null && v.matches("[A-Za-z0-9_-]{11}") ? v : null;
    }

    private static long startMs(String url) {
        String t = Uri.parse(url).getQueryParameter("t");
        if (t == null) return 0;
        try {
            return Long.parseLong(t.replaceAll("s$", "")) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateNative(String url) {
        String id = watchId(url);
        if (id != null && !webOnly.contains(id)) {
            App.setNative(true);
            setMini(false);
            if (!id.equals(NativePlayer.currentId)) {
                showThumb(id);
                NativePlayer.play(this, id, startMs(url));
            }
            showNative(true);
        } else if (id == null && NativePlayer.currentId != null && !isShorts(url)) {
            // Left the video for another page: like the official app, it keeps playing in a
            // small player at the bottom (tap = back to the video, X = stop).
            if (nativeFs) setNativeFullscreen(false);
            setMini(true);
        } else {
            if (id != null) App.setNative(false); // this video uses the web player
            if (NativePlayer.currentId != null) NativePlayer.stop();
            if (nativeFs) setNativeFullscreen(false);
            setMini(false);
            showNative(false);
        }
    }

    private void buildMiniBar() {
        miniBar = new android.widget.LinearLayout(this);
        miniBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        miniBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        miniBar.setBackgroundColor(0xFF212121);
        miniBar.setVisibility(View.GONE);
        miniBar.setOnClickListener(v -> {
            String id = NativePlayer.currentId;
            if (id == null) return;
            String url = "https://m.youtube.com/watch?v=" + id;
            App.navigate(url, () -> session.loadUri(url));
        });
        miniTitle = new android.widget.TextView(this);
        miniTitle.setTextColor(0xFFFFFFFF);
        miniTitle.setTextSize(14);
        miniTitle.setMaxLines(2);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTitle.setPadding(dp(10), 0, dp(4), 0);
        miniBar.addView(miniTitle, new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        miniPlay = new android.widget.ImageButton(this);
        miniPlay.setBackgroundResource(ripple.resourceId);
        miniPlay.setColorFilter(0xFFFFFFFF);
        miniPlay.setContentDescription("Pusti / pauziraj");
        miniPlay.setOnClickListener(v -> {
            androidx.media3.exoplayer.ExoPlayer p = NativePlayer.exo();
            if (p != null) p.setPlayWhenReady(!p.getPlayWhenReady());
        });
        miniBar.addView(miniPlay, new android.widget.LinearLayout.LayoutParams(dp(48), dp(48)));
        android.widget.ImageButton close = new android.widget.ImageButton(this);
        close.setBackgroundResource(ripple.resourceId);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(0xFFFFFFFF);
        close.setContentDescription("Zatvori");
        close.setOnClickListener(v -> {
            NativePlayer.stop();
            setMini(false);
            showNative(false);
        });
        miniBar.addView(close, new android.widget.LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void updateMini() {
        if (!mini) return;
        androidx.media3.exoplayer.ExoPlayer p = NativePlayer.exo();
        CharSequence t = p != null ? p.getMediaMetadata().title : null;
        miniTitle.setText(t != null ? t : "");
        boolean on = p != null && p.getPlayWhenReady();
        miniPlay.setImageResource(on ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    /** Moves the video picture into the small bar at the bottom, or back to the top. */
    private void setMini(boolean on) {
        if (mini == on) return;
        mini = on;
        ((android.view.ViewGroup) playerBox.getParent()).removeView(playerBox);
        if (on) {
            playerView.hideController();
            playerView.setUseController(false);
            fpsButton.setVisibility(View.GONE);
            miniBar.addView(playerBox, 0, new android.widget.LinearLayout.LayoutParams(dp(114), dp(64)));
            playerBox.setVisibility(View.VISIBLE);
            miniBar.setVisibility(View.VISIBLE);
            updateMini();
        } else {
            miniBar.setVisibility(View.GONE);
            column.addView(playerBox, 0, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            playerView.setUseController(true);
        }
    }

    private void showNative(boolean on) {
        playerBox.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void showThumb(String id) {
        thumb.setImageDrawable(null);
        thumb.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try (java.io.InputStream in = new java.net.URL("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg").openStream()) {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
                runOnUiThread(() -> {
                    if (id.equals(NativePlayer.currentId) && thumb.getVisibility() == View.VISIBLE) thumb.setImageBitmap(b);
                });
            } catch (Exception ignored) {
            }
        }, "thumb").start();
    }

    /** "Next" or the end of a video: what YouTube's autoplay would open (playlist first). */
    private void playNext() {
        String id = NativePlayer.currentId;
        App.requestNext(found -> {
            java.util.List<String> candidates = new java.util.ArrayList<>();
            if (found != null) candidates.add(found);
            if (id != null) {
                try {
                    Streams cur = Streams.get(id); // already looked up: no waiting
                    if (cur.next != null) candidates.add(cur.next);
                    candidates.addAll(cur.related);
                } catch (Exception ignored) {
                }
            }
            if (candidates.isEmpty()) return;
            if (App.foreground && !mini) {
                // On the video page: YouTube's own player takes over one that can't play here.
                startNext(candidates.get(0));
                return;
            }
            // Listening off screen or in the small player: skip suggestions that can't be
            // played here (e.g. made for kids), or the music would just stop.
            new Thread(() -> {
                for (String c : candidates) {
                    String nid = watchId(c.replace("://www.youtube.com/", "://m.youtube.com/"));
                    if (nid == null || webOnly.contains(nid)) continue;
                    try {
                        Streams.get(nid);
                        runOnUiThread(() -> startNext(c));
                        return;
                    } catch (Exception e) {
                        Log.i(App.TAG, "next: skipping " + nid + " (" + e.getMessage() + ")");
                    }
                }
            }, "next").start();
        });
    }

    private void startNext(String url) {
        String u = url.replace("://www.youtube.com/", "://m.youtube.com/");
        Log.i(App.TAG, "next: " + u + (mini ? " (small player)" : ""));
        // Start it here: in the background the page is inactive and may never get to
        // switching pages itself, and then nothing would play.
        String nid = watchId(u);
        if (nid != null && !webOnly.contains(nid)) {
            showThumb(nid);
            NativePlayer.play(this, nid, 0);
        }
        // The small player keeps the page the user is on (Home, search...).
        if (!mini) App.navigate(u, () -> session.loadUri(u));
    }

    @SuppressWarnings("deprecation")
    private void setNativeFullscreen(boolean fs) {
        if (nativeFs == fs) return;
        nativeFs = fs;
        playerView.setFullscreenButtonState(fs);
        geckoView.setVisibility(fs ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (fs) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fs
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        if (fs) {
            if (rotatedToFs) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            else applyFsOrientation();
        } else if (rotatedToFs) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else {
            // Back to upright; follow the sensor again a moment later.
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            geckoView.postDelayed(() -> {
                if (!nativeFs) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }, 3000);
        }
        if (!fs) rotatedToFs = false;
        playerBox.requestLayout();
        root.requestApplyInsets();
        updateTabBar();
    }

    private void applyFsOrientation() {
        if (rotatedToFs) return;
        androidx.media3.exoplayer.ExoPlayer exo = NativePlayer.exo();
        boolean vertical = exo != null && exo.getVideoSize().height > exo.getVideoSize().width;
        setRequestedOrientation(vertical ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // Turning the phone sideways on a video goes to full screen and back, like the app.
        boolean land = c.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (land && !nativeFs && playerBox.getVisibility() == View.VISIBLE) {
            rotatedToFs = true;
            setNativeFullscreen(true);
        } else if (!land && nativeFs && rotatedToFs) {
            setNativeFullscreen(false);
        }
    }

    /** Windows of this app on screen now (normally one, briefly two). */
    private static int started;

    @Override
    protected void onStart() {
        super.onStart();
        started++;
        App.setForeground(true);
        NativePlayer.setVideoWanted(true);
        NativePlayer.onScreen = true;
        connectVideoService();
        UpdateBanner.check(this, root); // "new version" bar, only while the app is open
        Updater.check(this);
    }

    /**
     * Out of sight the page is made inactive: Gecko then stops drawing it, stops decoding the
     * picture of a video that keeps playing (only the sound goes on) and slows its timers.
     * Before, the page stayed active whenever something had been playing, and used ~18% of a
     * core even after pausing, ~60% while playing.
     */
    @Override
    protected void onStop() {
        super.onStop();
        // Opening the app again can create a new window before the old one is closed: only
        // the last one closing means the app left the screen.
        started = Math.max(0, started - 1);
        if (started == 0) {
            App.setForeground(false);
            // Only the sound is needed now (it keeps playing through VideoService).
            NativePlayer.setVideoWanted(false);
            NativePlayer.onScreen = false;
        }
    }

    @Override
    protected void onDestroy() {
        if (App.onDownload == downloadHandler) App.onDownload = null;
        if (NativePlayer.exo() != null) NativePlayer.exo().removeListener(nativeListener);
        if (playerView != null) playerView.setPlayer(null);
        if (controllerFuture != null) androidx.media3.session.MediaController.releaseFuture(controllerFuture);
        if (geckoView != null) geckoView.releaseSession();
        super.onDestroy();
    }
}
