package com.clause.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ClauseSplitterTest {

    private static List<String> numbers(List<Clause> clauses) {
        return clauses.stream().map(Clause::number).toList();
    }

    @Test
    void romanianArticlesAndSubclauses() {
        String text = """
                CONTRACT DE ÎNCHIRIERE
                Încheiat între Proprietar și Chiriaș.

                Art. 1 Obiectul contractului
                1.1. Proprietarul dă în folosință apartamentul.
                1.2. Apartamentul se predă mobilat.

                Art. 2 Chiria
                2.1. Chiria lunară este de 2.000 lei.
                2.000 lei reprezintă garanția, nu o clauză.
                """;
        List<Clause> c = ClauseSplitter.split(text);
        assertThat(numbers(c)).containsExactly("", "1", "1.1", "1.2", "2", "2.1");
        assertThat(c.get(1).title()).isEqualTo("Obiectul contractului");
        assertThat(c.get(0).title()).isEqualTo("CONTRACT DE ÎNCHIRIERE");
        // Every character belongs to exactly one clause, in order.
        for (int i = 1; i < c.size(); i++) assertThat(c.get(i).start()).isEqualTo(c.get(i - 1).end());
        assertThat(c.get(c.size() - 1).end()).isEqualTo(text.length());
    }

    @Test
    void numberedClausesInsideOneLongLine() {
        // How PDF extraction often delivers a contract: one line, headings inside it.
        String text = "LICENSE AGREEMENT This Agreement is made by A and B. The parties agree as follows: "
                + "BASIC PROVISIONS 1. Definitions. Terms have the meanings below. 2. Territory. The Territory is worldwide. "
                + "3. Term. This Agreement lasts 2 years, and fees of 1. 500 dollars are not a clause. 4. Fees. Fees are due monthly.";
        List<Clause> c = ClauseSplitter.split(text);
        assertThat(numbers(c)).containsExactly("", "1", "2", "3", "4");
        assertThat(c.get(2).title()).isEqualTo("Territory");
        assertThat(c.get(2).text(text)).startsWith("2. Territory.");
    }

    @Test
    void amountsYearsAndListItemsAreNotClauses() {
        String text = """
                1. Payment
                The price is payable as follows:
                2024 was the first year.
                5) a list item inside a clause
                2. Term
                The term is 12 months.
                """;
        assertThat(numbers(ClauseSplitter.split(text))).containsExactly("1", "2");
    }

    @Test
    void capitalsLineTitlesTheNextClause() {
        String text = """
                TERMINATION
                1. Either party may terminate on 30 days notice.
                CONFIDENTIALITY
                The parties keep this agreement confidential.
                """;
        List<Clause> c = ClauseSplitter.split(text);
        assertThat(c).hasSize(2);
        assertThat(c.get(0).number()).isEqualTo("1");
        assertThat(c.get(0).title()).isEqualTo("TERMINATION");
        assertThat(c.get(0).start()).isZero();
        assertThat(c.get(1).title()).isEqualTo("CONFIDENTIALITY");
    }

    @Test
    void followsAcceptsTheNextNumberOnly() {
        assertThat(ClauseSplitter.follows(new int[] {2}, new int[] {3})).isTrue();
        assertThat(ClauseSplitter.follows(new int[] {2}, new int[] {2, 1})).isTrue();
        assertThat(ClauseSplitter.follows(new int[] {2, 3}, new int[] {3})).isTrue();
        assertThat(ClauseSplitter.follows(new int[] {2, 3}, new int[] {2, 4})).isTrue();
        assertThat(ClauseSplitter.follows(new int[] {2}, new int[] {12})).isFalse();
        assertThat(ClauseSplitter.follows(new int[] {2}, new int[] {2})).isFalse();
        assertThat(ClauseSplitter.follows(new int[] {2}, new int[] {1})).isFalse();
        assertThat(ClauseSplitter.follows(null, new int[] {1})).isTrue();
        assertThat(ClauseSplitter.follows(null, new int[] {7})).isFalse();
    }
}
