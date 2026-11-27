package com.clause;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The command line on the sample lease: read gives the clauses, compare finds the changed figures. */
class CliTest {

    private static String run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Cli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String sample(String name) {
        return Path.of("..", "samples", name).toString();
    }

    @Test
    void readGivesTextLanguageAndClauses() {
        String json = run("read", sample("chirie-v1.txt"));
        assertTrue(json.contains("\"language\" : \"ro\""), json.substring(0, 200));
        assertTrue(json.contains("\"clauses\""));
        assertTrue(json.contains("\"start\""));
    }

    @Test
    void compareReportsWhatChanged() {
        String json = run("compare", sample("chirie-v1.txt"), sample("chirie-v2.txt"));
        assertTrue(json.contains("\"changes\""));
        assertTrue(json.contains("\"figures_changed\""));
        assertTrue(json.contains("\"kind\" : \"modified\""));
    }

    @Test
    void badUsageIsRefused() {
        var err = new ByteArrayOutputStream();
        assertEquals(2, Cli.run(new String[] {"read"}, System.out, new PrintStream(err)));
        assertEquals(1, Cli.run(new String[] {"read", "no-such-file.pdf"}, System.out, new PrintStream(err)));
    }
}
