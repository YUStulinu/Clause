package com.clause.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * Turns an uploaded file (PDF, DOCX or plain text) into the contract's text, one line per line of
 * the document. Page furniture that would split clauses (page numbers, a header or footer repeated
 * on every page) is removed, words hyphenated across lines are joined, and Word's automatic
 * numbering, which is not part of a paragraph's text, is written out ("3.2 ...").
 */
public final class DocumentReader {

    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^\\s*(?:(?:page|pagina|pag\\.)\\s*)?-?\\s*\\d{1,3}\\s*-?(?:\\s*(?:of|/|din)\\s*\\d{1,3})?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HYPHEN_BREAK = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");

    private DocumentReader() {}

    public static String read(String filename, byte[] bytes) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        if (name.endsWith(".pdf")) text = pdf(bytes);
        else if (name.endsWith(".docx")) text = docx(bytes);
        else if (name.endsWith(".txt") || name.endsWith(".md")) text = plain(bytes);
        else throw new IllegalArgumentException("Clause reads PDF, DOCX and plain text files, not " + filename);
        return tidy(text);
    }

    static String plain(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static String pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<List<String>> pages = new ArrayList<>();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(new ArrayList<>(List.of(stripper.getText(doc).split("\\R", -1))));
            }
            return joinPages(pages);
        }
    }

    /** The pages' lines without page numbers and without a first or last line every page repeats. */
    static String joinPages(List<List<String>> pages) {
        for (List<String> lines : pages) lines.removeIf(l -> PAGE_NUMBER.matcher(l).matches());
        if (pages.size() >= 3) {
            for (boolean top : new boolean[] {true, false}) {
                Map<String, Integer> seen = new HashMap<>();
                for (List<String> lines : pages) {
                    String edge = edge(lines, top);
                    if (edge != null) seen.merge(edge, 1, Integer::sum);
                }
                for (List<String> lines : pages) {
                    String edge = edge(lines, top);
                    if (edge != null && seen.get(edge) * 2 > pages.size()) lines.remove(top ? firstIndex(lines) : lastIndex(lines));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (List<String> lines : pages) {
            for (String l : lines) out.append(l).append('\n');
        }
        return out.toString();
    }

    private static String edge(List<String> lines, boolean top) {
        int i = top ? firstIndex(lines) : lastIndex(lines);
        return i < 0 ? null : lines.get(i).strip();
    }

    private static int firstIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) if (!lines.get(i).isBlank()) return i;
        return -1;
    }

    private static int lastIndex(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) if (!lines.get(i).isBlank()) return i;
        return -1;
    }

    static String docx(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder out = new StringBuilder();
            Map<BigInteger, int[]> counters = new HashMap<>();
            for (IBodyElement e : doc.getBodyElements()) {
                if (e instanceof XWPFParagraph p) {
                    out.append(numberOf(p, counters)).append(p.getText()).append('\n');
                } else if (e instanceof XWPFTable t) {
                    for (XWPFTableRow row : t.getRows()) {
                        List<String> cells = row.getTableCells().stream().map(c -> c.getText().strip()).toList();
                        out.append(String.join(" | ", cells)).append('\n');
                    }
                }
            }
            return out.toString();
        }
    }

    /** Word's automatic number for a paragraph, from its level's pattern ("%1.%2.") and running counters. */
    private static String numberOf(XWPFParagraph p, Map<BigInteger, int[]> counters) {
        BigInteger numId = p.getNumID();
        String pattern = p.getNumLevelText();
        if (numId == null || pattern == null || p.getNumIlvl() == null) return "";
        String format = p.getNumFmt();
        if (format != null && (format.equals("bullet") || format.equals("none"))) return "";
        int level = p.getNumIlvl().intValue();
        int[] c = counters.computeIfAbsent(numId, k -> new int[9]);
        c[level]++;
        for (int i = level + 1; i < c.length; i++) c[i] = 0;
        String label = pattern;
        for (int i = 0; i <= level; i++) label = label.replace("%" + (i + 1), Integer.toString(Math.max(1, c[i])));
        return label + " ";
    }

    /** NFC, no tabs or non-breaking spaces, single spaces inside lines, hyphenated words joined. */
    static String tidy(String s) {
        s = Normalizer.normalize(s, Normalizer.Form.NFC)
                .replace("\r\n", "\n").replace('\r', '\n')
                .replace(' ', ' ').replace('\t', ' ')
                // Romanian: the comma-below letters, not the cedilla ones old fonts use
                .replace('ş', 'ș').replace('Ş', 'Ș').replace('ţ', 'ț').replace('Ţ', 'Ț');
        s = HYPHEN_BREAK.matcher(s).replaceAll("$1$2");
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : s.split("\n", -1)) {
            String l = line.strip().replaceAll(" {2,}", " ");
            if (l.isEmpty()) {
                if (++blank > 1) continue;
            } else {
                blank = 0;
            }
            out.append(l).append('\n');
        }
        return out.toString().strip() + "\n";
    }

    /** "ro" when the text reads as Romanian, "en" otherwise. */
    public static String language(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        int ro = 0, en = 0;
        for (String w : new String[] {" și ", " sau ", " părți", " pentru ", " prezentul ", " contractul ", " este ", " în "}) ro += count(t, w);
        for (String w : new String[] {" the ", " shall ", " and ", " party ", " agreement ", " this ", " of "}) en += count(t, w);
        for (char c : t.toCharArray()) if ("ăâîșț".indexOf(c) >= 0) ro++;
        return ro > en ? "ro" : "en";
    }

    private static int count(String s, String w) {
        int n = 0;
        for (int i = s.indexOf(w); i >= 0; i = s.indexOf(w, i + 1)) n++;
        return n;
    }
}
