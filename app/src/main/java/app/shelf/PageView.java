package app.shelf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 横向分页阅读视图。
 * 手势：左滑下一张、右滑上一张；到章尾继续左滑进下一章，到章首继续右滑回上一章。
 * 背景：支持导入的自定义图片 + 可调压暗。
 */
public class PageView extends View {

    public interface Listener {
        void onPageChanged(int page, int pageCount);

        void onTap();

        void onSwipePrevChapter();

        void onSwipeNextChapter();
    }

    private Listener listener;

    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint eyebrowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrimPaint = new Paint();

    private StaticLayout layout;
    private final List<Integer> pageStarts = new ArrayList<>();
    private int pageCount = 1;
    private int pageIndex = 0;
    private int pendingPage = -1;

    private String text = "";
    private String chapterTitle = "";
    private String eyebrow = "";

    private int bodyColor = Color.parseColor("#DCDCE3");

    private float textSizePx;
    private float lineSpacingMult = 1.75f;

    private int headerH;
    private int footerH;
    private int padL, padR;

    private Bitmap background;
    private Bitmap bgScaled;
    private int scrim = 68;
    private String highlight;
    private final Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private VelocityTracker tracker;
    private float dragOffset;
    private float dragBase;
    private int turnDir;
    private ValueAnimator anim;
    private int touchSlop;
    private boolean dragging;
    private boolean tapAllowed = true;
    private float downX;
    private long downTime;

    public PageView(Context c) {
        this(c, null);
    }

    public PageView(Context c, AttributeSet a) {
        super(c, a);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);

        titlePaint.setTypeface(medium);
        titlePaint.setColor(Theme.TEXT);
        eyebrowPaint.setTypeface(sans);
        eyebrowPaint.setColor(Theme.TEXT3);
        eyebrowPaint.setLetterSpacing(0.14f);
        footerPaint.setTypeface(sans);
        footerPaint.setColor(Theme.TEXT3);
        footerPaint.setLetterSpacing(0.04f);
        sepPaint.setColor(Color.argb(18, 255, 255, 255));
        edgePaint.setColor(Color.argb(26, 255, 255, 255));
        hlPaint.setColor(Color.argb(64, 231, 183, 120));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ---------------- 内容 ----------------

    /** page < 0 表示从第一张开始。 */
    public void setChapter(String title, String eyebrow, String body, int page) {
        this.chapterTitle = title == null ? "" : title;
        this.eyebrow = eyebrow == null ? "" : eyebrow;
        this.text = body == null ? "" : body;
        pageIndex = 0;
        pendingPage = Math.max(0, page);
        cancelAnim();
        turnDir = 0;
        dragOffset = 0;
        rebuild(false);
    }

    public void setTypography(int fontSizeSp, float spacingMult) {
        float d = getResources().getDisplayMetrics().scaledDensity;
        this.textSizePx = fontSizeSp * d;
        this.lineSpacingMult = spacingMult;
        bodyPaint.setTextSize(textSizePx);
        bodyPaint.setLetterSpacing(0.012f);
        bodyPaint.setColor(bodyColor);
        titlePaint.setTextSize(20 * d);
        eyebrowPaint.setTextSize(11 * d);
        footerPaint.setTextSize(11 * d);
        rebuild(true);
    }

    public void setPageBackground(Bitmap bmp) {
        this.background = bmp;
        this.bgScaled = null;
        prepareBackground();
        invalidate();
    }

    /** 压暗程度，0-95 的百分比。 */
    public void setScrim(int percent) {
        this.scrim = Math.max(0, Math.min(95, percent));
        invalidate();
    }

    public int currentPage() {
        return pageIndex;
    }

    public int pageCount() {
        return pageCount;
    }

    public void gotoPage(int p) {
        pageIndex = Math.max(0, Math.min(pageCount - 1, p));
        pendingPage = -1;
        cancelAnim();
        turnDir = 0;
        dragOffset = 0;
        invalidate();
        notifyPage();
    }

