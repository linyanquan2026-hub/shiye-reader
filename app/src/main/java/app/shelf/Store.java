package app.shelf;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Store {

    /** 进程内缓存：回到书架、再进阅读页都不用重新解析 JSON。 */
    private static final Map<String, Model.Book> CACHE = new HashMap<>();

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "books");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileFor(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    private static File progressFor(Context c, String id) {
        return new File(dir(c), id + ".progress");
    }

    /** 全量保存（导入时用）。 */
    public static void save(Context c, Model.Book b) {
        writeBook(c, b);
        writeProgress(c, b);
        CACHE.put(b.id, b);
    }

    /** 只写阅读进度，几十字节，不阻塞退出/返回。 */
    public static void saveProgress(Context c, Model.Book b) {
        writeProgress(c, b);
        CACHE.put(b.id, b);
    }

    private static void writeProgress(Context c, Model.Book b) {
        try {
            JSONObject o = new JSONObject();
            o.put("chapter", b.chapterIndex);
            o.put("page", b.pageIndex);
            o.put("size", b.fontSize);
            o.put("spacing", (double) b.lineSpacing);
            o.put("bg", b.bgFile == null ? "" : b.bgFile);
            o.put("scrim", b.bgScrim);
            o.put("open", b.lastOpen);
            o.put("cover", b.coverFile == null ? "" : b.coverFile);
            o.put("kind", b.kind);
            o.put("coll", b.collectionId == null ? "" : b.collectionId);
            o.put("merged", b.mergedInto == null ? "" : b.mergedInto);
            writeFile(progressFor(c, b.id), o.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeBook(Context c, Model.Book b) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", b.id);
            o.put("title", b.title);
            o.put("source", b.source);

            JSONArray cs = new JSONArray();
            for (Model.Chapter ch : b.chapters) {
                JSONObject cj = new JSONObject();
                cj.put("t", ch.title);
                cj.put("b", ch.body);
                cs.put(cj);
            }
            o.put("chapters", cs);

            JSONArray vs = new JSONArray();
            for (Model.Volume v : b.volumes) {
                JSONObject vj = new JSONObject();
                vj.put("t", v.title);
                vj.put("s", v.summary);
                vj.put("f", v.from);
                vj.put("to", v.to);
                vs.put(vj);
            }
            o.put("volumes", vs);
            writeFile(fileFor(c, b.id), o.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writeFile(File dst, String content) throws Exception {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.close();
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            // 个别机型 rename 会失败，退回直接写
            FileOutputStream o2 = new FileOutputStream(dst);
            o2.write(content.getBytes(StandardCharsets.UTF_8));
            o2.close();
            tmp.delete();
        }
    }

    public static Model.Book load(Context c, String id) {
        Model.Book cached = CACHE.get(id);
        if (cached != null) return cached;
        try {
            File f = fileFor(c, id);
            if (!f.exists()) return null;
            String json = new String(readAll(f), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            Model.Book b = new Model.Book();
            b.id = o.optString("id");
            b.title = o.optString("title");
            b.source = o.optString("source");
            // 兼容老格式：进度写在书里
            b.chapterIndex = o.optInt("chapter", 0);
            b.pageIndex = o.optInt("page", 0);
            b.scrollY = o.optInt("scroll", 0);
            b.fontSize = o.optInt("size", 19);
            b.lineSpacing = (float) o.optDouble("spacing", 1.75);
            b.bgFile = o.optString("bg", "");
            b.bgScrim = o.optInt("scrim", 68);

            JSONArray cs = o.optJSONArray("chapters");
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject cj = cs.getJSONObject(i);
                    b.chapters.add(new Model.Chapter(cj.optString("t"), cj.optString("b")));
                }
            }
            JSONArray vs = o.optJSONArray("volumes");
            if (vs != null) {
                for (int i = 0; i < vs.length(); i++) {
                    JSONObject vj = vs.getJSONObject(i);
                    b.volumes.add(new Model.Volume(vj.optString("t"), vj.optString("s"),
                            vj.optInt("f"), vj.optInt("to")));
                }
            }
            applyProgress(c, b);
            CACHE.put(id, b);
            return b;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static void applyProgress(Context c, Model.Book b) {
        File pf = progressFor(c, b.id);
        if (!pf.exists()) return;
        try {
            JSONObject o = new JSONObject(new String(readAll(pf), StandardCharsets.UTF_8));
            b.chapterIndex = o.optInt("chapter", b.chapterIndex);
            b.pageIndex = o.optInt("page", b.pageIndex);
            b.fontSize = o.optInt("size", b.fontSize);
            b.lineSpacing = (float) o.optDouble("spacing", b.lineSpacing);
            b.bgFile = o.optString("bg", b.bgFile);
            b.bgScrim = o.optInt("scrim", b.bgScrim);
            b.lastOpen = o.optLong("open", b.lastOpen);
            b.coverFile = o.optString("cover", b.coverFile);
            b.kind = o.optInt("kind", b.kind);
            b.collectionId = o.optString("coll", b.collectionId);
            b.mergedInto = o.optString("merged", b.mergedInto);
        } catch (Exception ignored) {
        }
    }

    public static List<Model.Book> all(Context c) {
        List<Model.Book> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - 5);
            Model.Book b = load(c, id);
            if (b == null) continue;
            // 文件夹没有章节也要能显示
            if (b.kind == 1 || !b.chapters.isEmpty()) out.add(b);
        }
        out.sort((x, y) -> y.id.compareTo(x.id));
        return out;
    }

    public static void delete(Context c, String id) {
        CACHE.remove(id);
        File f = fileFor(c, id);
        if (f.exists()) f.delete();
        File p = progressFor(c, id);
        if (p.exists()) p.delete();
    }

    public static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) break;
            off += n;
        }
        in.close();
        return buf;
    }
}
