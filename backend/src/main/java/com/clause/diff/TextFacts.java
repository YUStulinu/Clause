package com.clause.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hard facts in a piece of contract text: amounts, percentages, durations, dates and bare
 * numbers, and the words that negate. Between two versions of a clause, a changed fact or a
 * changed negation always changes the meaning, whatever the wording around it.
 */
public final class TextFacts {

    /** One fact as written, and its value normalised: "1.500 lei" and "1500 RON" are both "1500 ron". */
    public record Fact(String text, String value) {}

    public record Change(String before, String after) {}

    private static final String NUMBER = "\\d{1,3}(?:[.,\\s]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?";
    private static final Pattern FACT = Pattern.compile(
            "(" + NUMBER + ")\\s*(%|percent|la sut[ăa]|lei|ron|eur|euro|€|usd|\\$|days?|zile|zi|business days|months?|luni|lun[ăa]|years?|ani|an|weeks?|s[ăa]pt[ăa]m[âa]ni|hours?|ore)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DATE = Pattern.compile("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "\\b(not|no|never|neither|nor|without|cannot|nu|nici|f[ăa]r[ăa]|niciodat[ăa]|interzis[ăa]?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("percent", "%"), Map.entry("la sută", "%"), Map.entry("la suta", "%"),
            Map.entry("lei", "ron"), Map.entry("euro", "eur"), Map.entry("€", "eur"), Map.entry("$", "usd"),
            Map.entry("day", "days"), Map.entry("zile", "days"), Map.entry("zi", "days"),
            Map.entry("month", "months"), Map.entry("luni", "months"), Map.entry("lună", "months"), Map.entry("luna", "months"),
            Map.entry("year", "years"), Map.entry("ani", "years"), Map.entry("an", "years"),
            Map.entry("week", "weeks"), Map.entry("săptămâni", "weeks"), Map.entry("saptamani", "weeks"),
            Map.entry("hour", "hours"), Map.entry("ore", "hours"));

    private TextFacts() {}

    public static List<Fact> facts(String text) {
        List<Fact> out = new ArrayList<>();
        Matcher d = DATE.matcher(text);
        List<int[]> dates = new ArrayList<>();
        while (d.find()) {
            out.add(new Fact(d.group(), "date " + d.group().replaceAll("[./-]", "-")));
            dates.add(new int[] {d.start(), d.end()});
        }
        Matcher m = FACT.matcher(text);
        while (m.find()) {
            int at = m.start();
            if (dates.stream().anyMatch(r -> at >= r[0] && at < r[1])) continue;
            if (isClauseNumber(text, m)) continue;
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            unit = UNITS.getOrDefault(unit, unit);
            out.add(new Fact(m.group().strip(), (number(m.group(1)) + " " + unit).strip()));
        }
        return out;
    }

    /** "1.500" and "1,500" are fifteen hundred; "2,5" and "2.5" are two and a half. */
    static String number(String s) {
        String t = s.replace(" ", "");
        if (t.matches("\\d{1,3}([.,]\\d{3})+")) t = t.replaceAll("[.,]", "");
        else if (t.matches("\\d{1,3}([.,]\\d{3})+[.,]\\d+")) {
            // "1.500,25" or "1,500.25": the last separator is the decimal one.
            int dec = Math.max(t.lastIndexOf('.'), t.lastIndexOf(','));
            t = t.substring(0, dec).replaceAll("[.,]", "") + "." + t.substring(dec + 1);
        } else t = t.replace(',', '.');
        if (t.contains(".")) t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
        return t;
    }

    /** A number without a unit at the start of the text or of a line: a clause's own number ("4.2."). */
    private static boolean isClauseNumber(String text, Matcher m) {
        return m.group(2) == null && (m.start() == 0 || text.charAt(m.start() - 1) == '\n');
    }

    private static final Pattern OBLIGATION = Pattern.compile(
            "\\b(shall|must|will|agrees? to|(?:is|are) (?:required|obliged|obligated) to|trebuie|se oblig[ăa]|(?:este|sunt) obligat[ăai]*|va|vor)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PERMISSION = Pattern.compile(
            "\\b(may|can|(?:is|are) (?:entitled|permitted|allowed) to|ha(?:s|ve) the right to|poate|pot|(?:are|au) dreptul|(?:este|sunt) îndreptățit[ăai]*)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * How many obligations ("shall", "must", "trebuie") and permissions ("may", "poate") a text
     * states. "shall" and "will" are both obligations, so rewording one into the other changes nothing;
     * "shall" into "may" does.
     */
    public static String modality(String text) {
        return "obligations " + count(OBLIGATION, text) + ", permissions " + count(PERMISSION, text);
    }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    public static int negations(String text) {
        Matcher m = NEGATION.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * The facts that differ between two texts, paired in order of appearance where both sides have
     * one of the same kind (unit), unpaired otherwise.
     */
    public static List<Change> changes(String a, String b) {
        Map<String, Integer> count = new HashMap<>();
        List<Fact> fa = facts(a), fb = facts(b);
        for (Fact f : fa) count.merge(f.value(), 1, Integer::sum);
        for (Fact f : fb) count.merge(f.value(), -1, Integer::sum);
        List<Fact> gone = new ArrayList<>(), added = new ArrayList<>();
        Map<String, Integer> left = new HashMap<>(count);
        for (Fact f : fa) if (left.getOrDefault(f.value(), 0) > 0) { gone.add(f); left.merge(f.value(), -1, Integer::sum); }
        left = new HashMap<>(count);
        for (Fact f : fb) if (left.getOrDefault(f.value(), 0) < 0) { added.add(f); left.merge(f.value(), 1, Integer::sum); }
        List<Change> out = new ArrayList<>();
        for (Fact g : gone) {
            Fact match = null;
            for (Fact n : added) if (unit(n).equals(unit(g))) { match = n; break; }
            if (match != null) {
                added.remove(match);
                out.add(new Change(g.text(), match.text()));
            } else {
                out.add(new Change(g.text(), null));
            }
        }
        for (Fact n : added) out.add(new Change(null, n.text()));
        return out;
    }

    private static String unit(Fact f) {
        int space = f.value().indexOf(' ');
        return space < 0 ? "" : f.value().substring(space + 1);
    }
}
