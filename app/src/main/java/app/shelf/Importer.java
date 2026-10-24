package app.shelf;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 多格式导入：txt / md / html / docx / pptx / epub / pdf（文字版）。
 * 全部只抽文字——重排版阅读器不支持图片内容。
 */
public class Importer {

    private static final int MAX_ENTRY = 24 * 1024 * 1024;
    private static boolean pdfInited = false;

    public static Model.Book read(Context c, String displayName, byte[] data) throws Exception {
        String name = displayName == null ? "" : displayName;
        String lower = name.toLowerCase(Locale.US);
        String title = name.replaceAll("(?i)\\.(txt|text|md|markdown|html?|docx|pptx|epub|pdf)$", "");
        if (title.trim().isEmpty()) title = "未命名";

        List<Model.Chapter> chapters;
        if (lower.endsWith(".docx")) {
            chapters = docx(data);
        } else if (lower.endsWith(".pptx")) {
            chapters = pptx(data);
        } else if (lower.endsWith(".epub")) {
            chapters = epub(data);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            chapters = fromText(htmlToText(TextUtil.decode(data)), title);
        } else if (lower.endsWith(".pdf")) {
            chapters = pdf(c, data);
        } else {
            chapters = TextUtil.splitChapters(TextUtil.decode(data), title);
        }
        if (chapters == null || chapters.isEmpty()) throw new Exception("这个文件里没有抽到文字");

        Model.Book b = new Model.Book();
        b.id = "b" + System.currentTimeMillis();
        b.title = title;
        b.source = name;
        b.chapters = chapters;
        TextUtil.buildVolumes(b);
        return b;
    }

    private static List<Model.Chapter> fromText(String text, String title) {
        return TextUtil.splitChapters(text, title);
    }

    // ---------------- docx ----------------

