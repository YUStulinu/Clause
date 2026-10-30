package com.clause.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.clause.document.Clause;
import com.clause.document.Contract;

/**
 * Pairs the clauses of two versions of a contract by what they say, not by their numbers: a clause
 * that was renumbered or moved is still the same clause. Similarity is the overlap of word pairs
 * (Dice), with clause numbers and figures masked so that a changed amount does not hide a match.
 * Pairs are taken greedily from the most similar down; then clauses left over with the same number
 * and a few words in common are paired as rewritten. What is left after that was added or removed.
 */
public final class ClauseAligner {

    public enum Kind { SAME, COSMETIC, MODIFIED, ADDED, REMOVED }

    public record Pair(Kind kind, Clause a, Clause b, double similarity, boolean moved) {}

    static final double MATCH = 0.35;
    static final double REWRITTEN = 0.15;

    private ClauseAligner() {}

    public static List<Pair> align(Contract a, Contract b) {
        List<Clause> ca = content(a), cb = content(b);
        List<Set<String>> sa = ca.stream().map(c -> shingles(body(a, c))).toList();
        List<Set<String>> sb = cb.stream().map(c -> shingles(body(b, c))).toList();
        record Candidate(int i, int j, double score) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < ca.size(); i++) {
            for (int j = 0; j < cb.size(); j++) {
                double s = dice(sa.get(i), sb.get(j));
                // Same heading: a small push, for clauses rewritten a lot.
                if (!ca.get(i).title().isBlank() && ca.get(i).title().equalsIgnoreCase(cb.get(j).title())) s += 0.15;
                if (s >= MATCH) candidates.add(new Candidate(i, j, s));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        int[] matchA = new int[ca.size()];
        int[] matchB = new int[cb.size()];
        Arrays.fill(matchA, -1);
        Arrays.fill(matchB, -1);
        Map<Integer, Double> score = new HashMap<>();
        for (Candidate c : candidates) {
            if (matchA[c.i()] < 0 && matchB[c.j()] < 0) {
                matchA[c.i()] = c.j();
                matchB[c.j()] = c.i();
                score.put(c.i(), Math.min(1.0, c.score()));
            }
        }
        // Heavily rewritten clauses: still unpaired, same number, some words in common.
        for (int i = 0; i < ca.size(); i++) {
            if (matchA[i] >= 0 || ca.get(i).number().isEmpty()) continue;
            for (int j = 0; j < cb.size(); j++) {
                if (matchB[j] >= 0 || !ca.get(i).number().equals(cb.get(j).number())) continue;
                double s = dice(sa.get(i), sb.get(j));
                if (s >= REWRITTEN) {
                    matchA[i] = j;
                    matchB[j] = i;
                    score.put(i, s);
                    break;
                }
            }
        }
        boolean[] moved = moved(matchA);

        // In the order of the new version, with removed clauses where they used to be.
        List<Pair> out = new ArrayList<>();
        int nextA = 0;
        for (int j = 0; j < cb.size(); j++) {
            int i = matchB[j];
            if (i >= 0 && !moved[i]) {
                for (; nextA < i; nextA++) if (matchA[nextA] < 0) out.add(new Pair(Kind.REMOVED, ca.get(nextA), null, 0, false));
                nextA = Math.max(nextA, i + 1);
            }
            if (i < 0) {
                out.add(new Pair(Kind.ADDED, null, cb.get(j), 0, false));
                continue;
            }
            String ta = body(a, ca.get(i)), tb = body(b, cb.get(j));
            Kind kind = ta.equals(tb) ? Kind.SAME : WordDiff.cosmetic(ta, tb) ? Kind.COSMETIC : Kind.MODIFIED;
            out.add(new Pair(kind, ca.get(i), cb.get(j), score.get(i), moved[i]));
        }
        for (; nextA < ca.size(); nextA++) if (matchA[nextA] < 0) out.add(new Pair(Kind.REMOVED, ca.get(nextA), null, 0, false));
        return out;
    }

    /** The clauses with something in them besides a heading. */
    static List<Clause> content(Contract c) {
        return c.clauses().stream().filter(cl -> !body(c, cl).isBlank()).toList();
    }

    /** A clause's text without its own number, which renumbering changes. */
    public static String body(Contract c, Clause cl) {
        String t = cl.text(c.text()).strip();
        if (!cl.number().isEmpty() && t.startsWith(cl.number())) t = t.substring(cl.number().length());
        return t.replaceFirst("^(?i)(article|articolul|art\\.?|section|sec[țt]iunea|§)\\s*", "").replaceFirst("^[\\s.):\\-–—]+", "").strip();
    }

    /** Clauses whose position relative to the others changed: those outside the longest run kept in order. */
    static boolean[] moved(int[] matchA) {
        int n = matchA.length;
        int[] len = new int[n], prev = new int[n];
        int best = -1;
        for (int i = 0; i < n; i++) {
            prev[i] = -1;
            if (matchA[i] < 0) continue;
            len[i] = 1;
            for (int p = 0; p < i; p++) {
                if (matchA[p] >= 0 && matchA[p] < matchA[i] && len[p] + 1 > len[i]) {
                    len[i] = len[p] + 1;
                    prev[i] = p;
                }
            }
            if (best < 0 || len[i] > len[best]) best = i;
        }
        boolean[] inOrder = new boolean[n];
        for (int i = best; i >= 0; i = prev[i]) inOrder[i] = true;
        boolean[] moved = new boolean[n];
        for (int i = 0; i < n; i++) moved[i] = matchA[i] >= 0 && !inOrder[i];
        return moved;
    }

    static Set<String> shingles(String text) {
        String[] w = text.toLowerCase().replaceAll("\\d+([.,]\\d+)*", "#").split("[^\\p{L}#]+");
        Set<String> out = new HashSet<>();
        String prev = null;
        for (String t : w) {
            if (t.isEmpty()) continue;
            out.add(t);
            if (prev != null) out.add(prev + " " + t);
            prev = t;
        }
        return out;
    }

    static double dice(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        Set<String> small = a.size() < b.size() ? a : b, large = small == a ? b : a;
        for (String s : small) if (large.contains(s)) common++;
        return 2.0 * common / (a.size() + b.size());
    }
}
