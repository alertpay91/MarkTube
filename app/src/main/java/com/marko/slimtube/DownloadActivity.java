package com.marko.slimtube;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Preuzimanje" tab: download one video or its audio with yt-dlp. Every download has to be
 * confirmed with "I am allowed to download this"; the first use shows the full notice.
 */
public class DownloadActivity extends Activity {
    static final String EXTRA_URL = "url";
    private static final int BG = 0xFF0F0F0F, CARD = 0xFF212121, TEXT = 0xFFF1F1F1, DIM = 0xFFAAAAAA;
    private static final int ACCENT = 0xFF3EA6FF, WARN = 0xFFFFB74D;
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::renderJobs;

    private EditText urlField;
    private TextView infoView;
    private RadioGroup modeGroup;
    private CheckBox allowed;
    private Button downloadButton;
    private LinearLayout jobList;

    private String checkedUrl;
    private VideoInfo info;
    private int infoRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(col);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
            } else {
                @SuppressWarnings("deprecation") int top = insets.getSystemWindowInsetTop();
                @SuppressWarnings("deprecation") int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(0, top, 0, bottom);
            }
            return insets;
        });

        // Header with a back arrow.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("←", 24, TEXT);
        back.setPadding(0, dp(4), dp(16), dp(4));
        back.setOnClickListener(v -> finish());
        back.setContentDescription("Nazad");
        header.addView(back);
        TextView title = text("Preuzimanje", 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        col.addView(header);

        // Always-visible notice.
        TextView notice = text("⚠  Na sopstvenu odgovornost. Preuzimaj samo snimke koje smeš da preuzmeš: "
                + "svoje snimke, snimke čiji je autor dozvolio preuzimanje ili sadržaj sa slobodnom licencom "
                + "(npr. Creative Commons). Preuzimanje tuđe muzike, filmova i snimaka bez dozvole može da krši "
                + "autorska prava i YouTube uslove korišćenja.", 13, WARN);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        notice.setBackground(round(0x33FFB74D, 10));
        addSpaced(col, notice, 12);

        urlField = new EditText(this);
        urlField.setHint("Link snimka (youtube.com/watch?v=…)");
        urlField.setHintTextColor(DIM);
        urlField.setTextColor(TEXT);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setBackgroundTintList(ColorStateList.valueOf(DIM));
        addSpaced(col, urlField, 16);

        LinearLayout row = new LinearLayout(this);
        Button paste = button("Nalepi link", false);
        paste.setOnClickListener(v -> pasteLink());
        row.addView(paste, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button check = button("Proveri snimak", false);
        check.setOnClickListener(v -> loadInfo(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(8);
        row.addView(check, lp);
        addSpaced(col, row, 8);

        infoView = text("", 14, TEXT);
        infoView.setPadding(dp(12), dp(10), dp(12), dp(10));
        infoView.setBackground(round(CARD, 10));
        infoView.setVisibility(View.GONE);
        addSpaced(col, infoView, 12);

        modeGroup = new RadioGroup(this);
        modeGroup.addView(radio(Downloads.VIDEO_720, "Video, do 720p (MP4)"));
        modeGroup.addView(radio(Downloads.VIDEO_1080, "Video, do 1080p (MP4)"));
        modeGroup.addView(radio(Downloads.AUDIO, "Samo zvuk (M4A)"));
        modeGroup.check(prefs().getInt("mode", Downloads.VIDEO_720));
        modeGroup.setOnCheckedChangeListener((g, id) -> prefs().edit().putInt("mode", id).apply());
        addSpaced(col, modeGroup, 12);

        allowed = new CheckBox(this);
        allowed.setText("Imam pravo da preuzmem ovaj snimak i preuzimam ga na sopstvenu odgovornost.");
        allowed.setTextColor(TEXT);
        allowed.setButtonTintList(ColorStateList.valueOf(ACCENT));
        allowed.setOnCheckedChangeListener((b, c) -> updateButton());
        addSpaced(col, allowed, 8);

        downloadButton = button("Preuzmi", true);
        downloadButton.setOnClickListener(v -> startDownload());
        addSpaced(col, downloadButton, 8);

        TextView where = text("Fajlovi se čuvaju u folderu Download/MarkTube.", 12, DIM);
        addSpaced(col, where, 6);

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView lh = text("Preuzimanja", 18, TEXT);
        lh.setTypeface(Typeface.DEFAULT_BOLD);
        listHeader.addView(lh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = text("Očisti završena", 13, ACCENT);
        clear.setPadding(dp(8), dp(8), 0, dp(8));
        clear.setOnClickListener(v -> { Downloads.clearFinished(); renderJobs(); });
        listHeader.addView(clear);
        addSpaced(col, listHeader, 24);

        jobList = new LinearLayout(this);
        jobList.setOrientation(LinearLayout.VERTICAL);
        addSpaced(col, jobList, 4);

        urlField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!s.toString().trim().equals(checkedUrl)) {
                    info = null;
                    allowed.setChecked(false);
                    infoView.setVisibility(View.GONE);
                    updateButton();
                }
            }
        });

        handleIntent(getIntent());
        updateButton();
        if (!prefs().getBoolean("noticeAccepted", false)) showNotice();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null) {
                Matcher m = URL_IN_TEXT.matcher(t);
                if (m.find()) url = m.group();
            }
        }
        url = videoUrl(url);
        if (url != null) {
            urlField.setText(url);
            loadInfo(false);
        }
    }

    /** Keeps only links to a single video or short; everything else (home, channels…) is ignored. */
    static String videoUrl(String url) {
        if (url == null) return null;
        Uri u = Uri.parse(url.trim());
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String path = u.getPath() == null ? "" : u.getPath();
        if (host.endsWith("youtu.be") && path.length() > 1) return "https://youtu.be" + path;
        if (!host.endsWith("youtube.com")) return null;
        if (path.startsWith("/watch") && u.getQueryParameter("v") != null) {
            return "https://www.youtube.com/watch?v=" + u.getQueryParameter("v");
        }
        Matcher m = Pattern.compile("^/(shorts|live)/([^/?#]+)").matcher(path);
        if (m.find()) return "https://www.youtube.com/watch?v=" + m.group(2);
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderJobs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    // ---- Notice shown before the first download ----

    private void showNotice() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Preuzimanje na sopstvenu odgovornost")
                .setMessage("MarkTube može da preuzme snimak ili zvuk pomoću programa yt-dlp.\n\n"
                        + "Smeš da preuzimaš samo ono što imaš pravo da preuzmeš, na primer:\n"
                        + "• svoje snimke,\n"
                        + "• snimke čiji je autor izričito dozvolio preuzimanje,\n"
                        + "• sadržaj sa slobodnom licencom (npr. Creative Commons) ili u javnom domenu.\n\n"
                        + "Preuzimanje tuđeg sadržaja bez dozvole može da krši autorska prava i YouTube "
                        + "uslove korišćenja. Za to što preuzmeš i kako to koristiš odgovaraš ti, a ne "
                        + "autori aplikacije. Zaštićeni (DRM) i plaćeni sadržaji se ne preuzimaju.")
                .setCancelable(false)
                .setPositiveButton("Razumem", (d, w) -> prefs().edit().putBoolean("noticeAccepted", true).apply())
                .setNegativeButton("Ne, hvala", (d, w) -> finish())
                .show();
    }

    // ---- Video info (title, channel, licence) ----

    private void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (t == null) return;
        Matcher m = URL_IN_TEXT.matcher(t);
        urlField.setText(m.find() ? m.group() : t.toString().trim());
        loadInfo(true);
    }

    private void loadInfo(boolean showErrors) {
        String raw = urlField.getText().toString().trim();
        String url = videoUrl(raw);
        if (url == null) {
            if (showErrors) showInfo("Ovo nije link ka jednom snimku. Otvori snimak pa dodirni Preuzmi, "
                    + "ili nalepi link oblika youtube.com/watch?v=…", false);
            return;
        }
        checkedUrl = raw;
        info = null;
        updateButton();
        int req = ++infoRequest;
        showInfo("Proveravam snimak… (prvi put traje malo duže)", false);
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                VideoInfo vi = Downloads.info(app, url);
                main.post(() -> {
                    if (req != infoRequest || isDestroyed()) return;
                    info = vi;
                    showInfo(describe(vi), true);
                    updateButton();
                });
            } catch (Throwable t) {
                main.post(() -> {
                    if (req != infoRequest || isDestroyed()) return;
                    showInfo("Ne mogu da proverim snimak: " + Downloads.shortError(t), false);
                });
            }
        }, "dl-info").start();
    }

    private static String describe(VideoInfo vi) {
        StringBuilder s = new StringBuilder();
        s.append(vi.getTitle() == null ? "(bez naslova)" : vi.getTitle());
        if (vi.getUploader() != null) s.append("\n").append(vi.getUploader());
        int d = vi.getDuration();
        if (d > 0) s.append(String.format(java.util.Locale.ROOT, "  ·  %d:%02d", d / 60, d % 60));
        String lic = vi.getLicense();
        s.append("\n\nLicenca: ");
        if (lic != null && lic.toLowerCase().contains("creative commons")) {
            s.append(lic).append(" — autor dozvoljava ponovno korišćenje (navedi autora).");
        } else {
            s.append(lic != null && !lic.isEmpty() ? lic : "standardna YouTube licenca")
                    .append(" — preuzmi samo ako je snimak tvoj ili imaš dozvolu autora.");
        }
        return s.toString();
    }

    private void showInfo(String text, boolean ok) {
        infoView.setText(text);
        infoView.setTextColor(ok ? TEXT : DIM);
        infoView.setVisibility(View.VISIBLE);
    }

    private void updateButton() {
        boolean ok = info != null && allowed.isChecked();
        downloadButton.setEnabled(ok);
        downloadButton.setAlpha(ok ? 1f : 0.4f);
    }

    private void startDownload() {
        if (info == null || !allowed.isChecked()) return;
        String url = videoUrl(urlField.getText().toString());
        if (url == null) return;
        Downloads.enqueue(this, url, info.getTitle(), modeGroup.getCheckedRadioButtonId());
        allowed.setChecked(false);
        renderJobs();
    }

    // ---- Job list ----

    private void renderJobs() {
        main.removeCallbacks(tick);
        jobList.removeAllViews();
        if (Downloads.jobs.isEmpty()) {
            jobList.addView(text("Još ništa nije preuzeto.", 14, DIM));
        }
        for (Downloads.Job j : Downloads.jobs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(round(CARD, 10));
            TextView t = text(j.title + (j.mode == Downloads.AUDIO ? "  (zvuk)" : ""), 14, TEXT);
            t.setMaxLines(2);
            card.addView(t);
            if (j.state == Downloads.RUNNING) {
                ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                pb.setProgressTintList(ColorStateList.valueOf(ACCENT));
                pb.setIndeterminate(j.progress < 0);
                pb.setMax(100);
                pb.setProgress(Math.round(Math.max(0, j.progress)));
                card.addView(pb);
            }
            int color = j.state == Downloads.FAILED ? 0xFFFF8A80 : DIM;
            card.addView(text(j.status, 12, color));
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            if (j.active()) {
                TextView c = text("Otkaži", 13, ACCENT);
                c.setPadding(dp(12), dp(8), 0, 0);
                c.setOnClickListener(v -> { Downloads.cancel(j); renderJobs(); });
                actions.addView(c);
            } else if (j.state == Downloads.DONE && j.result != null) {
                TextView o = text("Otvori", 13, ACCENT);
                o.setPadding(dp(12), dp(8), 0, 0);
                o.setOnClickListener(v -> open(j));
                actions.addView(o);
            }
            if (actions.getChildCount() > 0) card.addView(actions);
            addSpaced(jobList, card, 8);
        }
        if (Downloads.anyActive()) main.postDelayed(tick, 700);
    }

    private void open(Downloads.Job j) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(j.result, j.mode == Downloads.AUDIO ? "audio/*" : "video/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    // ---- Tiny view helpers ----

    private SharedPreferences prefs() {
        return getSharedPreferences("downloads", MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFF0F0F0F : TEXT);
        b.setBackground(round(primary ? ACCENT : CARD, 20));
        return b;
    }

    private RadioButton radio(int id, String s) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(s);
        r.setTextColor(TEXT);
        r.setButtonTintList(ColorStateList.valueOf(ACCENT));
        return r;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void addSpaced(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }
}
