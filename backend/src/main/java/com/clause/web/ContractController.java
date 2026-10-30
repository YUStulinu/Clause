package com.clause.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.clause.analysis.AnalysisClient;
import com.clause.diff.ClauseAligner;
import com.clause.diff.Comparison;
import com.clause.document.Contract;
import com.clause.document.DocumentReader;

/**
 * The API the frontend uses. Every answer that explains a contract carries the same notice: Clause
 * reads contracts, it does not give legal advice.
 */
@RestController
@RequestMapping("/api")
public class ContractController {

    public static final String DISCLAIMER =
            "Clause explains what a contract says; it is not legal advice. Every statement is checked against the "
                    + "quoted text, but a checker can miss things: read the quoted clauses, and ask a lawyer before you sign "
                    + "anything important.";

    private final ContractStore store;
    private final AnalysisClient analysis;

    public ContractController(ContractStore store, AnalysisClient analysis) {
        this.store = store;
        this.analysis = analysis;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("backend", "up", "analysis", analysis.alive() ? "up" : "down");
    }

    @PostMapping("/contracts")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty.");
        String text = DocumentReader.read(file.getOriginalFilename(), file.getBytes());
        if (text.strip().length() < 50) {
            throw new IllegalArgumentException("Almost no text could be read from this file. A scanned PDF has to go through OCR first.");
        }
        return view(store.add(file.getOriginalFilename(), text));
    }

    @GetMapping("/contracts/{id}")
    public Map<String, Object> contract(@PathVariable String id) {
        return view(find(id));
    }

    @PostMapping("/contracts/{id}/analysis")
    public Map<String, Object> analyze(@PathVariable String id, @RequestParam(defaultValue = "") String language) {
        Contract c = find(id);
        String lang = language.isBlank() ? c.language() : language;
        Map<String, Object> a = store.analysis(id, lang);
        if (a == null) {
            a = new LinkedHashMap<>(analysis.analyze(c, lang));
            a.put("disclaimer", DISCLAIMER);
            store.putAnalysis(id, lang, a);
        }
        return a;
    }

    @GetMapping("/compare")
    public Map<String, Object> compare(@RequestParam String a, @RequestParam String b,
                                       @RequestParam(defaultValue = "true") boolean meaning) {
        Contract ca = find(a), cb = find(b);
        List<Comparison.Entry> entries = Comparison.compare(ca, cb);
        String note = null;
        List<Map<String, Object>> questions = Comparison.questions(ca, cb, entries);
        if (meaning && !questions.isEmpty()) {
            try {
                entries = Comparison.withMeanings(entries, analysis.judge(cb.language(), questions));
            } catch (RestClientException e) {
                note = "The analysis service is not running: changed clauses are shown, but not whether their meaning changed.";
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ClauseAligner.Kind k : ClauseAligner.Kind.values()) {
            counts.put(k.name().toLowerCase(), entries.stream().filter(e -> e.kind() == k).count());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", Map.of("id", ca.id(), "name", ca.name(), "text", ca.text()));
        out.put("b", Map.of("id", cb.id(), "name", cb.name(), "text", cb.text()));
        out.put("counts", counts);
        out.put("entries", entries);
        out.put("note", note);
        out.put("disclaimer", DISCLAIMER);
        return out;
    }

    private Contract find(String id) {
        Contract c = store.get(id);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No contract " + id + " in this session: upload it again.");
        return c;
    }

    private static Map<String, Object> view(Contract c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id());
        out.put("name", c.name());
        out.put("language", c.language());
        out.put("text", c.text());
        out.put("clauses", c.clauses());
        out.put("disclaimer", DISCLAIMER);
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unreadable(IOException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "The file could not be read: " + e.getMessage()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> serviceDown(RestClientException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "The analysis service is not answering. Start it (service/, see README) and try again."));
    }
}
