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
    private boolean canGoBack;
    private boolean fullScreen;
    private String currentUrl = App.HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // GeckoView maps touches wrongly if it has padding itself, so the system-bar insets
        // are applied to a plain container around it instead.
        root = new android.widget.FrameLayout(this);
        android.widget.LinearLayout column = new android.widget.LinearLayout(this);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        geckoView = new GeckoView(this);
        column.addView(geckoView, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        handleIntent(getIntent());
    }

    private void attachSession() {
        session = App.session(this);
        session.setNavigationDelegate(new NavDelegate());
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
        if (canGoBack) {
            session.goBack();
        } else if (!isHome(currentUrl)) {
            session.loadUri(App.HOME);
        } else {
            moveTaskToBack(true);
        }
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
    };
    private static final int[] TAB_ICONS = {
            R.drawable.ic_tab_home, R.drawable.ic_tab_shorts, R.drawable.ic_tab_subs, R.drawable.ic_tab_you,
    };

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
                if (isShorts && isShorts(currentUrl)) return;
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
        tabBar.setVisibility(!fullScreen && isShorts(currentUrl) ? View.VISIBLE : View.GONE);
    }

    // ---- Keep the page out from under the status bar, nav bar and keyboard ----

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (fullScreen) {
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
            if (url != null) currentUrl = url;
            updateTabBar();
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

    /**
     * GeckoView marks the page hidden when our window goes away (screen off / home), and YouTube
     * pauses hidden pages. While something is playing, keep the page "active" so it continues.
     */
    @Override
    protected void onStart() {
        super.onStart();
        App.visible = true;
        Updater.check(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.visible = false;
        geckoView.postDelayed(() -> {
            if (!App.visible) Updater.installIfIdle(this);
        }, 3000);
        geckoView.postDelayed(() -> {
            if (App.playing && App.hasSession()) session.setActive(true);
        }, 300);
    }

    @Override
    protected void onDestroy() {
        if (geckoView != null) geckoView.releaseSession();
        super.onDestroy();
    }
}
