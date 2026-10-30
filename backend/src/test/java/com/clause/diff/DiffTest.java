package com.clause.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.clause.document.ClauseSplitter;
import com.clause.document.Contract;

class DiffTest {

    private static Contract contract(String text) {
        return new Contract("x", "x", "en", text, ClauseSplitter.split(text));
    }

    @Test
    void factsAreNormalised() {
        assertThat(TextFacts.number("1.500")).isEqualTo("1500");
        assertThat(TextFacts.number("1,500")).isEqualTo("1500");
        assertThat(TextFacts.number("2,5")).isEqualTo("2.5");
        assertThat(TextFacts.number("1.500,25")).isEqualTo("1500.25");
        assertThat(TextFacts.number("10.50")).isEqualTo("10.5");
        assertThat(TextFacts.facts("0,1% pe zi, 30 de zile, 2.000 lei, din 01.03.2026"))
                .extracting(TextFacts.Fact::value)
                .containsExactlyInAnyOrder("0.1 %", "30", "2000 ron", "date 01-03-2026");
    }

    @Test
    void changedFactsArePaired() {
        List<TextFacts.Change> c = TextFacts.changes(
                "Penalties of 0.1% per day, with 30 days notice and a fee of 100 EUR.",
                "Penalties of 0.5% per day, with 30 days notice.");
        assertThat(c).containsExactlyInAnyOrder(new TextFacts.Change("0.1%", "0.5%"), new TextFacts.Change("100 EUR", null));
        // Written differently, the same amount is no change.
        assertThat(TextFacts.changes("a deposit of 1.500 lei", "a deposit of 1500 RON")).isEmpty();
    }

    @Test
    void negations() {
        assertThat(TextFacts.negations("Chiriașul nu poate subînchiria fără acord.")).isEqualTo(2);
        assertThat(TextFacts.negations("The Tenant may sublet.")).isZero();
    }

    @Test
    void wordDiffPutsBothTextsBackTogether() {
        String a = "The Tenant shall pay  the rent monthly.";
        String b = "The Tenant shall pay the rent and a fee every month.";
        List<WordDiff.Run> runs = WordDiff.diff(a, b);
        String before = runs.stream().filter(r -> r.kind() != WordDiff.Kind.INSERTED).map(WordDiff.Run::text).collect(Collectors.joining());
        String after = runs.stream().filter(r -> r.kind() != WordDiff.Kind.DELETED).map(WordDiff.Run::text).collect(Collectors.joining());
        assertThat(after).isEqualTo(b);
        assertThat(before.replaceAll("\\s+", " ")).isEqualTo(a.replaceAll("\\s+", " "));
        assertThat(runs).anySatisfy(r -> {
            assertThat(r.kind()).isEqualTo(WordDiff.Kind.INSERTED);
            assertThat(r.text()).contains("fee");
        });
        assertThat(WordDiff.cosmetic("The  Rent, monthly.", "the rent monthly")).isTrue();
        assertThat(WordDiff.cosmetic("rent monthly", "rent weekly")).isFalse();
    }

    @Test
    void alignmentFindsMovedRenumberedAddedAndRemovedClauses() {
        Contract a = contract("""
                1. Rent
                The Tenant pays the rent of 500 EUR on the first day of every month by bank transfer.
                2. Repairs
                The Landlord carries out structural repairs at his own cost within a reasonable time.
                3. Pets
                No pets may be kept in the apartment without the written consent of the Landlord.
                4. Notice
                Either party may end this lease with thirty days written notice to the other party.
                """);
        Contract b = contract("""
                1. Notice
                Either party may end this lease with sixty days written notice to the other party.
                2. Rent
                The Tenant pays the rent of 500 EUR on the first day of every month by bank transfer.
                3. Repairs
                The Landlord carries out structural repairs at his own cost within a reasonable time.
                4. Insurance
                The Tenant insures the contents of the apartment against fire and water damage.
                """);
        List<ClauseAligner.Pair> pairs = ClauseAligner.align(a, b);
        assertThat(pairs).filteredOn(p -> p.kind() == ClauseAligner.Kind.SAME).hasSize(2);  // renumbered, same words
        assertThat(pairs).filteredOn(p -> p.kind() == ClauseAligner.Kind.ADDED).singleElement()
                .satisfies(p -> assertThat(p.b().title()).isEqualTo("Insurance"));
        assertThat(pairs).filteredOn(p -> p.kind() == ClauseAligner.Kind.REMOVED).singleElement()
                .satisfies(p -> assertThat(p.a().title()).isEqualTo("Pets"));
        ClauseAligner.Pair notice = pairs.stream().filter(p -> p.kind() == ClauseAligner.Kind.MODIFIED).findFirst().orElseThrow();
        assertThat(notice.a().title()).isEqualTo("Notice");
        assertThat(notice.moved()).isTrue();

        List<Comparison.Entry> entries = Comparison.compare(a, b);
        Comparison.Entry changed = entries.stream().filter(e -> e.kind() == ClauseAligner.Kind.MODIFIED).findFirst().orElseThrow();
        assertThat(changed.facts()).isEmpty();  // thirty -> sixty is in words: for the meaning check, not the facts
        assertThat(Comparison.questions(a, b, entries)).hasSize(1);
    }

    @Test
    void reformattingThatShiftsClauseBoundariesIsNotAChange() {
        Contract a = contract("""
                1. Rent
                The Tenant pays the rent of 500 EUR on the first day of every month. Late payment costs 0.1% a day.
                2. Notice
                Either party may end this lease with thirty days written notice to the other party.
                """);
        // The same sentences, with the second clause's heading run into the first clause's line.
        Contract b = contract("""
                1. Rent
                The Tenant pays the rent of 500 EUR on the first day of every month.
                Late payment costs 0.1% a day. 2. Notice Either party may end this lease with thirty days written notice to the other party.
                """);
        List<Comparison.Entry> entries = Comparison.compare(a, b);
        assertThat(entries).noneMatch(e -> e.kind() == ClauseAligner.Kind.MODIFIED || e.kind() == ClauseAligner.Kind.ADDED
                || e.kind() == ClauseAligner.Kind.REMOVED);
        assertThat(Comparison.questions(a, b, entries)).isEmpty();
    }

    @Test
    void obligationTurnedIntoPermission() {
        assertThat(TextFacts.modality("The Tenant shall repaint.")).isEqualTo(TextFacts.modality("The Tenant will repaint."));
        assertThat(TextFacts.modality("The Tenant shall repaint.")).isNotEqualTo(TextFacts.modality("The Tenant may repaint."));
        assertThat(TextFacts.modality("Chiriașul trebuie să zugrăvească.")).isNotEqualTo(TextFacts.modality("Chiriașul poate zugrăvi."));
        Contract a = contract("1. Repairs\nThe Landlord shall carry out all structural repairs at his own cost within a month.\n");
        Contract b = contract("1. Repairs\nThe Landlord may carry out all structural repairs at his own cost within a month.\n");
        Comparison.Entry e = Comparison.compare(a, b).get(0);
        assertThat(e.kind()).isEqualTo(ClauseAligner.Kind.MODIFIED);
        assertThat(e.modalityChanged()).isTrue();
        assertThat(e.onlyBefore()).hasSize(1);
    }

    @Test
    void movedIsTheComplementOfTheLongestRunInOrder() {
        assertThat(ClauseAligner.moved(new int[] {1, 2, 0})).containsExactly(false, false, true);
        assertThat(ClauseAligner.moved(new int[] {0, -1, 1})).containsExactly(false, false, false);
    }
}
