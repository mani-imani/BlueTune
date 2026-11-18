package ir.maniimani.geekplay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * GeekPlay — a pure-black Bluetooth music screen for the car head unit.
 */
public class MainActivity extends Activity {

    // ---- colours (night-first: nothing brighter than it needs to be)
    private static final int C_TEXT = Color.rgb(236, 236, 238);
    private static final int C_TEXT_2 = Color.rgb(142, 142, 150);
    private static final int C_TEXT_3 = Color.rgb(84, 84, 92);
    private static final int C_ICON = Color.rgb(205, 205, 212);
    private static final int C_SURFACE = Color.rgb(14, 14, 16);
    private static final int C_STROKE = Color.rgb(30, 30, 34);
    private static final int C_RIPPLE = Color.argb(46, 255, 255, 255);

    private static final long POLL_MS = 400;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread pollThread;
    private Handler poll;
    private final CarBt car = new CarBt();

    private Typeface fRegular, fMedium, fBold, fBlack;

    // views
    private FrameLayout root;
    private GlowView glow;
    private LinearLayout content, info, controls;
    private ArtworkView art;
    private TextView chipText, title, artist, counter, sourcePill;
    private ImageView chipIcon;
    private View chipDot;
    private WaveView wave;
    private ImageButton btnPrev, btnNext, btnMoon;
    private PlayButton btnPlay;
    private View dimOverlay;
    private Chrome chrome;
    private TextView clock;

    // state
    private CarBt.State state;
    private String trackKey = null;
    private boolean playingUi = false;
    private boolean optimisticUntilSet = false;
    private long optimisticUntil = 0;
    private Art.Palette palette = Art.NEUTRAL;
    private boolean dim;
    private int lastW = -1, lastH = -1;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        fRegular = font("vazirmatn_Regular");
        fMedium = font("vazirmatn_Medium");
        fBold = font("vazirmatn_Bold");
        fBlack = font("vazirmatn_Black");

        SharedPreferences prefs = getSharedPreferences("geekplay", MODE_PRIVATE);
        dim = prefs.getBoolean("dim", false);

        buildUi();
        setContentView(root);
        applyPalette(Art.NEUTRAL, false);
        showDisconnected(false);
        setDim(dim, false);

        pollThread = new HandlerThread("car-poll");
        pollThread.start();
        poll = new Handler(pollThread.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        chrome.start();
        poll.post(pollTask);
        title.setSelected(true);
    }

