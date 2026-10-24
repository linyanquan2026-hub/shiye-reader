package app.shelf;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReaderActivity extends Activity implements PageView.Listener {

    private static final int[] SIZES = {17, 19, 21, 24};
    private static final int[] SCRIMS = {46, 68, 84};
    private static final int REQ_BG = 2101;

    private Model.Book book;
    private PageView pageView;
    private TextView progress, topTitle;
    private LinearLayout topBar, bottomBar;
    private FrameLayout drawer, bgSheet;
    private View dim, panel, bgDim, bgPanel;
    private TextView bgNone, bgPick, scrimLight, scrimMid, scrimHeavy;
    private ListView toc;
    private EditText searchBox;
    private boolean barsShown = false;
    private int sizeIdx = 1;
    private int scrimIdx = 1;

    private boolean tocDirty = true;
    private boolean suppressWatcher = false;
    private String query = "";
    private int searchGen = 0;
    private int searchW = 0, searchH = 0;

    /** 一次算多少行结果（每行 = 一章里的一个命中页）。 */
    private static final int BATCH = 30;
    private String activeTerm = "";
    private final List<int[]> matches = new ArrayList<>();
    private int matchCursor = 0;
    private int totalHits = 0;
    private boolean loadingMore = false;

    private final List<Object> tocRows = new ArrayList<>();
    private TocAdapter tocAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 一条搜索结果：第几章 / 第几页 / 一共几页 / 命中字符位置 / 本章命中次数 / 上下文。 */
    private static class Hit {
        int chapter;
        int page;
        int pages;
        int offset;
        int total;
        String snippet;

        Hit(int c, int p, int n, int off, int totalHits, String s) {
            chapter = c;
            page = p;
            pages = n;
            offset = off;
            total = totalHits;
            snippet = s;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_reader);
        getWindow().setStatusBarColor(Theme.BG0);
        getWindow().setNavigationBarColor(Theme.BG0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String id = getIntent().getStringExtra("id");
        book = Store.load(this, id);
        if (book == null || book.chapters.isEmpty()) {
            finish();
            return;
        }
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == book.fontSize) sizeIdx = i;
        scrimIdx = nearestScrim(book.bgScrim);
        book.lastOpen = System.currentTimeMillis();

        pageView = findViewById(R.id.pageView);
        progress = findViewById(R.id.progressText);
        topTitle = findViewById(R.id.topTitle);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        drawer = findViewById(R.id.drawer);
        dim = findViewById(R.id.dim);
        panel = findViewById(R.id.drawerPanel);
        toc = findViewById(R.id.tocList);
        bgSheet = findViewById(R.id.bgSheet);
        bgDim = findViewById(R.id.bgDim);
        bgPanel = findViewById(R.id.bgPanel);
        bgNone = findViewById(R.id.bgNone);
        bgPick = findViewById(R.id.bgPick);
        scrimLight = findViewById(R.id.scrimLight);
        scrimMid = findViewById(R.id.scrimMid);
        scrimHeavy = findViewById(R.id.scrimHeavy);

        pageView.setListener(this);
        pageView.setTypography(SIZES[sizeIdx], book.lineSpacing);
        searchBox = findViewById(R.id.searchBox);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;
                ui.removeCallbacks(searchTask);
                ui.postDelayed(searchTask, 200);
            }
        });

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.prevBtn).setOnClickListener(v -> prevChapter());
        findViewById(R.id.nextBtn).setOnClickListener(v -> nextChapter());
        findViewById(R.id.fontBtn).setOnClickListener(v -> cycleFont());
        findViewById(R.id.bgBtn).setOnClickListener(v -> openBgSheet());
        findViewById(R.id.tocBtn).setOnClickListener(v -> openDrawer());
        findViewById(R.id.closeDrawer).setOnClickListener(v -> closeDrawer());
        dim.setOnClickListener(v -> closeDrawer());

        findViewById(R.id.bgClose).setOnClickListener(v -> closeBgSheet());
        bgDim.setOnClickListener(v -> closeBgSheet());
        bgNone.setOnClickListener(v -> clearBackground());
        bgPick.setOnClickListener(v -> pickBackground());
        scrimLight.setOnClickListener(v -> applyScrim(0));
        scrimMid.setOnClickListener(v -> applyScrim(1));
        scrimHeavy.setOnClickListener(v -> applyScrim(2));

        loadBackground();
        setBars(false, false);
        showChapter(book.chapterIndex, book.pageIndex, false);

        tocAdapter = new TocAdapter();
        toc.setAdapter(tocAdapter);
        toc.setOnItemClickListener((p, v, pos, i) -> {
            Object o = tocRows.get(pos);
            if (o instanceof MoreRow) {
                requestMore();
                return;
            }
            if (o instanceof Hit) {
                Hit h = (Hit) o;
                String term = query;
                closeDrawer();
                showChapter(h.chapter, 0, false);
                pageView.gotoChar(h.offset);
                pageView.setHighlight(term);
                return;
            }
            int target = book.chapterIndex;
            if (o instanceof Model.Volume) target = ((Model.Volume) o).from;
            else if (o instanceof int[]) target = ((int[]) o)[0];
            else return;
            closeDrawer();
            pageView.setHighlight(null);
            showChapter(target, 0, true);
        });
        buildTocRows();
    }

    // ---------------- 章节 ----------------

    private void showChapter(int index, int page, boolean animate) {
        if (book.chapters.isEmpty()) return;
        index = Math.max(0, Math.min(book.chapters.size() - 1, index));
        book.chapterIndex = index;
        Model.Chapter c = book.chapters.get(index);
        Model.Volume v = book.volumeOf(index);
        pageView.setHighlight(null);

        String eyebrow = (v == null || v.title.isEmpty() ? "" : v.title + "   ")
                + "第 " + (index + 1) + " / " + book.chapters.size() + " 章";
        pageView.setChapter(c.title, eyebrow, c.body, Math.max(0, page));
        topTitle.setText(c.title);
        book.pageIndex = pageView.currentPage();

        findViewById(R.id.prevBtn).setAlpha(index == 0 ? 0.35f : 1f);
        findViewById(R.id.nextBtn).setAlpha(index == book.chapters.size() - 1 ? 0.35f : 1f);

        if (animate) {
            pageView.setAlpha(0.15f);
            pageView.setTranslationX(Theme.dp(this, 30));
            pageView.animate().alpha(1f).translationX(0f).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(0.35f)).start();
        }
        tocDirty = true;
        if (drawer.getVisibility() == View.VISIBLE && query.isEmpty()) buildTocRows();
    }

    private void nextChapter() {
        if (book.chapterIndex + 1 >= book.chapters.size()) {
            toast("已经是最后一章了");
            return;
        }
        showChapter(book.chapterIndex + 1, 0, true);
    }

    private void prevChapter() {
        if (book.chapterIndex <= 0) {
            toast("已经是第一章了");
            return;
        }
        showChapter(book.chapterIndex - 1, Integer.MAX_VALUE, true);
    }

    @Override
    public void onSwipeNextChapter() {
        if (book.chapterIndex + 1 < book.chapters.size()) {
            showChapter(book.chapterIndex + 1, 0, true);
        } else {
            toast("已经是最后一章了");
        }
    }

    @Override
    public void onSwipePrevChapter() {
        if (book.chapterIndex > 0) {
            showChapter(book.chapterIndex - 1, Integer.MAX_VALUE, true);
        } else {
            toast("已经是第一章了");
        }
    }

    @Override
    public void onPageChanged(int page, int count) {
        book.pageIndex = page;
        int total = Math.max(1, book.chapters.size());
        double inChapter = count <= 1 ? 1.0 : (page + 1.0) / count;
        int pct = (int) Math.round((book.chapterIndex + inChapter) * 100.0 / total);
        progress.setText(String.format(Locale.US, "%d/%d 页   ·   %d/%d 章   ·   %d%%",
                page + 1, count, book.chapterIndex + 1, total, Math.min(100, pct)));
    }

    @Override
    public void onTap() {
        toggleBars();
    }

    // ---------------- 字号 ----------------

    private void cycleFont() {
        sizeIdx = (sizeIdx + 1) % SIZES.length;
        book.fontSize = SIZES[sizeIdx];
        pageView.setTypography(SIZES[sizeIdx], book.lineSpacing);
        Store.saveProgress(this, book);
        toast("字号  " + SIZES[sizeIdx]);
    }

    // ---------------- 工具栏 ----------------

    private void toggleBars() {
        if (bgSheet.getVisibility() == View.VISIBLE) {
            closeBgSheet();
            return;
        }
        if (drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        setBars(!barsShown, true);
    }

    private void setBars(boolean show, boolean animate) {
        barsShown = show;
        if (!animate) {
            topBar.setAlpha(show ? 1f : 0f);
            bottomBar.setAlpha(show ? 1f : 0f);
            topBar.setTranslationY(show ? 0 : -Theme.dp(this, 16));
            bottomBar.setTranslationY(show ? 0 : Theme.dp(this, 28));
            topBar.setVisibility(show ? View.VISIBLE : View.GONE);
            bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        if (show) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).translationY(0).setDuration(240).start();
            bottomBar.animate().alpha(1f).translationY(0).setDuration(300)
                    .setInterpolator(new OvershootInterpolator(0.5f)).start();
        } else {
            topBar.animate().alpha(0f).translationY(-Theme.dp(this, 16)).setDuration(180)
                    .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
            bottomBar.animate().alpha(0f).translationY(Theme.dp(this, 28)).setDuration(180)
                    .withEndAction(() -> bottomBar.setVisibility(View.GONE)).start();
        }
    }

    // ---------------- 目录 / 搜索 ----------------

    private void openDrawer() {
        drawer.setVisibility(View.VISIBLE);
        // 记录「正常阅读尺寸」下的正文宽高：搜索结果里的页码以它为基准，
        // 键盘弹出把窗口压短也不会让页码错位。
        int w = pageView.bodyWidth();
        int h = pageView.bodyHeight();
        if (w > 0 && h > 0) {
            searchW = w;
            searchH = h;
        }
        if (tocDirty && query.isEmpty()) {
            buildTocRows();
            tocDirty = false;
        }
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setDuration(220).start();
        panel.setTranslationY(Theme.dp(this, 24));
        panel.setAlpha(0f);
        panel.animate().translationY(0f).alpha(1f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(0.12f)).start();
    }

    private void closeDrawer() {
        hideKeyboard();
        if (!query.isEmpty() || !searchBox.getText().toString().isEmpty()) {
            suppressWatcher = true;
            searchBox.setText("");
            suppressWatcher = false;
            query = "";
            searchGen++;
            matches.clear();
            matchCursor = 0;
            loadingMore = false;
            tocDirty = true;
        }
        dim.animate().alpha(0f).setDuration(150).start();
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null && getCurrentFocus() != null) im.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            searchBox.clearFocus();
        } catch (Exception ignored) {
        }
    }

    private void buildTocRows() {
        tocRows.clear();
        int current = book.chapterIndex;
        for (Model.Volume v : book.volumes) {
            tocRows.add(v);
            for (int i = v.from; i <= v.to && i < book.chapters.size(); i++) {
                tocRows.add(new int[]{i});
            }
        }
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        toc.post(() -> {
            int pos = 0;
            for (int i = 0; i < tocRows.size(); i++) {
                Object o = tocRows.get(i);
                if (o instanceof int[] && ((int[]) o)[0] == current) {
                    pos = i;
                    break;
                }
            }
            toc.setSelection(Math.max(0, pos - 2));
        });
    }

    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    private void runSearch() {
        final String term = searchBox.getText().toString().trim();
        query = term;
        if (term.isEmpty()) {
            tocDirty = false;
            buildTocRows();
            return;
        }
        final int gen = ++searchGen;
        tocRows.clear();
        tocRows.add("搜索「" + term + "」…");
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        new Thread(() -> {
            // 第一遍：全书扫一遍，只数不改，很快
            final List<int[]> ms = new ArrayList<>();
            int hits = 0;
            for (int i = 0; i < book.chapters.size(); i++) {
                String body = book.chapters.get(i).body;
                int k = body.indexOf(term);
                if (k < 0) continue;
                int n = 0;
                while (k >= 0 && n < 500) {
                    n++;
                    k = body.indexOf(term, k + term.length());
                }
                ms.add(new int[]{i, n});
                hits += n;
            }
            final int totalHits = hits;
            ui.post(() -> {
                if (gen != searchGen) return;
                startHits(term, ms, totalHits);
            });
        }).start();
    }

    /** 第一遍扫完：先挂个「共 N 章」，再按批把页码算出来。 */
    private void startHits(String term, List<int[]> ms, int totalHits) {
        activeTerm = term;
        matches.clear();
        matches.addAll(ms);
        this.totalHits = totalHits;
        matchCursor = 0;
        loadingMore = false;
        tocRows.clear();
        if (ms.isEmpty()) {
            tocRows.add("没有找到「" + term + "」");
            if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
            return;
        }
        tocRows.add("共 " + ms.size() + " 章 · " + totalHits + " 处，按页码列出");
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
        requestMore();
    }

    /** 分页只在后台算，一次算一批，界面不卡。 */
    private void requestMore() {
        if (loadingMore || matchCursor >= matches.size()) return;
        loadingMore = true;
        final int gen = searchGen;
        final int from = matchCursor;
        final String term = activeTerm;
        final int w = searchW > 0 ? searchW : pageView.bodyWidth();
        final int h = searchH > 0 ? searchH : pageView.bodyHeight();
        final TextPaint paint = pageView.bodyPaintCopy();
        final float mult = pageView.lineSpacing();
        if (w <= 0 || h <= 0) {
            loadingMore = false;
            return;
        }
        new Thread(() -> {
            List<Hit> out = new ArrayList<>();
            int idx = from;
            final int len = term.length();
            while (idx < matches.size() && out.size() < BATCH) {
                int[] m = matches.get(idx++);
                String body = book.chapters.get(m[0]).body;
                StaticLayout lay = new StaticLayout(body, paint, w, Layout.Alignment.ALIGN_NORMAL, mult, 0f, true);
                int[] starts = Paginator.pageLineStarts(lay, h);
                java.util.HashSet<Integer> seen = new java.util.HashSet<>();
                int added = 0;
                for (int k = body.indexOf(term); k >= 0 && added < 3; k = body.indexOf(term, k + len)) {
                    int page = Paginator.pageOfChar(lay, starts, k);
                    if (!seen.add(page)) continue;
                    out.add(new Hit(m[0], page, starts.length, k, m[1], snippet(body, k, len)));
                    added++;
                    if (out.size() >= BATCH) break;
                }
            }
            final List<Hit> batch = out;
            final int consumed = idx;
            ui.post(() -> {
                if (gen != searchGen) return;
                loadingMore = false;
                matchCursor = consumed;
                appendHits(batch);
            });
        }).start();
    }

    private void appendHits(List<Hit> batch) {
        if (!tocRows.isEmpty() && tocRows.get(tocRows.size() - 1) instanceof MoreRow) {
            tocRows.remove(tocRows.size() - 1);
        }
        tocRows.addAll(batch);
        if (matchCursor < matches.size()) {
            tocRows.add(new MoreRow(matches.size() - matchCursor));
        }
        if (tocAdapter != null) tocAdapter.notifyDataSetChanged();
    }

    /** 「继续显示更多」占位行。 */
    private static class MoreRow {
        int remaining;

        MoreRow(int r) {
            remaining = r;
        }
    }

    private static String snippet(String body, int at, int len) {
        int s = Math.max(0, at - 16);
        int e = Math.min(body.length(), at + len + 26);
        String t = body.substring(s, e).replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return (s > 0 ? "…" : "") + t + (e < body.length() ? "…" : "");
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        if (bgSheet.getVisibility() == View.VISIBLE) {
            closeBgSheet();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 背景 ----------------

    private File bgDir() {
        File d = new File(getFilesDir(), "bg");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private int nearestScrim(int v) {
        int best = 1;
        int diff = Integer.MAX_VALUE;
        for (int i = 0; i < SCRIMS.length; i++) {
            int d = Math.abs(SCRIMS[i] - v);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private void loadBackground() {
        pageView.setScrim(SCRIMS[scrimIdx]);
        if (book.bgFile == null || book.bgFile.isEmpty()) {
            pageView.setPageBackground(null);
            refreshBgState();
            return;
        }
        File f = new File(bgDir(), book.bgFile);
        if (!f.exists()) {
            book.bgFile = "";
            pageView.setPageBackground(null);
            refreshBgState();
            return;
        }
        Bitmap bmp = decodeScaled(f.getAbsolutePath());
        pageView.setPageBackground(bmp);
        refreshBgState();
    }

    private void pickBackground() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_BG);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_BG || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        toast("正在处理图片…");
        new Thread(() -> {
            try {
                byte[] raw = readAll(uri);
                final Bitmap bmp = decodeScaled(raw);
                if (bmp == null) throw new IllegalStateException("无法读取这张图片");
                File out = new File(bgDir(), book.id + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                }
                ui.post(() -> {
                    book.bgFile = out.getName();
                    book.bgScrim = SCRIMS[scrimIdx];
                    pageView.setPageBackground(bmp);
                    pageView.setScrim(SCRIMS[scrimIdx]);
                    refreshBgState();
                    Store.saveProgress(ReaderActivity.this, book);
                    closeBgSheet();
                    toast("背景已设置");
                });
            } catch (Exception e) {
                ui.post(() -> toast("设置失败：" + e.getMessage()));
            }
        }).start();
    }

    private void applyScrim(int idx) {
        scrimIdx = idx;
        book.bgScrim = SCRIMS[idx];
        pageView.setScrim(SCRIMS[idx]);
        refreshBgState();
        Store.saveProgress(this, book);
    }

    private void clearBackground() {
        if (book.bgFile != null && !book.bgFile.isEmpty()) {
            File f = new File(bgDir(), book.bgFile);
            if (f.exists()) f.delete();
        }
        book.bgFile = "";
        pageView.setPageBackground(null);
        refreshBgState();
        Store.saveProgress(this, book);
        toast("已恢复默认深色底");
    }

    private void refreshBgState() {
        boolean has = book.bgFile != null && !book.bgFile.isEmpty();
        bgNone.setSelected(!has);
        bgPick.setSelected(has);
        scrimLight.setSelected(scrimIdx == 0);
        scrimMid.setSelected(scrimIdx == 1);
        scrimHeavy.setSelected(scrimIdx == 2);
    }

    private void openBgSheet() {
        bgSheet.setVisibility(View.VISIBLE);
        refreshBgState();
        bgDim.setAlpha(0f);
        bgDim.animate().alpha(1f).setDuration(200).start();
        bgPanel.setTranslationY(Theme.dp(this, 320));
        bgPanel.animate().translationY(0f).setDuration(320)
                .setInterpolator(new OvershootInterpolator(0.14f)).start();
    }

    private void closeBgSheet() {
        bgDim.animate().alpha(0f).setDuration(150).start();
        bgPanel.animate().translationY(Theme.dp(this, 320)).setDuration(200)
                .withEndAction(() -> bgSheet.setVisibility(View.GONE)).start();
    }

    // ---------------- 图片解码 ----------------

    private int screenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private Bitmap decodeScaled(String path) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);
        probe.inSampleSize = sampleSize(probe.outWidth, probe.outHeight);
        probe.inJustDecodeBounds = false;
        try {
            return BitmapFactory.decodeFile(path, probe);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap decodeScaled(byte[] raw) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        probe.inSampleSize = sampleSize(probe.outWidth, probe.outHeight);
        probe.inJustDecodeBounds = false;
        try {
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private int sampleSize(int w, int h) {
        if (w <= 0 || h <= 0) return 1;
        // 背景只需要覆盖屏幕，控制在 400 万像素内，避免大图 OOM
        long budget = 4000000L;
        int s = 1;
        while ((long) w * h / ((long) s * s) > budget) s++;
        return s;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (book != null) {
            book.pageIndex = pageView.currentPage();
            // 只写进度（几十字节），不再整本书序列化，返回书架不再卡一下
            Store.saveProgress(this, book);
        }
    }

    private class TocAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return tocRows.size();
        }

        @Override
        public Object getItem(int i) {
            return tocRows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object o = tocRows.get(position);
            if (o instanceof Model.Volume) return 0;
            if (o instanceof Hit || o instanceof String || o instanceof MoreRow) return 2;
            return 1;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Object o = tocRows.get(i);
            if (v == null) v = LayoutInflater.from(ReaderActivity.this).inflate(R.layout.item_toc, parent, false);
            TextView t = v.findViewById(R.id.tocTitle);
            TextView s = v.findViewById(R.id.tocSummary);
            if (o instanceof MoreRow) {
                MoreRow m = (MoreRow) o;
                t.setText("继续显示后面的 " + m.remaining + " 章  ↓");
                t.setTextSize(13);
                t.setTypeface(Theme.medium());
                t.setTextColor(Theme.ACCENT);
                s.setVisibility(View.GONE);
                v.setPadding(Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 20),
                        Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 20));
                return v;
            }
            if (o instanceof String) {
                t.setText((String) o);
                t.setTextSize(13);
                t.setTypeface(Theme.serif());
                t.setTextColor(Theme.TEXT3);
                s.setVisibility(View.GONE);
                v.setPadding(Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 22),
                        Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 22));
                return v;
            }
            if (o instanceof Hit) {
                Hit h = (Hit) o;
                Model.Chapter c = book.chapters.get(h.chapter);
                t.setText("第 " + (h.chapter + 1) + " 章   " + c.title + "（" + h.total + " 处）");
                t.setTextSize(14);
                t.setTypeface(Theme.medium());
                t.setTextColor(Theme.TEXT);
                s.setVisibility(View.VISIBLE);
                s.setText("第 " + (h.page + 1) + " / " + h.pages + " 页  ·  " + h.snippet);
                v.setPadding(Theme.dp(ReaderActivity.this, 22), Theme.dp(ReaderActivity.this, 13),
                        Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 13));
                return v;
            }
            if (o instanceof Model.Volume) {
                Model.Volume vol = (Model.Volume) o;
                String vt = vol.title.replaceAll("\\s*·\\s*卷$", "");
                t.setText("卷 " + (book.volumes.indexOf(vol) + 1) + "   " + vt + "（" + vol.count() + " 章）");
                t.setTextColor(Theme.ACCENT);
                t.setTextSize(15);
                t.setTypeface(Theme.medium());
                if (vol.summary != null && vol.summary.length() > 0) {
                    s.setVisibility(View.VISIBLE);
                    s.setText(vol.summary);
                } else {
                    s.setVisibility(View.GONE);
                }
                v.setPadding(Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 20),
                        Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 6));
            } else {
                int idx = ((int[]) o)[0];
                Model.Chapter c = book.chapters.get(idx);
                t.setText(c.title);
                t.setTextSize(14);
                t.setTypeface(idx == book.chapterIndex ? Theme.medium() : Theme.serif());
                t.setTextColor(idx == book.chapterIndex ? Theme.TEXT : Theme.TEXT2);
                s.setVisibility(View.GONE);
                v.setPadding(Theme.dp(ReaderActivity.this, 34), Theme.dp(ReaderActivity.this, 11),
                        Theme.dp(ReaderActivity.this, 20), Theme.dp(ReaderActivity.this, 11));
            }
            return v;
        }
    }
}

