package app.shelf;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 书架网格里的一格：封面 + 书名 + 进度，支持多选态。 */
public class BookCell extends LinearLayout {

    private final CoverView cover;
    private final TextView title;
    private final TextView meta;
    private final TextView mark;
    private Model.Book book;
    private boolean picked;

    public BookCell(Context c) {
        super(c);
        setOrientation(VERTICAL);

        FrameLayout box = new FrameLayout(c);
        cover = new CoverView(c);
        box.addView(cover, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mark = new TextView(c);
        mark.setText("✓");
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mark.setTextColor(0xFF141416);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Theme.ACCENT);
        mark.setBackground(gd);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                Theme.dp(c, 24), Theme.dp(c, 24), Gravity.TOP | Gravity.END);
        mp.topMargin = Theme.dp(c, 8);
        mp.rightMargin = Theme.dp(c, 8);
        mark.setVisibility(GONE);
        box.addView(mark, mp);

        addView(box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(Theme.TEXT);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Theme.medium());
        LayoutParams tp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tp.topMargin = Theme.dp(c, 11);
        addView(title, tp);

        meta = new TextView(c);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setTextColor(Theme.TEXT3);
        meta.setMaxLines(1);
        meta.setFontFeatureSettings("tnum");
        LayoutParams mp2 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        mp2.topMargin = Theme.dp(c, 4);
        addView(meta, mp2);
    }

    public void bind(Model.Book b) {
        this.book = b;
        cover.setBook(b);
        title.setText(b.title);
        if (b.kind == 1) {
            int n = b.childCount;
            meta.setText(n > 0 ? "合集  ·  " + n + " 本" : "空合集");
        } else if (b.kind == 2) {
            meta.setText("合并本  ·  " + b.chapters.size() + " 章");
        } else {
            int total = Math.max(1, b.chapters.size());
            int cur = Math.min(b.chapterIndex + 1, total);
            int pct = (int) Math.round(cur * 100.0 / total);
            meta.setText("第 " + cur + "/" + total + " 章  ·  " + pct + "%");
        }
    }

    public void setPicked(boolean on) {
        this.picked = on;
        mark.setVisibility(on ? VISIBLE : GONE);
        cover.setAlpha(on ? 0.72f : 1f);
    }

    public boolean isPicked() {
        return picked;
    }

    public Model.Book book() {
        return book;
    }
}
