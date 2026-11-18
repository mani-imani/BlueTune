package ir.maniimani.geekplay;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * A calm "live" waveform that breathes while music plays and settles into a row of
 * dots when paused. Purely decorative (the head unit gives us no audio data), built
 * from layered sine waves per bar so it moves organically instead of jittering.
 */
final class WaveView extends View {

    private static final int BARS = 31;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] f1 = new float[BARS], f2 = new float[BARS], p1 = new float[BARS], p2 = new float[BARS];
    private final float barW, gap;

    private int color = Color.WHITE;
    private float energy = 0f;       // 0 = paused (dots), 1 = playing
    private float energyTarget = 0f;
    private long last = 0;
    private ValueAnimator colorAnim;

    WaveView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        barW = 3.2f * d;
        gap = 4.6f * d;
        Random r = new Random(7);
        for (int i = 0; i < BARS; i++) {
            f1[i] = 1.6f + r.nextFloat() * 2.2f;
            f2[i] = 0.5f + r.nextFloat() * 1.1f;
            p1[i] = r.nextFloat() * 6.283f;
            p2[i] = r.nextFloat() * 6.283f;
        }
    }

    void setPlaying(boolean playing) {
        energyTarget = playing ? 1f : 0f;
        postInvalidateOnAnimation();
    }

    void animateColor(int target) {
        if (colorAnim != null) colorAnim.cancel();
        colorAnim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        colorAnim.setDuration(900);
        colorAnim.addUpdateListener(a -> {
            color = (int) a.getAnimatedValue();
            invalidate();
        });
        colorAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        energy += (energyTarget - energy) * Math.min(1f, dt * 3.2f);
        float t = now / 1000f;

        float w = getWidth(), h = getHeight();
        float total = BARS * barW + (BARS - 1) * gap;
        float x = (w - total) / 2f;
        float cy = h / 2f;
        float maxH = h * 0.92f;

        for (int i = 0; i < BARS; i++) {
            float pos = (i - (BARS - 1) / 2f) / ((BARS - 1) / 2f);       // -1..1
            float env = (float) Math.exp(-pos * pos * 2.2f);               // taller in the middle
            float a = 0.5f + 0.5f * (float) Math.sin(t * f1[i] + p1[i]);
            float b = 0.65f + 0.35f * (float) Math.sin(t * f2[i] + p2[i]);
            float bh = barW + (maxH - barW) * env * a * b * energy;
            float edgeAlpha = 0.35f + 0.65f * env;
            paint.setColor(color);
            paint.setAlpha(Math.round(255 * edgeAlpha * (0.55f + 0.45f * energy)));
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
            c.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            x += barW + gap;
        }

        if (energy > 0.002f || energyTarget > 0f) {
            postInvalidateOnAnimation();
        } else {
            last = 0;
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) postInvalidateOnAnimation();
    }
}
