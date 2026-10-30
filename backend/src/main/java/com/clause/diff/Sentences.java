package com.clause.diff;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sentences, and a key for each that ignores what reformatting changes: case, spacing, punctuation,
 * line breaks, and a clause number at the start.
 */
final class Sentences {

    private static final Pattern BREAK = Pattern.compile("(?<=[.;:!?])\\s+|\\n+");
    private static final Pattern LEADING_NUMBER = Pattern.compile(
            "^\\s*(?:(?:article|articolul|art\\.?|section|sec[țt]iunea|§)\\s*)?(?:[0-9]{1,3}(?:\\.[0-9]{1,3})*|[ivxlc]{1,7})[.)]?\\s+",
            Pattern.CASE_INSENSITIVE);

    private Sentences() {}

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String s : BREAK.split(text)) {
            String t = s.strip();
            if (key(t).length() >= 3) out.add(t);
        }
        return out;
    }

    static String key(String sentence) {
        String s = LEADING_NUMBER.matcher(sentence).replaceFirst("");
        s = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return s.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
