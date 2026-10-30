package com.clause.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A word-level diff of two texts (Myers' O(ND) algorithm), as runs of kept, deleted and inserted
 * text. Tokens carry their trailing whitespace, so the runs put back together are the texts exactly;
 * whitespace itself never counts as a difference.
 */
public final class WordDiff {

    public enum Kind { SAME, DELETED, INSERTED }

    public record Run(Kind kind, String text) {}

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,'’][\\p{L}\\p{N}]+)*\\s*|[^\\p{L}\\p{N}\\s]\\s*|\\s+");

    private WordDiff() {}

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static List<Run> diff(String a, String b) {
        List<String> x = tokens(a), y = tokens(b);
        int n = x.size(), m = y.size(), max = n + m;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        outer:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int i;
                if (k == -d || (k != d && v[max + k - 1] < v[max + k + 1])) i = v[max + k + 1];
                else i = v[max + k - 1] + 1;
                int j = i - k;
                while (i < n && j < m && same(x.get(i), y.get(j))) { i++; j++; }
                v[max + k] = i;
                if (i >= n && j >= m) { found = d; break outer; }
            }
        }
        // Walk back through the trace to recover the edit script.
        List<Run> rev = new ArrayList<>();
        int i = n, j = m;
        for (int d = found; d > 0; d--) {
            int[] pv = trace.get(d);
            int k = i - j;
            int prevK = (k == -d || (k != d && pv[max + k - 1] < pv[max + k + 1])) ? k + 1 : k - 1;
            int pi = pv[max + prevK], pj = pi - prevK;
            while (i > pi && j > pj) { rev.add(new Run(Kind.SAME, y.get(j - 1))); i--; j--; }
            if (i == pi) { rev.add(new Run(Kind.INSERTED, y.get(j - 1))); j--; }
            else { rev.add(new Run(Kind.DELETED, x.get(i - 1))); i--; }
        }
        while (i > 0 && j > 0) { rev.add(new Run(Kind.SAME, y.get(j - 1))); i--; j--; }
        List<Run> out = new ArrayList<>();
        for (int r = rev.size() - 1; r >= 0; r--) {
            Run run = rev.get(r);
            if (!out.isEmpty() && out.get(out.size() - 1).kind() == run.kind()) {
                out.set(out.size() - 1, new Run(run.kind(), out.get(out.size() - 1).text() + run.text()));
            } else {
                out.add(run);
            }
        }
        return out;
    }

    private static boolean same(String a, String b) {
        return a.strip().equals(b.strip());
    }

    /** Only whitespace, letter case and punctuation differ (or nothing). */
    public static boolean cosmetic(String a, String b) {
        return normal(a).equals(normal(b));
    }

    static String normal(String s) {
        return s.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
