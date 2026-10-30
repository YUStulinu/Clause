package com.clause.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.clause.document.ClauseSplitter;
import com.clause.document.Contract;
import com.clause.document.DocumentReader;

/**
 * The contracts read in this session, in memory only: a contract is private, and nothing of it is
 * written to disk. The oldest go once there are more than {@link #CAPACITY}. A contract's id is a
 * hash of its text, so the same file uploaded twice is one contract.
 */
@Component
public class ContractStore {

    static final int CAPACITY = 50;

    private final Map<String, Contract> contracts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Contract> eldest) {
            return size() > CAPACITY;
        }
    };
    private final Map<String, Map<String, Object>> analyses = new LinkedHashMap<>();

    public synchronized Contract add(String name, String text) {
        String id = id(text);
        Contract c = new Contract(id, name, DocumentReader.language(text), text, ClauseSplitter.split(text));
        contracts.put(id, c);
        return c;
    }

    public synchronized Contract get(String id) {
        return contracts.get(id);
    }

    public synchronized Map<String, Object> analysis(String id, String answerLanguage) {
        return analyses.get(id + "/" + answerLanguage);
    }

    public synchronized void putAnalysis(String id, String answerLanguage, Map<String, Object> a) {
        analyses.put(id + "/" + answerLanguage, a);
        analyses.keySet().removeIf(k -> !contracts.containsKey(k.substring(0, k.indexOf('/'))));
    }

    static String id(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
