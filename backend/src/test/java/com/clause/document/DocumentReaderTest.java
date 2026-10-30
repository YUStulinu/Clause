package com.clause.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

class DocumentReaderTest {

    /** A three-page PDF with a header and page numbers on every page. */
    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String[][] pages = {
                    {"ACME LEASE - CONFIDENTIAL", "1. Rent", "The Tenant pays 500 dollars a month.", "Page 1 of 3"},
                    {"ACME LEASE - CONFIDENTIAL", "2. Deposit", "A deposit of one month's rent is due.", "Page 2 of 3"},
                    {"ACME LEASE - CONFIDENTIAL", "3. Term", "The lease runs for twelve months.", "Page 3 of 3"},
            };
            for (String[] lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 740;
                    for (String l : lines) {
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(72, y);
                        cs.showText(l);
                        cs.endText();
                        y -= 40;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void pdfWithoutPageFurniture() throws Exception {
        String text = DocumentReader.read("lease.pdf", pdf());
        assertThat(text).contains("1. Rent\nThe Tenant pays 500 dollars a month.");
        assertThat(text).doesNotContain("Page 1 of 3").doesNotContain("CONFIDENTIAL");
        assertThat(ClauseSplitter.split(text)).extracting(Clause::number).containsExactly("1", "2", "3");
    }

    @Test
    void docxParagraphsAndTables() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("1. Price");
            doc.createParagraph().createRun().setText("The price is set out below.");
            XWPFTable t = doc.createTable(1, 2);
            t.getRow(0).getCell(0).setText("Monthly fee");
            t.getRow(0).getCell(1).setText("20 EUR");
            doc.write(out);
            bytes = out.toByteArray();
        }
        String text = DocumentReader.read("offer.docx", bytes);
        assertThat(text).contains("1. Price\nThe price is set out below.\nMonthly fee | 20 EUR");
    }

    @Test
    void tidyJoinsHyphenatedWordsAndFixesRomanianLetters() {
        String raw = "Chiriaşul plăteşte chiria  lunar, iar pro-\nprietarul o încasează.\r\n\r\n\r\nArt. 2";
        String text = DocumentReader.tidy(raw);
        assertThat(text).isEqualTo("Chiriașul plătește chiria lunar, iar proprietarul o încasează.\n\nArt. 2\n");
    }

    @Test
    void languageAndRejections() throws Exception {
        assertThat(DocumentReader.language("Chiriașul plătește chiria și întreținerea pentru apartament.")).isEqualTo("ro");
        assertThat(DocumentReader.language("The Tenant shall pay the rent and the charges of the building.")).isEqualTo("en");
        assertThatThrownBy(() -> DocumentReader.read("photo.jpg", new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThat(DocumentReader.read("a.txt", "﻿hello".getBytes(StandardCharsets.UTF_8))).isEqualTo("hello\n");
    }

    @Test
    void repeatedFooterGoesOnlyWhenOnMostPages() {
        List<List<String>> pages = new ArrayList<>();
        pages.add(new ArrayList<>(List.of("Text one", "Footer")));
        pages.add(new ArrayList<>(List.of("Text two", "Footer")));
        pages.add(new ArrayList<>(List.of("Text three", "Something else")));
        String joined = DocumentReader.joinPages(pages);
        assertThat(joined).doesNotContain("Footer").contains("Something else");
    }
}
