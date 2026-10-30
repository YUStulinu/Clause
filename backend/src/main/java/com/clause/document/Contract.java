package com.clause.document;

import java.util.List;

/**
 * A contract as read: its whole text, and the clauses it was split into. Every position (a clause's
 * start and end, a quote's span) is a character offset into {@code text}, so the frontend can
 * highlight exactly what a statement rests on.
 */
public record Contract(String id, String name, String language, String text, List<Clause> clauses) {

    public Clause clause(String clauseId) {
        return clauses.stream().filter(c -> c.id().equals(clauseId)).findFirst().orElse(null);
    }

    /** The clause holding a character position. */
    public Clause clauseAt(int position) {
        for (Clause c : clauses) {
            if (position >= c.start() && position < c.end()) return c;
        }
        return null;
    }
}
