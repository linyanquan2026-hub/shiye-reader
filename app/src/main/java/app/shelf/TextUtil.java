package app.shelf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextUtil {

    private static final Pattern HEAD = Pattern.compile(
            "^\\s*(第\\s*[0-9零〇一二三四五六七八九十百千万两]{1,8}\\s*[章节回话幕]"
                    + "|序章|序言|楔子|引子|尾声|后记|番外|Chapter\\s*\\d+)"
                    + "\\s*[：:、.．\\-—·]?\\s*[^\\n]{0,40}$");

    private static final Pattern VOL_HEAD = Pattern.compile(
            "^\\s*第\\s*[0-9零〇一二三四五六七八九十百千两]{1,6}\\s*[卷部篇]\\s*[^\\n]{0,30}$");

    public static String decode(byte[] data) {
        String s = tryCharset(data, StandardCharsets.UTF_8);
        if (s != null) return s;
        s = tryCharset(data, Charset.forName("GBK"));
        if (s != null) return s;
        s = tryCharset(data, Charset.forName("GB18030"));
        if (s != null) return s;
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String tryCharset(byte[] data, Charset cs) {
        try {
            java.nio.charset.CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String s = dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
            // 明显的乱码特征：替换符或大量私有区字符
            if (s.indexOf('\uFFFD') >= 0) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<Model.Chapter> splitChapters(String raw, String fallbackTitle) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        String[] lines = text.split("\n", -1);
        List<Model.Chapter> out = new ArrayList<>();

        int start = -1;
        String curTitle = null;
        StringBuilder body = new StringBuilder();
        int prefaceStart = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            boolean isHead = t.length() > 0 && t.length() <= 44 && HEAD.matcher(t).matches()
                    && !t.contains("。") && !t.contains("，");
            if (isHead) {
                if (start >= 0) {
                    out.add(new Model.Chapter(curTitle, clean(body.toString())));
                } else {
                    String pre = join(lines, prefaceStart, i);
                    if (pre.trim().length() > 0) out.add(new Model.Chapter("序", clean(pre)));
                }
                curTitle = t;
                start = i;
                body.setLength(0);
            } else if (start >= 0) {
                body.append(line).append('\n');
            }
        }
        if (start >= 0) out.add(new Model.Chapter(curTitle, clean(body.toString())));

        if (out.isEmpty()) {
            // 没有章节标记：按 3000 字切
            int n = 0;
            for (int i = 0; i < text.length(); i += 3000) {
                n++;
                String part = text.substring(i, Math.min(text.length(), i + 3000));
                out.add(new Model.Chapter("第 " + n + " 节", clean(part)));
            }
        }
        return out;
    }

    private static String join(String[] lines, int a, int b) {
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < b; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private static String clean(String s) {
        String t = s.replace("\n{3,}", "\n\n").trim();
        return t;
    }

    // ---------- 自动分卷 + 摘要 ----------

    public static void buildVolumes(Model.Book book) {
        List<Model.Chapter> chs = book.chapters;
        book.volumes.clear();
        if (chs.isEmpty()) return;

        // 1) 文本里自带「第N卷/部/篇」时直接采用
        List<Integer> marks = new ArrayList<>();
        for (int i = 0; i < chs.size(); i++) {
            if (VOL_HEAD.matcher(chs.get(i).title).matches()) marks.add(i);
        }
        if (marks.size() >= 2) {
            for (int k = 0; k < marks.size(); k++) {
                int from = marks.get(k);
                int to = (k + 1 < marks.size() ? marks.get(k + 1) - 1 : chs.size() - 1);
                if (to < from) continue;
                book.volumes.add(new Model.Volume(chs.get(from).title, summarize(chs, from, to), from, to));
            }
            int last = book.volumes.get(book.volumes.size() - 1).to;
            if (last < chs.size() - 1) {
                int from = last + 1;
                book.volumes.add(new Model.Volume("余下", summarize(chs, from, chs.size() - 1), from, chs.size() - 1));
            }
            return;
        }

        // 2) 用标题主题词聚类：主题突然变了就起一个新卷
        int from = 0;
        Set<String> volTokens = new HashSet<>();
        for (int i = 1; i < chs.size(); i++) {
            Set<String> cur = tokens(chs.get(i).title);
            cur.addAll(bodyKeywords(chs.get(i)));
            double overlap = cur.isEmpty() || volTokens.isEmpty() ? 1.0 : jaccard(cur, volTokens);
            int size = i - from;
            boolean bigBreak = size >= 5 && overlap < 0.12;
            boolean tooLong = size >= 18;
            if (bigBreak || tooLong) {
                book.volumes.add(new Model.Volume(volumeName(chs, from, i - 1), summarize(chs, from, i - 1), from, i - 1));
                from = i;
                volTokens = new HashSet<>();
            }
            volTokens.addAll(cur);
        }
        book.volumes.add(new Model.Volume(volumeName(chs, from, chs.size() - 1), summarize(chs, from, chs.size() - 1), from, chs.size() - 1));

        // 3) 单章卷合并掉，避免目录碎成一片
        List<Model.Volume> merged = new ArrayList<>();
        for (Model.Volume v : book.volumes) {
            if (v.count() < 4 && !merged.isEmpty() && merged.get(merged.size() - 1).count() < 16) {
                Model.Volume p = merged.get(merged.size() - 1);
                p.to = v.to;
                p.summary = summarize(chs, p.from, p.to);
            } else {
                merged.add(v);
            }
        }
        book.volumes = merged;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        return (double) inter / (double) (a.size() + b.size() - inter);
    }

    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
            "的", "了", "是", "在", "我", "你", "他", "她", "和", "与", "就", "都", "也", "上", "下", "中",
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "章", "节", "回"));

    /** 从章节标题里取主题词（2 字组合，去掉编号和常见虚词）。 */
    private static Set<String> tokens(String title) {
        String t = title.replaceAll("^第\\s*[0-9零〇一二三四五六七八九十百千万两]+\\s*[章节回话幕]", "")
                .replaceAll("^第\\s*[0-9零〇一二三四五六七八九十百千两]+\\s*[卷部篇]", "")
                .replaceAll("[\\s\\p{Punct}·、，。！？：；（）()《》\"'“”‘’—\\-]", "");
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 <= t.length(); i++) {
            String bg = t.substring(i, i + 2);
            if (STOP.contains(bg.substring(0, 1)) || STOP.contains(bg.substring(1))) continue;
            out.add(bg);
        }
        return out;
    }

    /** 正文高频 2 字词，用来判断「这一段在讲什么」，比只看标题准。 */
    private static Set<String> bodyKeywords(Model.Chapter c) {
        Map<String, Integer> freq = new HashMap<>();
        String t = c.body;
        int step = Math.max(1, t.length() / 4000);
        for (int i = 0; i + 2 <= t.length(); i += step) {
            String bg = t.substring(i, i + 2);
            if (bg.charAt(0) < 0x4E00 || bg.charAt(1) < 0x4E00) continue;
            if (STOP.contains(bg.substring(0, 1)) || STOP.contains(bg.substring(1))) continue;
            freq.merge(bg, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(freq.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        Set<String> out = new HashSet<>();
        for (int i = 0; i < Math.min(8, es.size()); i++) out.add(es.get(i).getKey());
        return out;
    }

    private static String volumeName(List<Model.Chapter> chs, int from, int to) {
        Map<String, Integer> freq = new HashMap<>();
        for (int i = from; i <= to; i++) {
            for (String t : tokens(chs.get(i).title)) freq.merge(t, 1, Integer::sum);
        }
        String best = null;
        int bestN = 1;
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        int idx = from + 1;
        int half = Math.max(2, (to - from + 1) * 4 / 10);
        if (best != null && bestN >= half) return best + " · 卷";
        String t0 = chs.get(from).title
                .replaceAll("^第\\s*[0-9零〇一二三四五六七八九十百千万两]+\\s*[章节回话幕]", "")
                .replaceAll("[\\p{Punct}·、，。：；（）()《》\\s]", "").replaceAll("[0-9０-９]+$", "").trim();
        if (t0.length() > 9) t0 = t0.substring(0, 9);
        if (t0.length() >= 3) return t0 + " · 卷";
        return "第 " + idx + " 卷";
    }

    /** 抽取式摘要：挑出这一卷里最能代表剧情的两句话（离线，不联网）。 */
    public static String summarize(List<Model.Chapter> chs, int from, int to) {
        if (from > to) return "";
        StringBuilder all = new StringBuilder();
        for (int i = from; i <= to && i < chs.size(); i++) all.append(chs.get(i).body).append('\n');

        List<String> sentences = new ArrayList<>();
        for (String s : all.toString().split("[。！？!?\\n]")) {
            String t = s.trim();
            if (t.length() >= 8 && t.length() <= 80) sentences.add(t);
            if (sentences.size() > 400) break;
        }
        if (sentences.isEmpty()) return "";

        Map<String, Integer> freq = new HashMap<>();
        for (String s : sentences) {
            for (int i = 0; i + 2 <= s.length(); i++) {
                String bg = s.substring(i, i + 2);
                if (STOP.contains(bg.substring(0, 1)) || STOP.contains(bg.substring(1))) continue;
                freq.merge(bg, 1, Integer::sum);
            }
        }

        int n = Math.min(3, Math.max(2, (to - from + 1) / 6));
        List<String> picked = new ArrayList<>();
        // 第一卷的第一句通常交代人物，先放进去
        picked.add(sentences.get(0));

        while (picked.size() < n) {
            String bestS = null;
            double bestScore = -1;
            for (String s : sentences) {
                if (picked.contains(s)) continue;
                double score = 0;
                for (int i = 0; i + 2 <= s.length(); i++) {
                    Integer f = freq.get(s.substring(i, i + 2));
                    if (f != null) score += f;
                }
                score = score / Math.max(1, s.length()) * 10;
                if (score > bestScore) {
                    bestScore = score;
                    bestS = s;
                }
            }
            if (bestS == null) break;
            picked.add(bestS);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            String s = picked.get(i);
            if (i > 0) sb.append("；");
            sb.append(s);
        }
        String out = sb.toString().replaceAll("\\s+", "");
        if (out.length() > 110) out = out.substring(0, 110) + "…";
        return out;
    }

    public static String wordCount(int chars) {
        if (chars >= 10000) return String.format(java.util.Locale.US, "%.1f 万字", chars / 10000.0);
        return chars + " 字";
    }
}
