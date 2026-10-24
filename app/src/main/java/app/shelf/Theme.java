package app.shelf;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.OvershootInterpolator;

public class Theme {

    public static final int BG0 = Color.parseColor("#0B0B0D");
    public static final int BG1 = Color.parseColor("#101014");
    public static final int BG2 = Color.parseColor("#16161B");
    public static final int BG3 = Color.parseColor("#1E1E24");
    public static final int TEXT = Color.parseColor("#EDEDF1");
    public static final int TEXT2 = Color.parseColor("#9A9AA5");
    public static final int TEXT3 = Color.parseColor("#6B6B76");
    public static final int ACCENT = Color.parseColor("#E7B778");
    public static final int ACCENT2 = Color.parseColor("#8FA8FF");

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    public static Typeface serif() {
        return Typeface.create("serif", Typeface.NORMAL);
    }

    /** 弹簧：先冲过头一点再回到目标（stiffness 越大越"弹"）。 */
    public static void spring(View v, float from, float to, long dur, float overshoot) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(dur);
        TimeInterpolator it = new OvershootInterpolator(overshoot);
        a.setInterpolator(it);
        a.addUpdateListener(an -> v.setTranslationY((float) an.getAnimatedValue()));
        a.start();
    }

    public static void fade(View v, float from, float to, long dur) {
        v.setAlpha(from);
        v.animate().alpha(to).setDuration(dur).start();
    }

    public static int blend(int a, int b, float k) {
        float ik = 1 - k;
        return Color.argb(
                (int) (Color.alpha(a) * ik + Color.alpha(b) * k),
                (int) (Color.red(a) * ik + Color.red(b) * k),
                (int) (Color.green(a) * ik + Color.green(b) * k),
                (int) (Color.blue(a) * ik + Color.blue(b) * k));
    }

    public static int alpha(int color, float a) {
        return Color.argb((int) (255 * Math.max(0, Math.min(1, a))), Color.red(color), Color.green(color), Color.blue(color));
    }
}
