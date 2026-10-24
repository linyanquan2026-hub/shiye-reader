package app.shelf;

import android.text.StaticLayout;

import java.util.ArrayList;

/** 分页计算：正文按行高切成若干页，返回每页首行的行号。
 *  阅读器和「搜索页码」共用同一套算法，保证页码一致。 */
public class Paginator {

    public static int[] pageLineStarts(StaticLayout lay, int pageHeight) {
        if (lay == null || pageHeight <= 0) return new int[]{0};
        int n = lay.getLineCount();
        if (n <= 0) return new int[]{0};
        ArrayList<Integer> starts = new ArrayList<>();
        starts.add(0);
        int startLine = 0;
        for (int l = 1; l < n; l++) {
            int h = lay.getLineBottom(l) - lay.getLineTop(startLine);
            if (h > pageHeight) {
                starts.add(l);
                startLine = l;
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    /** 某个字符落在第几页（0 起）。 */
    public static int pageOfChar(StaticLayout lay, int[] lineStarts, int offset) {
        int best = 0;
        for (int p = 0; p < lineStarts.length; p++) {
            if (lay.getLineStart(lineStarts[p]) <= offset) best = p;
            else break;
        }
        return best;
    }
}