    @Override
    protected void onPause() {
        poll.removeCallbacks(pollTask);
        chrome.stop();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) chrome.hide();
    }

    @Override
    protected void onDestroy() {
        pollThread.quitSafely();
        super.onDestroy();
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            final CarBt.State s = car.read();
            main.post(() -> apply(s));
            poll.postDelayed(this, POLL_MS);
        }
    };

    // ================================================================== state → UI

    private void apply(CarBt.State s) {
        state = s;
        if (!s.serviceOk) {
            showServiceError();
            return;
        }
        if (!s.connected) {
            showDisconnected(true);
            return;
        }

        setControlsEnabled(true);
        chipDot.setBackground(dot(palette.accent));
        chipIcon.setImageTintList(ColorStateList.valueOf(palette.accent));
        chipText.setText(s.deviceName.isEmpty() ? "بلوتوث وصله" : s.deviceName);

        boolean hasSong = !Art.isBlank(s.title) || !Art.isBlank(s.artist);
        String key = s.title + "\u0001" + s.artist;
        if (!key.equals(trackKey)) {
            trackKey = key;
            art.setSong(s.title, s.artist);
            applyPalette(Art.paletteFor(s.title, s.artist), true);
            if (hasSong) {
                setTextAnimated(title, Art.isBlank(s.title) ? "بدون عنوان" : s.title);
                setTextAnimated(artist, Art.isBlank(s.artist) ? "" : s.artist);
            } else {
                setTextAnimated(title, "آماده‌ی پخش");
                setTextAnimated(artist, "یه آهنگ رو روی گوشی پخش کن");
            }
        }

        if (s.count > 0 && s.index > 0) {
            counter.setVisibility(View.VISIBLE);
            counter.setText("آهنگ " + fa(s.index) + " از " + fa(s.count));
        } else {
            counter.setVisibility(View.GONE);
        }

        boolean playing = s.playing;
        if (optimisticUntilSet && SystemClock.elapsedRealtime() < optimisticUntil) {
            playing = playingUi; // keep the instant feedback until the head unit catches up
        } else {
            optimisticUntilSet = false;
        }
        setPlaying(playing);

        boolean wrongSource = s.source != 0 && s.source != CarBt.APP_ID_BT_MUSIC;
        sourcePill.setVisibility(wrongSource ? View.VISIBLE : View.GONE);

        wave.animate().alpha(hasSong ? 1f : 0.35f).setDuration(300).start();
    }

    private void showDisconnected(boolean animate) {
        state = state == null ? new CarBt.State() : state;
        setControlsEnabled(false);
        chipDot.setBackground(dot(C_TEXT_3));
        chipIcon.setImageTintList(ColorStateList.valueOf(C_TEXT_3));
        chipText.setText("وصل نیست");
        if (!"__disc".equals(trackKey)) {
            trackKey = "__disc";
            art.setSong("", "");
            applyPalette(Art.NEUTRAL, animate);
            setTextAnimated(title, "گوشی وصل نیست");
            setTextAnimated(artist, "بلوتوث آیفون رو روشن کن تا خودکار وصل بشه");
        }
        counter.setVisibility(View.GONE);
        sourcePill.setVisibility(View.GONE);
        setPlaying(false);
        wave.setAlpha(0.35f);
    }

    private void showServiceError() {
        setControlsEnabled(false);
        chipDot.setBackground(dot(Color.rgb(160, 70, 70)));
        chipIcon.setImageTintList(ColorStateList.valueOf(C_TEXT_3));
        chipText.setText("خطای اتصال");
        if (!"__err".equals(trackKey)) {
            trackKey = "__err";
            art.setSong("", "");
            applyPalette(Art.NEUTRAL, true);
            setTextAnimated(title, "سرویس مانیتور در دسترس نیست");
            setTextAnimated(artist, "سیستم مانیتور اجازه‌ی اتصال به بلوتوث رو نداد");
        }
        counter.setVisibility(View.GONE);
        sourcePill.setVisibility(View.GONE);
        setPlaying(false);
        wave.setAlpha(0.35f);
    }

    private void setPlaying(boolean playing) {
        if (playing == playingUi && btnPlay.getTag() != null) return;
        boolean first = btnPlay.getTag() == null && !playing && !playingUi;
        playingUi = playing;
        btnPlay.setTag(Boolean.TRUE);
        btnPlay.setPlaying(playing, !first);
        btnPlay.setContentDescription(playing ? "توقف" : "پخش");
        wave.setPlaying(playing);
        float s = playing ? 1f : 0.93f;
        art.animate().scaleX(s).scaleY(s).setDuration(550)
                .setInterpolator(playing ? new OvershootInterpolator(1.4f) : new DecelerateInterpolator()).start();
        glow.setActive(playing);
    }

    private void applyPalette(Art.Palette p, boolean animate) {
        palette = p;
        glow.animateTo(p.glow);
        wave.animateColor(p.accent);
        btnPlay.animateRing(p.accent);
    }

    private void setControlsEnabled(boolean on) {
        for (View v : new View[]{btnPrev, btnPlay, btnNext}) {
            v.setEnabled(on);
            v.animate().alpha(on ? 1f : 0.28f).setDuration(300).start();
        }
    }

    // ================================================================== actions

    private void onPlayPause() {
        poll.post(car::playPause);
        setPlaying(!playingUi);
        optimisticUntilSet = true;
        optimisticUntil = SystemClock.elapsedRealtime() + 1500;
        pulse(btnPlay);
    }

    private void onNext() {
        poll.post(car::next);
        pulse(btnNext);
    }

    private void onPrev() {
        poll.post(car::prev);
        pulse(btnPrev);
    }

    private void onSwitchSource() {
        poll.post(car::enterBtMusicSource);
        // entering the BT source opens the vendor app; come straight back
        main.postDelayed(() -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK)), 900);
    }

    private void setDim(boolean on, boolean animate) {
        dim = on;
        getSharedPreferences("geekplay", MODE_PRIVATE).edit().putBoolean("dim", on).apply();
        float target = on ? 0.5f : 0f;
        if (animate) dimOverlay.animate().alpha(target).setDuration(400).start();
        else dimOverlay.setAlpha(target);
        btnMoon.setImageTintList(ColorStateList.valueOf(on ? palette.accent : C_TEXT_3));
    }

    // ================================================================== UI building

    private void buildUi() {
        root = new FrameLayout(this);
        chrome = new Chrome(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(Color.BLACK);

        glow = new GlowView(this);
        root.addView(glow, match());

        content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        root.addView(content, match());

        // artwork
        art = new ArtworkView(this, fBlack);
        content.addView(art, new LinearLayout.LayoutParams(dp(360), dp(360)));

        // info column
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        info.addView(buildChip(), wrapCentered(0));

        title = text(fBlack, 34, C_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setFadingEdgeLength(dp(24));
        title.setSelected(true);
        info.addView(title, fullWidth(dp(22)));

        artist = text(fMedium, 19, C_TEXT_2);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(artist, fullWidth(dp(6)));

        counter = text(fRegular, 13, C_TEXT_3);
        info.addView(counter, fullWidth(dp(6)));

        // live waveform (decorative)
        wave = new WaveView(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        wlp.topMargin = dp(26);
        info.addView(wave, wlp);

        // controls
        controls = new LinearLayout(this);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        controls.setGravity(Gravity.CENTER);
        btnPrev = roundButton(R.drawable.ic_prev, 76, 36, false);
        btnPlay = new PlayButton(this, C_TEXT, C_RIPPLE);
        btnNext = roundButton(R.drawable.ic_next, 76, 36, false);
        btnPrev.setContentDescription("قبلی");
        btnNext.setContentDescription("بعدی");
        btnPrev.setOnClickListener(v -> onPrev());
        btnPlay.setOnClickListener(v -> onPlayPause());
        btnNext.setOnClickListener(v -> onNext());
        controls.addView(btnPrev, sq(76, 0));
        controls.addView(btnPlay, sq(100, 40));
        controls.addView(btnNext, sq(76, 40));
        info.addView(controls, wrapCentered(dp(20)));

        // source pill
        sourcePill = text(fMedium, 14, C_TEXT_2);
        sourcePill.setText("پخش از بلوتوث");
        sourcePill.setPadding(dp(22), dp(9), dp(22), dp(9));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(C_SURFACE);
        pill.setStroke(dp(1), C_STROKE);
        pill.setCornerRadius(dp(100));
        sourcePill.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), pill, null));
        sourcePill.setOnClickListener(v -> onSwitchSource());
        sourcePill.setVisibility(View.GONE);
        info.addView(sourcePill, wrapCentered(dp(18)));

        // night-dim toggle (top-right)
        btnMoon = new ImageButton(this);
        btnMoon.setImageResource(R.drawable.ic_moon);
        btnMoon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btnMoon.setPadding(dp(14), dp(14), dp(14), dp(14));
        btnMoon.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), null, new ShapeDrawable(new OvalShape())));
        btnMoon.setContentDescription("حالت شب");
        btnMoon.setOnClickListener(v -> setDim(!dim, true));
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.LEFT);
        mlp.topMargin = dp(16);
        mlp.leftMargin = dp(12);
        root.addView(btnMoon, mlp);

        clock = chrome.createClock(fBold, 20, C_TEXT_2);
        root.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // dim overlay — not clickable, so touches pass through
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        dimOverlay.setClickable(false);
        dimOverlay.setFocusable(false);
        root.addView(dimOverlay, match());

        // tap along the very top edge to bring the head unit's bar back for a moment
        chrome.addTopStrip(root, dp(12));

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                v.post(() -> relayout(w, h));
            }
        });
    }

    /** Landscape: art left, info right. Portrait: stacked. */
    private void relayout(int w, int h) {
        boolean landscape = w >= h;
        int pad = landscape ? dp(44) : dp(28);
        content.setPadding(pad, pad, pad, pad);
        content.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        // Landscape: controls on the left (driver's side in a left-hand-drive car), cover on the right.
        // Portrait: cover on top.
        content.removeAllViews();
        if (landscape) {
            content.addView(info);
            content.addView(art);
        } else {
            content.addView(art);
            content.addView(info);
        }

        int artSize;
        if (landscape) {
            artSize = (int) Math.min(h - 2f * pad - dp(44), w * 0.40f); // leave room for the clock
        } else {
            artSize = (int) Math.min(w - 2f * pad, h * 0.40f - dp(44));
        }
        artSize = Math.max(artSize, dp(160));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(artSize, artSize);
        art.setLayoutParams(alp);

        LinearLayout.LayoutParams ilp;
        if (landscape) {
            ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.rightMargin = dp(48);
        } else {
            ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(28);
        }
        info.setLayoutParams(ilp);

        // Fit the info column to the screen: measure at full size, then scale every
        // size (text, gaps, buttons) down together if it doesn't fit. Buttons stay
        // perfectly square, so the play ring is always a true circle.
        applyScale(1f);
        int infoW = landscape ? (w - 2 * pad - artSize - ilp.rightMargin) : (w - 2 * pad);
        int availH = landscape ? (h - 2 * pad) : (h - 2 * pad - artSize - ilp.topMargin);
        info.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, infoW), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int need = info.getMeasuredHeight();
        if (need > availH && need > 0) {
            float k = Math.max(0.55f, availH / (float) need * 0.97f);
            applyScale(k);
        }

        content.post(() -> {
            float cx = (art.getLeft() + art.getWidth() / 2f) / (float) Math.max(1, w);
            float cy = (art.getTop() + art.getHeight() / 2f) / (float) Math.max(1, h);
            glow.setCenter(cx, cy);
            chrome.placeClockAbove(art, dp(4));
        });
    }

    private void applyScale(float k) {
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34 * k);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19 * k);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * k);
        chipText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13 * k);
        sourcePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * k);

        topMargin(title, dp(22 * k));
        topMargin(artist, dp(6 * k));
        topMargin(counter, dp(6 * k));
        topMargin(wave, dp(26 * k));
        ViewGroup.LayoutParams wl = wave.getLayoutParams();
        wl.height = dp(46 * k);
        wave.setLayoutParams(wl);
        topMargin(controls, dp(20 * k));
        topMargin(sourcePill, dp(18 * k));

        sizeButton(btnPrev, 76 * k, 36 * k, 0);
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) btnPlay.getLayoutParams();
        plp.width = plp.height = dp(100 * k);
        plp.leftMargin = dp(40 * k);
        btnPlay.setLayoutParams(plp);
        sizeButton(btnNext, 76 * k, 36 * k, 40 * k);
        info.requestLayout();
    }

    private void topMargin(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = px;
        v.setLayoutParams(lp);
    }

    private void sizeButton(ImageButton b, float sizeDp, float iconDp, float leftDp) {
        int size = dp(sizeDp);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = size;
        lp.height = size;
        lp.leftMargin = dp(leftDp);
        b.setLayoutParams(lp);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        int pad = (size - dp(iconDp)) / 2;
        b.setPadding(pad, pad, pad, pad);
    }

    private View buildChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_SURFACE);
        bg.setStroke(dp(1), C_STROKE);
        bg.setCornerRadius(dp(100));
        chip.setBackground(bg);

        chipDot = new View(this);
        chipDot.setBackground(dot(C_TEXT_3));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        chip.addView(chipDot, dlp);

        chipIcon = new ImageView(this);
        chipIcon.setImageResource(R.drawable.ic_bt);
        chipIcon.setImageTintList(ColorStateList.valueOf(C_TEXT_3));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(16), dp(16));
        ilp.setMarginStart(dp(9));
        chip.addView(chipIcon, ilp);

        chipText = text(fMedium, 13, C_TEXT_2);
        chipText.setSingleLine(true);
        chipText.setEllipsize(TextUtils.TruncateAt.END);
        chipText.setMaxWidth(dp(260));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(6));
        chip.addView(chipText, tlp);
        return chip;
    }

    private ImageButton roundButton(int icon, int sizeDp, int iconDp, boolean primary) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp((sizeDp - iconDp) / 2);
        b.setPadding(pad, pad, pad, pad);
        b.setImageTintList(ColorStateList.valueOf(primary ? C_TEXT : C_ICON));
        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), null, mask));
        return b;
    }

    // ================================================================== helpers

    private void pulse(View v) {
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(2.2f)).start()
        ).start();
    }

    private void setTextAnimated(final TextView tv, final CharSequence text) {
        if (TextUtils.equals(tv.getText(), text)) return;
        if (tv.getText().length() == 0) {
            tv.setText(text);
            return;
        }
        tv.animate().alpha(0f).translationY(dp(6)).setDuration(160).withEndAction(() -> {
            tv.setText(text);
            tv.setTranslationY(-dp(6));
            tv.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
            if (tv == title) tv.setSelected(true);
        }).start();
    }

    private TextView text(Typeface face, float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setIncludeFontPadding(true);
        return t;
    }

    private GradientDrawable dot(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private Typeface font(String name) {
        try {
            return Typeface.createFromAsset(getAssets(), "fonts/" + name + ".ttf");
        } catch (Throwable t) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams fullWidth(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams wrapCentered(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams sq(int sizeDp, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.leftMargin = dp(leftMarginDp);
        return lp;
    }

    private static final char[] FA = {'۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'};

    static String fa(long n) {
        String s = String.valueOf(n);
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? FA[c - '0'] : c);
        return b.toString();
    }

}