    private static List<Model.Chapter> docx(byte[] data) throws Exception {
        Map<String, byte[]> z = unzip(data);
        byte[] doc = z.get("word/document.xml");
        if (doc == null) throw new Exception("不是有效的 docx 文件");
        String xml = new String(doc, StandardCharsets.UTF_8);

        List<String> paras = new ArrayList<>();
        List<Boolean> heads = new ArrayList<>();
        Matcher m = Pattern.compile("<w:p\\b[^>]*>(.*?)</w:p>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String body = m.group(1);
            String text = wtText(body);
            if (text.trim().isEmpty()) continue;
            boolean heading = body.contains("w:pStyle") &&
                    (body.contains("Heading") || body.contains("标题") || body.contains("w:val=\"1\""));
            paras.add(text.trim());
            heads.add(heading);
        }
        if (paras.isEmpty()) throw new Exception("docx 里没有文字");

        int headingCount = 0;
        for (boolean h : heads) if (h) headingCount++;
        if (headingCount >= 2) {
            List<Model.Chapter> out = new ArrayList<>();
            String cur = null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paras.size(); i++) {
                if (heads.get(i)) {
                    if (cur != null) out.add(new Model.Chapter(cur, sb.toString().trim()));
                    cur = paras.get(i);
                    sb.setLength(0);
                } else {
                    sb.append(paras.get(i)).append('\n');
                }
            }
            if (cur != null) out.add(new Model.Chapter(cur, sb.toString().trim()));
            if (!out.isEmpty()) return out;
        }
        return TextUtil.splitChapters(join(paras), "docx");
    }

    private static String wtText(String paraXml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", Pattern.DOTALL).matcher(paraXml);
        while (m.find()) sb.append(xmlUnescape(m.group(1)));
        return sb.toString();
    }

    // ---------------- pptx ----------------

    private static List<Model.Chapter> pptx(byte[] data) throws Exception {
        Map<String, byte[]> z = unzip(data);
        List<int[]> order = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String name : z.keySet()) {
            Matcher m = Pattern.compile("ppt/slides/slide(\\d+)\\.xml").matcher(name);
            if (m.matches()) {
                order.add(new int[]{Integer.parseInt(m.group(1)), names.size()});
                names.add(name);
            }
        }
        if (order.isEmpty()) throw new Exception("不是有效的 pptx 文件");
        order.sort((a, b) -> Integer.compare(a[0], b[0]));

        List<Model.Chapter> out = new ArrayList<>();
        int n = 0;
        for (int[] pair : order) {
            String xml = new String(z.get(names.get(pair[1])), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            Matcher m = Pattern.compile("<a:t>(.*?)</a:t>", Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String t = xmlUnescape(m.group(1)).trim();
                if (!t.isEmpty()) lines.add(t);
            }
            if (lines.isEmpty()) continue;
            n++;
            String head = lines.get(0);
            if (head.length() > 30) head = head.substring(0, 30) + "…";
            StringBuilder body = new StringBuilder();
            for (int i = 1; i < lines.size(); i++) body.append(lines.get(i)).append('\n');
            out.add(new Model.Chapter(head, body.toString().trim()));
        }
        if (out.isEmpty()) throw new Exception("pptx 里没有文字");
        return out;
    }

    // ---------------- epub ----------------

    private static List<Model.Chapter> epub(byte[] data) throws Exception {
        Map<String, byte[]> z = unzip(data);
        byte[] cont = z.get("META-INF/container.xml");
        String opfPath = null;
        if (cont != null) {
            Matcher m = Pattern.compile("full-path=\"([^\"]+)\"").matcher(new String(cont, StandardCharsets.UTF_8));
            if (m.find()) opfPath = m.group(1);
        }
        if (opfPath == null) {
            for (String k : z.keySet()) if (k.endsWith(".opf")) { opfPath = k; break; }
        }
        if (opfPath == null) throw new Exception("不是有效的 epub 文件");

        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        String opf = new String(z.get(opfPath), StandardCharsets.UTF_8);

        Map<String, String> hrefById = new HashMap<>();
        Matcher mi = Pattern.compile("<item\\b[^>]*>").matcher(opf);
        while (mi.find()) {
            String tag = mi.group();
            String id = attr(tag, "id");
            String href = attr(tag, "href");
            if (id != null && href != null) hrefById.put(id, href);
        }

        List<String> docs = new ArrayList<>();
        Matcher ms = Pattern.compile("<itemref\\b[^>]*idref=\"([^\"]+)\"").matcher(opf);
        while (ms.find()) {
            String href = hrefById.get(ms.group(1));
            if (href != null) docs.add(href);
        }
        if (docs.isEmpty()) {
            for (String k : z.keySet()) if (k.toLowerCase(Locale.US).endsWith(".xhtml") || k.toLowerCase(Locale.US).endsWith(".html")) docs.add(k.startsWith(base) ? k.substring(base.length()) : k);
        }

        List<Model.Chapter> out = new ArrayList<>();
        int idx = 0;
        for (String href : docs) {
            String path = normalize(base + href);
            byte[] raw = z.get(path);
            if (raw == null) raw = z.get(base + href);
            if (raw == null) continue;
            String html = new String(raw, StandardCharsets.UTF_8);
            String text = htmlToText(html);
            if (text.trim().isEmpty()) continue;
            idx++;
            String rawTitle = firstTag(html, "title");
            if (rawTitle == null || rawTitle.trim().isEmpty()) rawTitle = firstTag(html, "h1");
            if (rawTitle == null || rawTitle.trim().isEmpty()) rawTitle = firstTag(html, "h2");
            if (rawTitle == null || rawTitle.trim().isEmpty()) rawTitle = "第 " + idx + " 节";
            rawTitle = rawTitle.replaceAll("\\s+", " ").trim();
            // 正文第一行常常就是标题，去掉重复
            String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text.trim();
            if (firstLine.equals(rawTitle) || (firstLine.length() > 2 && rawTitle.startsWith(firstLine))) {
                int cut = text.indexOf('\n');
                if (cut > 0) text = text.substring(cut + 1).trim();
            }
            String t = rawTitle;
            if (t.length() > 40) t = t.substring(0, 40) + "…";
            out.add(new Model.Chapter(t, text));
        }
        if (out.isEmpty()) throw new Exception("epub 里没有抽到文字");
        return out;
    }

    // ---------------- pdf ----------------

    private static List<Model.Chapter> pdf(Context c, byte[] data) throws Exception {
        if (!pdfInited) {
            PDFBoxResourceLoader.init(c.getApplicationContext());
            pdfInited = true;
        }
        PDDocument doc = null;
        try {
            doc = PDDocument.load(data);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            text = text.replace("\r\n", "\n").replace('\r', '\n').replace("\f", "\n\n");
            text = text.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
            if (text.trim().isEmpty()) throw new Exception("这个 PDF 没有文字层（可能是扫描版）");
            String title = doc.getDocumentInformation() != null ? doc.getDocumentInformation().getTitle() : null;
            return TextUtil.splitChapters(text, title == null ? "PDF" : title);
        } finally {
            if (doc != null) {
                try {
                    doc.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------------- 工具 ----------------

    private static Map<String, byte[]> unzip(byte[] data) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        byte[] buf = new byte[65536];
        long total = 0;
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) {
                bo.write(buf, 0, n);
                total += n;
                if (bo.size() > MAX_ENTRY || total > 64L * 1024 * 1024) throw new Exception("文件解压后过大");
            }
            out.put(e.getName(), bo.toByteArray());
        }
        zin.close();
        return out;
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        return sb.toString();
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String firstTag(String html, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return htmlToText(m.group(1));
        return null;
    }

    private static String join(List<String> paras) {
        StringBuilder sb = new StringBuilder();
        for (String p : paras) sb.append(p).append("\n\n");
        return sb.toString();
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<head\\b[^>]*>.*?</head>", " ");
        s = s.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote|pre)>", "\n");
        s = s.replaceAll("(?i)<(h[1-6])[^>]*>", "\n");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = xmlUnescape(s);
        s = s.replace('\u00A0', ' ').replace('\u3000', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    static String xmlUnescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '&') {
                sb.append(ch);
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 10) {
                sb.append(ch);
                continue;
            }
            String ent = s.substring(i + 1, semi);
            if (ent.equals("amp")) sb.append('&');
            else if (ent.equals("lt")) sb.append('<');
            else if (ent.equals("gt")) sb.append('>');
            else if (ent.equals("quot")) sb.append('"');
            else if (ent.equals("apos")) sb.append('\'');
            else if (ent.equals("nbsp")) sb.append(' ');
            else if (ent.startsWith("#x") || ent.startsWith("#X")) {
                try {
                    sb.append((char) Integer.parseInt(ent.substring(2), 16));
                } catch (Exception e) {
                    sb.append('&').append(ent).append(';');
                }
            } else if (ent.startsWith("#")) {
                try {
                    sb.append((char) Integer.parseInt(ent.substring(1)));
                } catch (Exception e) {
                    sb.append('&').append(ent).append(';');
                }
            } else {
                sb.append('&').append(ent).append(';');
            }
            i = semi;
        }
        return sb.toString();
    }
}