    /** 跳到包含某个字符的那一页（搜索定位用，跨字号/高度都准）。 */
    public void gotoChar(int offset) {
        if (layout == null || pageStarts.isEmpty()) return;
        pageIndex = pageOfChar(Math.max(0, offset));
        pendingPage = -1;
        cancelAnim();
        turnDir = 0;
        dragOffset = 0;
        invalidate();
        notifyPage();
    }

    // ---------------- 分页 ----------------

    private void rebuild(boolean keepPosition) {
        int keepOffset = -1;
        if (keepPosition && layout != null && pageIndex < pageStarts.size()) {
            keepOffset = layout.getLineStart(pageStarts.get(pageIndex));
        }
        pageStarts.clear();
        dragOffset = 0;

        int w = getWidth();
        if (w == 0) {
            layout = null;
            pageCount = 1;
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        headerH = (int) (d * 86);
        footerH = (int) (d * 42);
        padL = (int) (d * 24);
        padR = (int) (d * 24);
        prepareBackground();

        int pageW = w - padL - padR;
        int pageH = getHeight() - headerH - footerH;
        if (pageW <= 0 || pageH <= 0) {
            layout = null;
            pageCount = 1;
            return;
        }

        bodyPaint.setColor(bodyColor);
        layout = new StaticLayout(text, bodyPaint, pageW, Layout.Alignment.ALIGN_NORMAL,
                lineSpacingMult, 0f, true);

        int[] starts = Paginator.pageLineStarts(layout, pageH);
        pageStarts.clear();
        for (int v : starts) pageStarts.add(v);
        pageCount = pageStarts.size();

        if (keepOffset > 0) {
            pageIndex = pageOfChar(keepOffset);
        } else if (pendingPage >= 0) {
            pageIndex = Math.min(pendingPage, pageCount - 1);
            pendingPage = -1;
        } else {
            pageIndex = Math.min(pageIndex, pageCount - 1);
        }
        invalidate();
        notifyPage();
    }

    private int pageOfChar(int offset) {
        int best = 0;
        for (int p = 0; p < pageStarts.size(); p++) {
            int start = layout.getLineStart(pageStarts.get(p));
            if (start <= offset) best = p;
            else break;
        }
        return best;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // 尺寸变化（转屏、键盘收起）时按字符偏移保住当前读到的地方
        rebuild(true);
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);

        int w = getWidth();
        int h = getHeight();
        float d = getResources().getDisplayMetrics().density;

        canvas.drawText(TextUtils.ellipsize(eyebrow, eyebrowPaint, w - padL - padR, TextUtils.TruncateAt.END).toString(),
                padL, d * 30, eyebrowPaint);
        canvas.drawText(TextUtils.ellipsize(chapterTitle, titlePaint, w - padL - padR, TextUtils.TruncateAt.END).toString(),
                padL, d * 60, titlePaint);
        canvas.drawRect(padL, headerH - d * 4, w - padR, headerH - d * 4 + 1, sepPaint);

        if (layout == null) return;

        String pg = (pageIndex + 1) + " / " + pageCount;
        canvas.drawText(pg, w - padR - footerPaint.measureText(pg), h - d * 18, footerPaint);

        canvas.save();
        canvas.clipRect(0, headerH, w, h - footerH);
        float shift = -dragOffset;
        drawPage(canvas, pageIndex, shift);
        if (dragOffset > 0f) {
            drawPage(canvas, pageIndex + 1, shift + w);
        } else if (dragOffset < 0f) {
            drawPage(canvas, pageIndex - 1, shift - w);
        }
        if (Math.abs(dragOffset) > 1f) {
            // 两页之间的「订口」中线，画一条极淡的竖线当页边
            float edge = dragOffset > 0 ? w - dragOffset : -dragOffset;
            canvas.drawRect(edge - 0.5f, headerH, edge + 0.5f, h - footerH, edgePaint);
        }
        canvas.restore();
    }

