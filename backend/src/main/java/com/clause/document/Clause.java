package com.clause.document;

/**
 * One clause: its number as written ("3.2", "IV", "" for the preamble), its heading if it has one,
 * its depth in the numbering (1 for "3", 2 for "3.2") and where it sits in the contract's text.
 */
public record Clause(String id, String number, String title, int level, int start, int end) {

    public String text(String contractText) {
        return contractText.substring(start, end);
    }
}
