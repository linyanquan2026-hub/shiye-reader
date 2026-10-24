package app.shelf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private ListView list;
    private View empty, pickBar, folderBack;
    private TextView meta, count, shelfTitle, pickCount;
    private LinearLayout heroCard;
    private TextView heroTitle, heroSub, heroPct, pickMerge, pickFolder, pickCancel;
    private View heroFill, heroRest;
    private CoverView heroCover;

    /** 全部书（含被合并的源书） */
    private final List<Model.Book> all = new ArrayList<>();
    /** 当前层级要显示的书 */
    private final List<Model.Book> books = new ArrayList<>();
    private Model.Book hero;
    private String folder = "";
    private boolean picking = false;
    private final LinkedHashSet<String> picked = new LinkedHashSet<>();
    private ShelfAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int REQ_PICK = 1001;
    private static final int REQ_COVER = 1002;
    private String coverFor;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(Theme.BG0);
        getWindow().setNavigationBarColor(Theme.BG0);

        list = findViewById(R.id.bookList);
        empty = findViewById(R.id.emptyState);
        pickBar = findViewById(R.id.pickBar);
        pickCount = findViewById(R.id.pickCount);
        pickMerge = findViewById(R.id.pickMerge);
        pickFolder = findViewById(R.id.pickFolder);
        pickCancel = findViewById(R.id.pickCancel);

        View header = getLayoutInflater().inflate(R.layout.shelf_header, list, false);
        list.addHeaderView(header, null, false);
        meta = header.findViewById(R.id.shelfMeta);
        count = header.findViewById(R.id.shelfCount);
        shelfTitle = header.findViewById(R.id.shelfTitle);
        folderBack = header.findViewById(R.id.folderBack);
        heroCard = header.findViewById(R.id.heroCard);
        heroTitle = header.findViewById(R.id.heroTitle);
        heroSub = header.findViewById(R.id.heroSub);
        heroPct = header.findViewById(R.id.heroPct);
        heroFill = header.findViewById(R.id.heroFill);
        heroRest = header.findViewById(R.id.heroRest);
        heroCover = header.findViewById(R.id.heroCover);

        header.findViewById(R.id.importBtn).setOnClickListener(v -> pick());
        findViewById(R.id.emptyImport).setOnClickListener(v -> pick());
        heroCard.setOnClickListener(v -> {
            if (hero != null) open(hero);
        });
        folderBack.setOnClickListener(v -> {
            folder = "";
            reload();
        });
        pickCancel.setOnClickListener(v -> exitPicking());
        pickMerge.setOnClickListener(v -> doMerge());
        pickFolder.setOnClickListener(v -> doMakeFolder());

        adapter = new ShelfAdapter();
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!picking) reload();
    }

    @Override
    public void onBackPressed() {
        if (picking) {
            exitPicking();
            return;
        }
        if (!folder.isEmpty()) {
            folder = "";
            reload();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 数据 ----------------

    private void reload() {
        all.clear();
        all.addAll(Store.all(this));

        Map<String, Integer> childCount = new HashMap<>();
        for (Model.Book b : all) {
            if (b.collectionId != null && !b.collectionId.isEmpty()) {
                childCount.merge(b.collectionId, 1, Integer::sum);
            }
        }

        books.clear();
        for (Model.Book b : all) {
            if (b.mergedInto != null && !b.mergedInto.isEmpty()) continue;
            if (folder.isEmpty()) {
                if (b.collectionId == null || b.collectionId.isEmpty()) books.add(b);
            } else if (folder.equals(b.collectionId)) {
                books.add(b);
            }
        }
        for (Model.Book b : books) b.childCount = childCount.containsKey(b.id) ? childCount.get(b.id) : 0;

        books.sort((a, c) -> {
            boolean fa = a.kind == 1, fc = c.kind == 1;
            if (fa != fc) return fa ? -1 : 1;
            if (a.lastOpen != c.lastOpen) return Long.compare(c.lastOpen, a.lastOpen);
            return c.id.compareTo(a.id);
        });

        // 顶部「继续阅读」只在书架顶层、且有真书时出现
        hero = null;
        if (folder.isEmpty()) {
            for (Model.Book b : books) {
                if (b.kind != 1 && !b.chapters.isEmpty()) {
                    hero = b;
                    break;
                }
            }
        }

        boolean none = books.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        folderBack.setVisibility(folder.isEmpty() ? View.GONE : View.VISIBLE);
        shelfTitle.setText(folder.isEmpty() ? getString(R.string.app_name) : folderTitle());

        int totalChars = 0;
        for (Model.Book x : books) totalChars += x.charCount();
        meta.setText(none ? (folder.isEmpty() ? "本地书架" : "合集是空的")
                : books.size() + " 本 · " + TextUtil.wordCount(totalChars));
        count.setText(books.size() + " 本");

        bindHero();
        adapter.notifyDataSetChanged();
    }

    private String folderTitle() {
        for (Model.Book b : all) if (b.id.equals(folder)) return b.title;
        return "合集";
    }

    private void bindHero() {
        if (hero == null) {
            heroCard.setVisibility(View.GONE);
            return;
        }
        heroCard.setVisibility(View.VISIBLE);
        heroTitle.setText(hero.title);
        Model.Volume v = hero.volumeOf(hero.chapterIndex);
        String vol = v == null ? "" : v.title.replaceAll("\\s*·\\s*卷$", "") + " · ";
        heroSub.setText(vol + "第 " + Math.min(hero.chapterIndex + 1, hero.chapters.size())
                + " 章 · 共 " + hero.chapters.size() + " 章");
        int pct = pctOf(hero);
        heroPct.setText(pct + "%");
        heroCover.setBook(hero);
        LinearLayout.LayoutParams fl = (LinearLayout.LayoutParams) heroFill.getLayoutParams();
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) heroRest.getLayoutParams();
        fl.weight = Math.max(1, pct);
        rl.weight = Math.max(1, 100 - pct);
        heroFill.setLayoutParams(fl);
        heroRest.setLayoutParams(rl);
    }

    private int pctOf(Model.Book b) {
        int total = Math.max(1, b.chapters.size());
        return (int) Math.round(Math.min(total, b.chapterIndex + 1) * 100.0 / total);
    }

    private void open(Model.Book b) {
        if (b.kind == 1) {
            folder = b.id;
            reload();
            return;
        }
        if (b.chapters.isEmpty()) {
            toast("这本书没有内容");
            return;
        }
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("id", b.id);
        startActivity(i);
    }

    // ---------------- 多选 ----------------

    private void enterPicking(Model.Book first) {
        picking = true;
        picked.clear();
        if (first != null) picked.add(first.id);
        pickBar.setVisibility(View.VISIBLE);
        updatePickUi();
        adapter.notifyDataSetChanged();
    }

    private void togglePick(Model.Book b) {
        if (!picked.remove(b.id)) picked.add(b.id);
        updatePickUi();
        adapter.notifyDataSetChanged();
    }

    private void updatePickUi() {
        int n = picked.size();
        pickCount.setText("已选 " + n + " 本");
        boolean ok = n >= 2;
        pickMerge.setAlpha(ok ? 1f : 0.35f);
        pickFolder.setAlpha(ok ? 1f : 0.35f);
        pickMerge.setEnabled(ok);
        pickFolder.setEnabled(ok);
    }

    private void exitPicking() {
        picking = false;
        picked.clear();
        pickBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private List<Model.Book> pickedBooks() {
        List<Model.Book> out = new ArrayList<>();
        for (Model.Book b : books) if (picked.contains(b.id)) out.add(b);
        for (Model.Book b : all) if (picked.contains(b.id) && !out.contains(b)) out.add(b);
        return out;
    }

    // ---------------- 合集 ----------------

    private void doMerge() {
        if (picked.size() < 2) return;
        final List<Model.Book> sel = pickedBooks();
        StringBuilder def = new StringBuilder();
        for (int i = 0; i < sel.size() && i < 2; i++) {
            if (i > 0) def.append(" + ");
            def.append(sel.get(i).title);
        }
        if (sel.size() > 2) def.append(" 等 ").append(sel.size()).append(" 本");
        promptName("合并成一本", def.toString(), name -> {
            Model.Book mb = new Model.Book();
            mb.id = "c" + System.currentTimeMillis();
            mb.title = name;
            mb.kind = 2;
            int from = 0;
            for (Model.Book s : sel) {
                int n = s.chapters.size();
                if (n == 0) continue;
                for (Model.Chapter ch : s.chapters) mb.chapters.add(new Model.Chapter(ch.title, ch.body));
                mb.volumes.add(new Model.Volume("《" + s.title + "》", "共 " + n + " 章", from, from + n - 1));
                from += n;
                s.mergedInto = mb.id;
                Store.saveProgress(this, s);
            }
            if (mb.chapters.isEmpty()) {
                toast("选中的书都是空的");
                return;
            }
            Store.save(this, mb);
            exitPicking();
            reload();
            toast("已合并成《" + name + "》");
        });
    }

    private void doMakeFolder() {
        if (picked.size() < 2) return;
        final List<Model.Book> sel = pickedBooks();
        promptName("新建合集", sel.get(0).title + " 等 " + sel.size() + " 本", name -> {
            Model.Book fb = new Model.Book();
            fb.id = "f" + System.currentTimeMillis();
            fb.title = name;
            fb.kind = 1;
            Store.save(this, fb);
            for (Model.Book s : sel) {
                s.collectionId = fb.id;
                Store.saveProgress(this, s);
            }
            exitPicking();
            reload();
            toast("已建合集《" + name + "》");
        });
    }

    private void dissolve(Model.Book b) {
        if (b.kind == 1) {
            for (Model.Book x : all) {
                if (b.id.equals(x.collectionId)) {
                    x.collectionId = "";
                    Store.saveProgress(this, x);
                }
            }
        } else if (b.kind == 2) {
            for (Model.Book x : all) {
                if (b.id.equals(x.mergedInto)) {
                    x.mergedInto = "";
                    Store.saveProgress(this, x);
                }
            }
        }
        deleteBook(b);
    }

    // ---------------- 菜单 ----------------

    private void showMenu(final Model.Book b) {
        final String[] items;
        if (b.kind == 1) {
            items = new String[]{"重命名", "解散合集", "删除"};
        } else if (b.kind == 2) {
            items = new String[]{"更换封面", "解散合集", "删除"};
        } else {
            items = new String[]{"更换封面", "加入合集", "删除"};
        }
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(items, (d, which) -> {
                    if (b.kind == 1) {
                        if (which == 0) rename(b);
                        else if (which == 1) confirmDissolve(b);
                        else confirmDelete(b);
                    } else if (b.kind == 2) {
                        if (which == 0) pickCover(b);
                        else if (which == 1) confirmDissolve(b);
                        else confirmDelete(b);
                    } else {
                        if (which == 0) pickCover(b);
                        else if (which == 1) enterPicking(b);
                        else confirmDelete(b);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(final Model.Book b) {
        promptName("重命名", b.title, name -> {
            b.title = name;
            Store.saveProgress(this, b);
            reload();
        });
    }

    private void confirmDissolve(final Model.Book b) {
        new AlertDialog.Builder(this)
                .setTitle(b.kind == 1 ? "解散这个合集？" : "解散这个合并本？")
                .setMessage(b.kind == 1 ? "里面的书会回到书架。" : "原来的几本书会重新出现在书架上。")
                .setNegativeButton("取消", null)
                .setPositiveButton("解散", (d, w) -> {
                    dissolve(b);
                    reload();
                })
                .show();
    }

    private void confirmDelete(final Model.Book b) {
        new AlertDialog.Builder(this)
                .setTitle("删除《" + b.title + "》？")
                .setMessage("会从书架移除，原文文件不会被改动。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    if (b.kind == 1) dissolve(b);
                    else deleteBook(b);
                    reload();
                })
                .show();
    }

    private void deleteBook(Model.Book b) {
        Store.delete(this, b.id);
        for (String dir : new String[]{"bg", "covers"}) {
            File f = new File(new File(getFilesDir(), dir), b.id + ".jpg");
            if (f.exists()) f.delete();
        }
    }

    private interface NameCb {
        void on(String name);
    }

    private void promptName(String title, String def, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setText(def);
        et.setSelection(def == null ? 0 : def.length());
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (s.isEmpty()) return;
                    cb.on(s);
                })
                .show();
    }

    // ---------------- 封面 ----------------

    private void pickCover(Model.Book b) {
        coverFor = b.id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_COVER);
    }

    // ---------------- 导入 ----------------

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/markdown", "text/html",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/epub+zip", "*/*"});
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_COVER) {
            saveCover(uri);
            return;
        }
        if (req != REQ_PICK) return;

        final String name = displayName(uri);
        final String into = folder;
        toast("正在解析 " + name + " …");
        new Thread(() -> {
            try {
                byte[] bytes = read(uri);
                Model.Book book = Importer.read(this, name, bytes);
                if (into != null && !into.isEmpty()) book.collectionId = into;
                Store.save(this, book);
                ui.post(() -> {
                    reload();
                    toast("已导入 " + book.chapters.size() + " 章 / " + book.volumes.size() + " 卷");
                });
            } catch (OutOfMemoryError e) {
                ui.post(() -> toast("文件太大，内存不够了"));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> toast("导入失败：" + msg));
            }
        }).start();
    }

    /** 把选的图裁成 3:4 存到 covers/<id>.jpg，作为这本书的封面。 */
    private void saveCover(final Uri uri) {
        final String id = coverFor;
        if (id == null) return;
        toast("正在处理封面…");
        new Thread(() -> {
            try {
                byte[] raw = read(uri);
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
                int s = 1;
                while (probe.outWidth / (s * 2) >= 720) s *= 2;
                probe.inJustDecodeBounds = false;
                probe.inSampleSize = s;
                Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);
                if (src == null) throw new IllegalStateException("读不到这张图");

                int tw = 600, th = 800;
                float scale = Math.max((float) tw / src.getWidth(), (float) th / src.getHeight());
                int cropW = Math.round(tw / scale);
                int cropH = Math.round(th / scale);
                int left = Math.max(0, (src.getWidth() - cropW) / 2);
                int top = Math.max(0, (src.getHeight() - cropH) / 2);
                Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(out);
                android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                android.graphics.Rect srcRect = new android.graphics.Rect(left, top, left + cropW, top + cropH);
                cv.drawBitmap(src, srcRect, new android.graphics.Rect(0, 0, tw, th), pt);

                File dir = new File(getFilesDir(), "covers");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, id + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                }
                Model.Book b = Store.load(this, id);
                if (b != null) {
                    b.coverFile = f.getName();
                    Store.saveProgress(this, b);
                }
                ui.post(() -> {
                    reload();
                    toast("封面已更新");
                });
            } catch (Exception e) {
                ui.post(() -> toast("设置封面失败：" + e.getMessage()));
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "未命名";
    }

    private byte[] read(Uri uri) throws Exception {
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

    // ---------------- 网格 ----------------

    private class ShelfAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return (books.size() + 1) / 2;
        }

        @Override
        public Object getItem(int i) {
            return books.get(i * 2);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            LinearLayout row;
            BookCell[] cells;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(Theme.dp(MainActivity.this, 20), 0, Theme.dp(MainActivity.this, 20), 0);
                cells = new BookCell[2];
                for (int k = 0; k < 2; k++) {
                    if (k == 1) {
                        Space gap = new Space(MainActivity.this);
                        row.addView(gap, new LinearLayout.LayoutParams(Theme.dp(MainActivity.this, 14), 1));
                    }
                    BookCell cell = new BookCell(MainActivity.this);
                    cells[k] = cell;
                    row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                row.setTag(cells);
                convert = row;
            } else {
                cells = (BookCell[]) convert.getTag();
            }

            int a = i * 2;
            bindCell(cells[0], books.get(a));
            if (a + 1 < books.size()) {
                cells[1].setVisibility(View.VISIBLE);
                bindCell(cells[1], books.get(a + 1));
            } else {
                cells[1].setVisibility(View.INVISIBLE);
            }
            cells[0].setPadding(0, 0, 0, Theme.dp(MainActivity.this, 22));
            cells[1].setPadding(0, 0, 0, Theme.dp(MainActivity.this, 22));
            return convert;
        }
    }

    private void bindCell(final BookCell cell, final Model.Book b) {
        cell.bind(b);
        cell.setPicked(picking && picked.contains(b.id));
        cell.setOnClickListener(v -> {
            if (picking) togglePick(b);
            else open(b);
        });
        cell.setOnLongClickListener(v -> {
            if (picking) togglePick(b);
            else showMenu(b);
            return true;
        });
    }
}
