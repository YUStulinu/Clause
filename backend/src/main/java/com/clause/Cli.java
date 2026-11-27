package com.clause;

import com.clause.diff.ClauseAligner;
import com.clause.diff.Comparison;
import com.clause.document.Clause;
import com.clause.document.ClauseSplitter;
import com.clause.document.Contract;
import com.clause.document.DocumentReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command line, for AI agents and scripts: the same reading and comparing the app does, as JSON on
 * standard output, without starting the server.
 *
 * <pre>
 *   java -jar clause.jar read contract.pdf          the text, its language and its clauses (with positions)
 *   java -jar clause.jar compare old.docx new.docx  the clauses of two versions paired, with what changed
 * </pre>
 */
public final class Cli {

    private Cli() {}

    public static boolean handles(String[] args) {
        return args.length > 0 && (args[0].equals("read") || args[0].equals("compare"));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Object result;
            if (args[0].equals("read") && args.length == 2) {
                result = describe(load(Path.of(args[1])));
            } else if (args[0].equals("compare") && args.length == 3) {
                result = compare(load(Path.of(args[1])), load(Path.of(args[2])));
            } else {
                err.println("usage: read <contract>  |  compare <old version> <new version>   (PDF, DOCX or text)");
                return 2;
            }
            out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    static Contract load(Path file) throws IOException {
        String name = file.getFileName().toString();
        String text = DocumentReader.read(name, Files.readAllBytes(file));
        if (text.isBlank()) {
            throw new IOException(name + ": no text found (a scanned PDF needs OCR first)");
        }
        return new Contract(name, name, DocumentReader.language(text), text, ClauseSplitter.split(text));
    }

    static Map<String, Object> describe(Contract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("language", c.language());
        m.put("text", c.text());
        List<Map<String, Object>> clauses = new ArrayList<>();
        for (Clause cl : c.clauses()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", cl.id());
            x.put("number", cl.number());
            x.put("title", cl.title());
            x.put("start", cl.start());
            x.put("end", cl.end());
            clauses.add(x);
        }
        m.put("clauses", clauses);
        return m;
    }

    static Map<String, Object> compare(Contract a, Contract b) {
        List<Map<String, Object>> changes = new ArrayList<>();
        int unchanged = 0;
        for (Comparison.Entry e : Comparison.compare(a, b)) {
            if (e.kind() == ClauseAligner.Kind.SAME || e.kind() == ClauseAligner.Kind.COSMETIC) {
                unchanged++;
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", e.kind().name().toLowerCase());
            x.put("clause_before", e.a() == null ? null : e.a().number() + " " + e.a().title());
            x.put("clause_after", e.b() == null ? null : e.b().number() + " " + e.b().title());
            x.put("before", e.a() == null ? "" : ClauseAligner.body(a, e.a()));
            x.put("after", e.b() == null ? "" : ClauseAligner.body(b, e.b()));
            x.put("only_before", e.onlyBefore());
            x.put("only_after", e.onlyAfter());
            x.put("figures_changed", e.facts().stream()
                    .map(f -> Map.of("before", f.before() == null ? "" : f.before(), "after", f.after() == null ? "" : f.after()))
                    .toList());
            x.put("negation_changed", e.negationChanged());
            x.put("obligation_changed", e.modalityChanged());
            changes.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("before", a.name());
        m.put("after", b.name());
        m.put("unchanged_clauses", unchanged);
        m.put("changes", changes);
        return m;
    }
}
