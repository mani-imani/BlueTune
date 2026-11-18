package ir.maniimani.bluetune;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Square, rounded cover with a cross-fade whenever the song changes. */
final class ArtworkView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Typeface glyphFace;
    private final float radius;

    private Bitmap current, previous;
    private float fade = 1f;
    private String title = "", artist = "";
    private ValueAnimator anim;

    ArtworkView(Context c, Typeface glyphFace) {
        super(c);
        this.glyphFace = glyphFace;
        this.radius = 26 * c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setSong(String title, String artist) {
        title = title == null ? "" : title;
        artist = artist == null ? "" : artist;
        if (title.equals(this.title) && artist.equals(this.artist) && current != null) return;
        this.title = title;
        this.artist = artist;
        rebuild(true);
    }

    private void rebuild(boolean animate) {
        int size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        previous = animate ? current : null;
        current = Art.render(size, title, artist, glyphFace);
        if (anim != null) anim.cancel();
        if (previous == null) {
            fade = 1f;
            invalidate();
            return;
        }
        fade = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(650);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            if (fade >= 1f) previous = null;
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int size = Math.min(w, h);
        float left = (w - size) / 2f, top = (h - size) / 2f;
        rect.set(left, top, left + size, top + size);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        rebuild(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (current == null) return;
        canvas.save();
        canvas.clipPath(clip);
        if (previous != null && fade < 1f) {
            paint.setAlpha(255);
            canvas.drawBitmap(previous, null, rect, paint);
        }
        paint.setAlpha((int) (255 * fade));
        canvas.drawBitmap(current, null, rect, paint);
        canvas.restore();
    }
}
