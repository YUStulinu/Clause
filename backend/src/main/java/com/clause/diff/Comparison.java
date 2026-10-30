package com.clause.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.clause.document.Clause;
import com.clause.document.Contract;

/**
 * Two versions of a contract, clause by clause: which clauses were added, removed, moved or changed,
 * the words that changed, and the hard facts that changed: amounts, rates, terms, a negation, an
 * obligation turned into a permission ("shall" into "may"). Whether a changed clause changed its
 * meaning is decided afterwards, by the analysis service; a changed fact decides it already.
 *
 * Only sentences that exist nowhere in the other version count as changed. Reformatting or moving a
 * clause can shift where one clause ends and the next begins (a heading on a new line, a numbering
 * out of sequence after a move), so the same sentences turn up under another clause; a change made
 * only of such sentences is not a change of what the contract says.
 */
public final class Comparison {

    public record Entry(String id, ClauseAligner.Kind kind, Clause a, Clause b, double similarity, boolean moved,
                        List<WordDiff.Run> diff, List<TextFacts.Change> facts, boolean negationChanged,
                        boolean modalityChanged, List<String> onlyBefore, List<String> onlyAfter,
                        Map<String, Object> meaning) {

        Entry withMeaning(Map<String, Object> m) {
            return new Entry(id, kind, a, b, similarity, moved, diff, facts, negationChanged, modalityChanged,
                    onlyBefore, onlyAfter, m);
        }
    }

    private Comparison() {}

    public static List<Entry> compare(Contract a, Contract b) {
        Set<String> inA = new HashSet<>(), inB = new HashSet<>();
        for (String s : Sentences.split(a.text())) inA.add(Sentences.key(s));
        for (String s : Sentences.split(b.text())) inB.add(Sentences.key(s));
        List<Entry> out = new ArrayList<>();
        int n = 0;
        for (ClauseAligner.Pair p : ClauseAligner.align(a, b)) {
            String id = "p" + n++;
            String ta = p.a() == null ? "" : ClauseAligner.body(a, p.a());
            String tb = p.b() == null ? "" : ClauseAligner.body(b, p.b());
            List<String> onlyA = Sentences.split(ta).stream().filter(s -> !inB.contains(Sentences.key(s))).toList();
            List<String> onlyB = Sentences.split(tb).stream().filter(s -> !inA.contains(Sentences.key(s))).toList();
            ClauseAligner.Kind kind = p.kind();
            boolean moved = p.moved();
            if (kind != ClauseAligner.Kind.SAME && kind != ClauseAligner.Kind.COSMETIC && onlyA.isEmpty() && onlyB.isEmpty()) {
                // Every sentence is still there, somewhere: moved or reformatted.
                kind = ClauseAligner.Kind.COSMETIC;
                moved = moved || p.a() == null || p.b() == null;
            }
            boolean changed = kind == ClauseAligner.Kind.MODIFIED;
            String ua = String.join(" ", onlyA), ub = String.join(" ", onlyB);
            List<WordDiff.Run> diff = changed || kind == ClauseAligner.Kind.COSMETIC && p.a() != null && p.b() != null
                    ? WordDiff.diff(ta, tb) : List.of();
            List<TextFacts.Change> facts = changed ? TextFacts.changes(ua, ub) : List.of();
            boolean negation = changed && TextFacts.negations(ua) != TextFacts.negations(ub);
            boolean modality = changed && !TextFacts.modality(ua).equals(TextFacts.modality(ub));
            out.add(new Entry(id, kind, p.a(), p.b(), p.similarity(), moved, diff, facts, negation, modality, onlyA, onlyB, null));
        }
        return out;
    }

    /** What the analysis service is asked about: the changed clauses, with what is already known. */
    public static List<Map<String, Object>> questions(Contract a, Contract b, List<Entry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.kind() != ClauseAligner.Kind.MODIFIED) continue;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", e.id());
            q.put("before", ClauseAligner.body(a, e.a()));
            q.put("after", ClauseAligner.body(b, e.b()));
            q.put("only_before", e.onlyBefore());
            q.put("only_after", e.onlyAfter());
            q.put("facts", e.facts().stream().map(f -> Map.of("before", f.before() == null ? "" : f.before(),
                    "after", f.after() == null ? "" : f.after())).toList());
            q.put("negation_changed", e.negationChanged());
            q.put("modality_changed", e.modalityChanged());
            out.add(q);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Entry> withMeanings(List<Entry> entries, Map<String, Object> judged) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        if (judged != null && judged.get("judgements") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                byId.put(String.valueOf(m.get("id")), m);
            }
        }
        return entries.stream().map(e -> byId.containsKey(e.id()) ? e.withMeaning(byId.get(e.id())) : e).toList();
    }
}
