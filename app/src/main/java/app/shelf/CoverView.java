package app.shelf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.io.File;

/**
 * 书架封面：默认按书名「程序生成」（暗调色 + 竖排宋体书名 + 书脊），
 * 用户自己选过封面图就直接显示图片。全部本地完成，不联网。
 */
public class CoverView extends View {

    private static final int[][] PALETTE = {
            {0xFF3C2426, 0xFF1A0E10},   // 酒红
            {0xFF24303F, 0xFF0E1319},   // 墨蓝
            {0xFF2C3A2E, 0xFF101710},   // 苔绿
            {0xFF42321F, 0xFF170F09},   // 赭石
            {0xFF34283A, 0xFF130E16},   // 紫檀
            {0xFF1E3436, 0xFF0B1415},   // 青灰
            {0xFF3A2C1E, 0xFF14100A},   // 褐
            {0xFF282C38, 0xFF0E1015},   // 石板
    };

    private Model.Book book;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private Bitmap custom;
    private float radiusDp = 10f;

    public CoverView(Context c) {
        this(c, null);
    }

    public CoverView(Context c, AttributeSet a) {
        super(c, a);
        tp.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    }

    public void setBook(Model.Book b) {
        this.book = b;
        this.custom = null;
        if (b != null && b.coverFile != null && !b.coverFile.isEmpty()) {
            custom = loadCustom(b.coverFile);
        }
        invalidate();
    }

    private Bitmap loadCustom(String name) {
        try {
            File f = new File(new File(getContext().getFilesDir(), "covers"), name);
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 400) s *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) w = Theme.dp(getContext(), 120);
        int h = Math.round(w * 4f / 3f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = Theme.dp(getContext(), radiusDp);
        rf.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rf, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        if (custom != null) {
            drawCoverCrop(canvas, custom, w, h);
            p.setShader(null);
            p.setColor(Color.argb(60, 0, 0, 0));
            canvas.drawRect(0, h * 0.62f, w, h, p);
        } else {
            String title = book == null || book.title == null ? "" : book.title;
            int[] tone = toneFor(title);
            p.setShader(new LinearGradient(0, 0, 0, h, tone[0], tone[1], Shader.TileMode.CLAMP));
            canvas.drawRect(rf, p);
            p.setShader(null);

            // 书脊
            float spine = w * 0.095f;
            p.setColor(Color.argb(70, 0, 0, 0));
            canvas.drawRect(0, 0, spine, h, p);
            p.setColor(Color.argb(30, 255, 255, 255));
            canvas.drawRect(spine, 0, spine + Math.max(1f, w * 0.004f), h, p);

            // 竖排书名
            String t = title.length() > 6 ? title.substring(0, 5) + "…" : title;
            if (t.length() > 0) {
                float size = Math.min(w * 0.20f, h * 0.86f / t.length());
                tp.setTextSize(size);
                tp.setColor(Color.argb(228, 237, 237, 241));
                float lineH = size * 1.34f;
                float total = lineH * t.length();
                float x = w * 0.60f;
                float y = (h - total) / 2f + size;
                for (int i = 0; i < t.length(); i++) {
                    canvas.drawText(t, i, i + 1, x, y, tp);
                    y += lineH;
                }
            }
            // 顶部一点暖金
            p.setColor(Color.argb(140, 231, 183, 120));
            canvas.drawRect(w * 0.60f, h * 0.085f, w * 0.60f + w * 0.10f,
                    h * 0.085f + Math.max(2f, h * 0.008f), p);
        }

        // 底部压暗，画面更沉
        p.setShader(new LinearGradient(0, h * 0.55f, 0, h,
                Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.55f, w, h, p);
        p.setShader(null);
        canvas.restore();

        // 发丝边
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, w * 0.0035f));
        p.setColor(Color.argb(36, 255, 255, 255));
        rf.set(0.6f, 0.6f, w - 0.6f, h - 0.6f);
        canvas.drawRoundRect(rf, r, r, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawCoverCrop(Canvas canvas, Bitmap bmp, int w, int h) {
        float scale = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        int dw = Math.round(bmp.getWidth() * scale);
        int dh = Math.round(bmp.getHeight() * scale);
        int dx = (w - dw) / 2;
        int dy = (h - dh) / 2;
        canvas.drawBitmap(bmp, null, new RectF(dx, dy, dx + dw, dy + dh), p);
    }

    private static int[] toneFor(String title) {
        if (title == null) title = "";
        int h = title.hashCode();
        int idx = Math.abs(h) % PALETTE.length;
        return new int[]{PALETTE[idx][0], PALETTE[idx][1]};
    }
}