    /** 背景提前裁成屏幕尺寸，避免每帧缩放一张大图。 */
    private void prepareBackground() {
        if (background == null) {
            bgScaled = null;
            return;
        }
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) {
            bgScaled = null;
            return;
        }
        float scale = Math.max((float) vw / background.getWidth(), (float) vh / background.getHeight());
        int w = Math.round(background.getWidth() * scale);
        int h = Math.round(background.getHeight() * scale);
        if (bgScaled != null && bgScaled.getWidth() == w && bgScaled.getHeight() == h
                && bgScaled != background) {
            return;
        }
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(background, w, h, true);
            bgScaled = (scaled == background) ? background : scaled;
        } catch (OutOfMemoryError e) {
            bgScaled = null;
        }
    }

    private void drawBackground(Canvas canvas) {
        if (background == null) {
            canvas.drawColor(Theme.BG0);
            return;
        }
        if (bgScaled == null) prepareBackground();
        Bitmap bmp = bgScaled != null ? bgScaled : background;
        if (bmp != null) {
            canvas.drawBitmap(bmp, (getWidth() - bmp.getWidth()) / 2f,
                    (getHeight() - bmp.getHeight()) / 2f, null);
        }
        if (scrim > 0) {
            scrimPaint.setColor(Color.argb((int) (scrim / 100f * 255), 10, 10, 12));
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
    }

    /**
     * 只画这一页自己的行（不再整章 layout.draw），
     * 翻页时每帧的绘制量从「整章」降到「一页」。
     */
    private void drawPage(Canvas canvas, int page, float dx) {
        if (page < 0 || page >= pageCount || pageStarts.isEmpty() || layout == null) return;
        int startLine = pageStarts.get(page);
        int endLine = (page + 1 < pageStarts.size()) ? pageStarts.get(page + 1) : layout.getLineCount();
        canvas.save();
        canvas.translate(padL + dx, headerH - layout.getLineTop(startLine));

        // 搜索命中高亮先铺底
        String hl = highlight;
        if (hl != null && hl.length() > 0) {
            int step = Math.max(1, hl.length());
            for (int l = startLine; l < endLine; l++) {
                int a = layout.getLineStart(l);
                int b = layout.getLineEnd(l);
                int at = text.indexOf(hl, a);
                while (at >= 0 && at + hl.length() <= b) {
                    float x1 = layout.getPrimaryHorizontal(at);
                    float x2 = layout.getPrimaryHorizontal(at + hl.length());
                    canvas.drawRect(x1, layout.getLineTop(l), x2, layout.getLineBottom(l), hlPaint);
                    at = text.indexOf(hl, at + step);
                }
            }
        }
        for (int l = startLine; l < endLine; l++) {
            int a = layout.getLineStart(l);
            int b = layout.getLineEnd(l);
            if (b > a) {
                canvas.drawText(text, a, b, 0f, layout.getLineBaseline(l), bodyPaint);
            }
        }
        canvas.restore();
    }

    public int bodyWidth() {
        return Math.max(0, getWidth() - padL - padR);
    }

    public int bodyHeight() {
        return Math.max(0, getHeight() - headerH - footerH);
    }

    public TextPaint bodyPaintCopy() {
        return new TextPaint(bodyPaint);
    }

    public float lineSpacing() {
        return lineSpacingMult;
    }

    /** 搜索命中词，当前页上会高亮；null 清除。 */
    public void setHighlight(String term) {
        this.highlight = (term == null || term.isEmpty()) ? null : term;
        invalidate();
    }

    private void notifyPage() {
        if (listener != null) listener.onPageChanged(pageIndex, pageCount);
    }

    // ---------------- 手势 ----------------

    private float applyResistance(float dx) {
        boolean pullPrev = dx < 0 && pageIndex == 0;
        boolean pullNext = dx > 0 && pageIndex >= pageCount - 1;
        if (pullPrev || pullNext) return dx * 0.26f;
        return dx;
    }

    /**
     * 一次手势只翻一张：松手时判断方向，然后把这「一张」动画走完，
     * 动画结束才改 pageIndex。不再按速度连翻。
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tracker == null) tracker = VelocityTracker.obtain();
        tracker.addMovement(ev);
        int w = getWidth();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                boolean wasTurning = turnDir != 0;
                if (wasTurning) {
                    // 上一次翻页还没走完就按下：直接落位，避免半页卡住
                    int dir = turnDir;
                    cancelAnim();
                    dragOffset = 0f;
                    commitTurn(dir);
                } else {
                    cancelAnim();
                }
                downX = ev.getX();
                downTime = System.currentTimeMillis();
                dragging = false;
                dragBase = dragOffset;
                tapAllowed = !wasTurning;
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = downX - ev.getX();
                if (!dragging && Math.abs(dx) > touchSlop) dragging = true;
                if (dragging) {
                    float applied = dragBase + applyResistance(dx);
                    dragOffset = Math.max(-w, Math.min(w, applied));
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (tracker != null) tracker.computeCurrentVelocity(1000);
                float vx = tracker == null ? 0 : tracker.getXVelocity();
                float dist = downX - ev.getX();
                long dt = Math.max(1, System.currentTimeMillis() - downTime);

                if (ev.getActionMasked() == MotionEvent.ACTION_UP && !dragging && tapAllowed
                        && dt < 400 && Math.abs(dist) < touchSlop * 2) {
                    if (listener != null) listener.onTap();
                    springBack();
                    return true;
                }
                if (!dragging) {
                    springBack();
                    return true;
                }

                // 方向判定：手指往左 → 下一页（+1），手指往右 → 上一页（-1）
                // 先看位移（够不够一屏的 8%），位移不够再看甩动速度的方向。
                float minDist = Math.max(touchSlop * 3f, w * 0.08f);
                int dir = 0;
                if (dist > minDist) {
                    dir = 1;
                } else if (dist < -minDist) {
                    dir = -1;
                } else if (Math.abs(vx) >= 380f) {
                    // getXVelocity：往右为正。往左甩是负值 → 下一页
                    dir = vx < 0 ? 1 : -1;
                }

                if (dir > 0) {
                    if (pageIndex < pageCount - 1) {
                        startTurn(1);
                    } else {
                        dragOffset = 0f;
                        invalidate();
                        if (listener != null) listener.onSwipeNextChapter();
                    }
                } else if (dir < 0) {
                    if (pageIndex > 0) {
                        startTurn(-1);
                    } else {
                        dragOffset = 0f;
                        invalidate();
                        if (listener != null) listener.onSwipePrevChapter();
                    }
                } else {
                    springBack();
                }
                dragging = false;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** 往 dir 方向翻一张，动画结束后才真正换页。 */
    private void startTurn(final int dir) {
        cancelAnim();
        final int w = getWidth();
        if (w <= 0) return;
        turnDir = dir;
        final ValueAnimator a = ValueAnimator.ofFloat(dragOffset, dir * w);
        anim = a;
        a.setDuration(270);
        a.setInterpolator(new DecelerateInterpolator(1.7f));
        a.addUpdateListener(an -> {
            if (anim != a) return;
            dragOffset = (float) an.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (anim != a) return;
                anim = null;
                commitTurn(dir);
            }
        });
        a.start();
    }

    private void commitTurn(int dir) {
        turnDir = 0;
        if (dir > 0) pageIndex = Math.min(pageCount - 1, pageIndex + 1);
        else if (dir < 0) pageIndex = Math.max(0, pageIndex - 1);
        dragOffset = 0f;
        invalidate();
        notifyPage();
    }

    /** 没滑够：弹回原位。 */
    private void springBack() {
        cancelAnim();
        if (Math.abs(dragOffset) < 1f) {
            dragOffset = 0f;
            invalidate();
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(dragOffset, 0f);
        anim = a;
        a.setDuration(240);
        a.setInterpolator(new DecelerateInterpolator(2.2f));
        a.addUpdateListener(an -> {
            if (anim != a) return;
            dragOffset = (float) an.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (anim != a) return;
                anim = null;
                dragOffset = 0f;
                invalidate();
            }
        });
        a.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;        // 先置空，取消时回调里不会再提交
            a.cancel();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAnim();
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }
}

