package com.clause.analysis;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.clause.document.Contract;

/**
 * The analysis service (service/, Python): the language model that extracts what a contract asks of
 * you, the checker that holds every statement to its quote, and the judge of whether a changed clause
 * changed its meaning. Reading a contract with a local model takes a while, hence the long timeout.
 */
@Component
public class AnalysisClient {

    private final RestClient http;

    public AnalysisClient(@Value("${clause.analysis-url:http://127.0.0.1:8766}") String url) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofMinutes(30));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public boolean alive() {
        try {
            http.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    public record ClauseRef(String id, String number, String title, int start, int end) {}

    /** The findings for a contract, each with its quote's span in the text and how it was checked. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(Contract c, String answerLanguage) {
        List<ClauseRef> clauses = c.clauses().stream()
                .map(cl -> new ClauseRef(cl.id(), cl.number(), cl.title(), cl.start(), cl.end())).toList();
        Map<String, Object> body = Map.of("text", c.text(), "language", c.language(), "answer_language", answerLanguage,
                "clauses", clauses);
        return http.post().uri("/analyze").body(body).retrieve().body(Map.class);
    }

    /** Whether each changed clause changed its meaning, with the reason. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> judge(String language, List<Map<String, Object>> pairs) {
        Map<String, Object> body = Map.of("language", language, "pairs", pairs);
        return http.post().uri("/compare").body(body).retrieve().body(Map.class);
    }
}
