package ir.maniimani.bluetune;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.text.BidiFormatter;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Hand-made marquee for the song title.
 *
 * Android's built-in TextView marquee restarts on every text, size or focus change and often
 * stalls on the head unit. This one never does: the title is laid out at its full width and
 * slid back and forth by translation, with a pause at each end and soft fading edges. A title
 * that fits is simply centered and doesn't move. Persian titles scroll right-to-left from
 * their start, Latin ones left-to-right.
 */
final class MarqueeView extends FrameLayout {

    private static final long PAUSE_START = 1800, PAUSE_END = 1400, FADE = 220;

    private final TextView text;
    private final float speed;  // px per second
    private final int fadeLen;
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator anim;
    private int lastW = -1, lastTextW = -1;
    private String lastText;
    private int gen;
    private boolean scrolling, rtl;

    MarqueeView(Context c, TextView text) {
        super(c);
        this.text = text;
        float d = c.getResources().getDisplayMetrics().density;
        speed = 38f * d;
        fadeLen = Math.round(26f * d);
        text.setSingleLine(true);
        text.setEllipsize(null);
        text.setHorizontallyScrolling(true);
        addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setClipChildren(true);
        setWillNotDraw(false);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // let the title be as wide as it wants; this view keeps the column's width
        text.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? text.getMeasuredWidth() : MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, resolveSize(text.getMeasuredHeight(), hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, tw = text.getMeasuredWidth(), th = text.getMeasuredHeight();
        int top = (b - t - th) / 2;
        text.layout(0, top, tw, top + th);
        if (w != lastW || tw != lastTextW || !text.getText().toString().equals(lastText)) {
            lastW = w;
            lastTextW = tw;
            lastText = text.getText().toString();
            post(this::restart);
        }
    }

    private void restart() {
        stop();
        int w = getWidth(), tw = text.getWidth();
        if (w <= 0) return;
        rtl = BidiFormatter.getInstance().isRtl(text.getText().toString());
        if (tw <= w) {
            scrolling = false;
            text.setTranslationX((w - tw) / 2f);
            invalidate();
            return;
        }
        scrolling = true;
        // RTL: right edges aligned first, then slide right to show the end on the left
        float from = rtl ? w - tw : 0, to = rtl ? 0 : w - tw;
        text.setTranslationX(from);
        invalidate();
        long dur = (long) (Math.abs(to - from) / speed * 1000f);
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(Math.max(800, dur));
        anim.setStartDelay(PAUSE_START);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            text.setTranslationX((float) a.getAnimatedValue());
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled) return;
                final int g = gen;
                // rest at the end, fade out, jump back to the start, fade in, go again
                postDelayed(() -> {
                    if (g != gen) return;
                    text.animate().alpha(0f).setDuration(FADE).withEndAction(() -> {
                    text.setTranslationX(from);
                    invalidate();
                    text.animate().alpha(1f).setDuration(FADE).withEndAction(() -> {
                        if (g == gen && anim == a) a.start();
                    }).start();
                }).start();
                }, PAUSE_END);
            }
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
        }
        gen++;
        text.animate().cancel();
        text.setAlpha(1f);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastW = -1;
        requestLayout();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) stop();
        else if (isAttachedToWindow()) {
            lastW = -1;
            requestLayout();
        }
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        if (!scrolling) {
            super.dispatchDraw(c);
            return;
        }
        int w = getWidth(), h = getHeight();
        int save = c.saveLayer(0, 0, w, h, null);
        super.dispatchDraw(c);
        float x = text.getTranslationX(), tw = text.getWidth();
        // fade an edge only while text is hidden past it
        if (x < -0.5f) drawFade(c, 0, fadeLen, h);
        if (x + tw > w + 0.5f) drawFade(c, w, w - fadeLen, h);
        c.restoreToCount(save);
    }

    private void drawFade(Canvas c, float edge, float inner, int h) {
        fadePaint.setShader(new LinearGradient(edge, 0, inner, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        c.drawRect(Math.min(edge, inner), 0, Math.max(edge, inner), h, fadePaint);
    }
}
