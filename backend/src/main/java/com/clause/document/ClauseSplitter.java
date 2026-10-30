package com.clause.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a contract into clauses at its headings, without changing a character of the text (every
 * position stays a position in the original). A heading starts
 * <ul>
 *   <li>with a keyword and a number: "Article 4", "ARTICOLUL IV", "Art. 4", "Section 4.2", "Capitolul 2", "§ 3";</li>
 *   <li>or with a clause number: "4.", "4.2", "4.2.1)". It counts only when it follows on from the
 *       numbers before it, so that "3. The rent is 1.500 lei" after "2." is a clause and an amount or
 *       a year is not.</li>
 * </ul>
 * Both are found at the start of a line, and also inside a line right after the end of a sentence:
 * many contracts come out of PDF extraction as one long line ("... as follows: 1. Definitions. ...
 * 2. Territory. ..."). A short line in capitals ("TERMINATION", "OBIECTUL CONTRACTULUI") titles the
 * clause right after it, or is a heading of its own. Each clause runs to the next heading; text
 * before the first one is the preamble.
 */
public final class ClauseSplitter {

    private static final String KEYWORDS = "(?:ARTICLE|Article|ARTICOLUL|Articolul|ARTICOL|Articol|Art\\.|ART\\.|SECTION|Section"
            + "|SECȚIUNEA|Secțiunea|SECTIUNEA|Sectiunea|CHAPTER|Chapter|CAPITOLUL|Capitolul|CLAUSE|Clause|CLAUZA|Clauza|ANNEX|Annex|ANEXA|Anexa)";
    private static final String NUMBER = "([0-9]{1,3}(?:\\.[0-9]{1,3})*|[IVXLC]{1,7})";
    /** Where a heading may start: a line start, after a sentence ends, or after a word in capitals ("TERMS 1."). */
    private static final String START = "(?:(?<=^)|(?<=\\n)|(?<=[.;:!?\"”)]\\s{1,3})|(?<=\\b\\p{Lu}{3,30}\\s))";

    private static final Pattern KEYWORD = Pattern.compile(START + KEYWORDS + "\\s+" + NUMBER + "\\b[.:)\\-–—]?");
    private static final Pattern SECTION_SIGN = Pattern.compile(START + "§\\s*([0-9]{1,3}(?:\\.[0-9]{1,3})*)\\b[.:)]?");
    // Inside a line only "4." or "4.2": "5)" there is usually an item of a list.
    private static final Pattern NUMBERED = Pattern.compile(START + "([0-9]{1,2}(?:\\.[0-9]{1,2}){0,3})(\\.)?\\s+(?=[\\p{Lu}\"“(])");
    private static final Pattern LINE_NUMBERED = Pattern.compile("(?m)^([0-9]{1,2}(?:\\.[0-9]{1,2}){0,3})(\\.|\\))?\\s+(?=\\S)");

    private ClauseSplitter() {}

    private record Candidate(int start, int end, String number, boolean keyword) {}

    private record Heading(int start, String number, String title, int level) {}

    public static List<Clause> split(String text) {
        List<Candidate> candidates = new ArrayList<>();
        Matcher m = KEYWORD.matcher(text);
        while (m.find()) candidates.add(new Candidate(m.start(), m.end(), m.group(1), true));
        m = SECTION_SIGN.matcher(text);
        while (m.find()) candidates.add(new Candidate(m.start(), m.end(), m.group(1), true));
        m = NUMBERED.matcher(text);
        while (m.find()) candidates.add(new Candidate(m.start(), m.end(), m.group(1), false));
        m = LINE_NUMBERED.matcher(text);
        while (m.find()) candidates.add(new Candidate(m.start(), m.end(), m.group(1), false));
        candidates.sort(Comparator.comparingInt(Candidate::start).thenComparing(c -> !c.keyword()));

        List<Heading> headings = new ArrayList<>();
        int[] last = null;
        int lastStart = -1;
        for (Candidate c : candidates) {
            if (c.start() == lastStart) continue;  // the same place found by two patterns
            if (c.keyword()) {
                headings.add(new Heading(c.start(), c.number(), titleAfter(text, c.end()), c.number().split("\\.").length));
                // "Art. 4" is followed by 4.1, 4.2 ...; a roman numeral starts the numbering afresh.
                last = isArabic(c.number()) ? parse(c.number()) : null;
                lastStart = c.start();
            } else {
                int[] n = parse(c.number());
                if (!follows(last, n)) continue;
                last = n;
                headings.add(new Heading(c.start(), c.number(), titleAfter(text, c.end()), n.length));
                lastStart = c.start();
            }
        }
        headings = withCapsHeadings(text, headings);

        List<Clause> clauses = new ArrayList<>();
        int end = text.length();
        int first = headings.isEmpty() ? end : headings.get(0).start();
        if (!text.substring(0, first).isBlank()) clauses.add(new Clause("c0", "", "Preamble", 0, 0, first));
        for (int i = 0; i < headings.size(); i++) {
            Heading h = headings.get(i);
            int stop = i + 1 < headings.size() ? headings.get(i + 1).start() : end;
            clauses.add(new Clause("c" + clauses.size(), h.number(), h.title(), h.level(), h.start(), stop));
        }
        return clauses;
    }

    /**
     * Short capitals lines: a title for the numbered heading right after them (the clause then
     * starts at the title), or a heading of their own.
     */
    private static List<Heading> withCapsHeadings(String text, List<Heading> headings) {
        List<Heading> out = new ArrayList<>();
        int h = 0, pos = 0;
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            int lineStart = pos;
            pos += lines[li].length() + 1;
            String l = lines[li].strip();
            while (h < headings.size() && headings.get(h).start() < lineStart) out.add(headings.get(h++));
            if (!isCapsHeading(l)) continue;
            if (h < headings.size() && headings.get(h).start() == lineStart) continue;  // a numbered heading in capitals
            // The next non-empty line: is it a numbered heading?
            int next = pos;
            int lj = li + 1;
            while (lj < lines.length && lines[lj].isBlank()) next += lines[lj++].length() + 1;
            if (h < headings.size() && headings.get(h).start() == next) {
                Heading n = headings.get(h++);
                out.add(new Heading(lineStart, n.number(), l, n.level()));
            } else {
                out.add(new Heading(lineStart, "", l, 1));
            }
        }
        while (h < headings.size()) out.add(headings.get(h++));
        return out;
    }

    static int[] parse(String number) {
        String[] parts = number.split("\\.");
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) n[i] = Integer.parseInt(parts[i]);
        return n;
    }

    private static boolean isArabic(String number) {
        return Character.isDigit(number.charAt(0));
    }

    /**
     * Does clause number {@code n} plausibly come after {@code last}? The next number at the same
     * depth, the first one a level down, or the next one higher up (a few skipped numbers allowed,
     * for a clause the extraction lost).
     */
    static boolean follows(int[] last, int[] n) {
        if (last == null) return n[0] >= 1 && n[0] <= 3 && allSmall(n, 3, 1);
        for (int d = 0; d < n.length; d++) {
            if (d >= last.length) {
                // Deeper: x.y -> x.y.1
                return d == n.length - 1 && n[d] >= 1 && n[d] <= 2 && prefixEquals(last, n, d);
            }
            if (n[d] != last[d]) {
                // Differs here: the next number at this depth, then at most a first child below it.
                return prefixEquals(last, n, d) && n[d] > last[d] && n[d] - last[d] <= 3 && allSmall(n, 2, d + 1);
            }
        }
        return false;  // the same number again
    }

    private static boolean prefixEquals(int[] a, int[] b, int len) {
        for (int i = 0; i < len; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static boolean allSmall(int[] n, int max, int from) {
        for (int i = from; i < n.length; i++) if (n[i] > max) return false;
        return true;
    }

    /**
     * The title right after a heading's number: up to the line end or the first full stop, when that
     * is short ("Payment. The tenant ..." gives "Payment"; a long first sentence gives no title).
     */
    static String titleAfter(String text, int from) {
        int lineEnd = text.indexOf('\n', from);
        if (lineEnd < 0) lineEnd = text.length();
        String rest = text.substring(from, Math.min(lineEnd, from + 200)).strip();
        rest = rest.replaceFirst("^[\\-–—:.)\\s]+", "");
        int dot = rest.indexOf(". ");
        // A whole short line is a title, unless it is a sentence ("The rent is due monthly.").
        String head = dot > 0 ? rest.substring(0, dot) : (lineEnd - from <= 80 && !rest.endsWith(".") ? rest : "");
        if (head.isEmpty() || head.length() > 70 || head.endsWith(",")) return "";
        int words = head.split("\\s+").length;
        int letters = 0, upper = 0;
        for (char c : head.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        if (letters == 0) return "";
        boolean caps = upper * 10 >= letters * 8;
        return caps || words <= 7 ? head : "";
    }

    /** A short line in capitals, such as "TERMINATION" or "DURATA CONTRACTULUI". */
    static boolean isCapsHeading(String l) {
        if (l.length() < 4 || l.length() > 80 || l.endsWith(",") || l.endsWith(";")) return false;
        int letters = 0, upper = 0;
        for (char c : l.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) upper++;
            }
        }
        return letters >= 4 && upper * 10 >= letters * 9 && !l.toUpperCase(Locale.ROOT).startsWith("WHEREAS");
    }
}
