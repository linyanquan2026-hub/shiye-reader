package app.shelf;

import java.util.ArrayList;
import java.util.List;

public class Model {

    public static class Chapter {
        public String title = "";
        public String body = "";

        public Chapter() {
        }

        public Chapter(String t, String b) {
            title = t;
            body = b;
        }
    }

    /** 一卷 = 一段连续的故事（若干章），带自动生成的简介。 */
    public static class Volume {
        public String title = "";
        public String summary = "";
        public int from = 0;   // 起始章（含）
        public int to = 0;     // 结束章（含）

        public Volume() {
        }

        public Volume(String t, String s, int f, int to) {
            title = t;
            summary = s;
            from = f;
            this.to = to;
        }

        public int count() {
            return to - from + 1;
        }
    }

    public static class Book {
        public String id = "";
        public String title = "";
        public String source = "";
        public int chapterIndex = 0;
        public int pageIndex = 0;
        public int scrollY = 0;
        public int fontSize = 19;
        public float lineSpacing = 1.75f;
        public String bgFile = "";
        public int bgScrim = 68;
        public long lastOpen = 0L;
        public String coverFile = "";
        /** 0 普通书 / 1 文件夹（合集）/ 2 合并本 */
        public int kind = 0;
        /** 属于哪个文件夹；空 = 书架顶层 */
        public String collectionId = "";
        /** 被合并进哪个合集；非空则不在书架显示 */
        public String mergedInto = "";
        /** 文件夹里有多少本（运行时算，不落盘） */
        public int childCount = 0;
        public List<Chapter> chapters = new ArrayList<>();
        public List<Volume> volumes = new ArrayList<>();

        public int charCount() {
            int n = 0;
            for (Chapter c : chapters) n += c.body.length();
            return n;
        }

        public Volume volumeOf(int chapter) {
            for (Volume v : volumes) if (chapter >= v.from && chapter <= v.to) return v;
            return null;
        }
    }
}
