package com.clause.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import com.clause.analysis.AnalysisClient;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ContractControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisClient analysis;

    static final String V1 = """
            1. Rent
            The Tenant pays the rent of 500 EUR on the first day of every month by bank transfer.
            2. Penalties
            For late payment the Tenant pays 0.1% of the rent for each day of delay, at most the rent itself.
            """;
    static final String V2 = """
            1. Rent
            The Tenant pays the rent of 500 EUR on the first day of every month by bank transfer.
            2. Penalties
            For late payment the Tenant pays 0.5% of the rent for each day of delay, at most the rent itself.
            """;

    private String upload(String name, String text) throws Exception {
        String body = mvc.perform(multipart("/api/contracts")
                        .file(new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clauses", hasSize(2)))
                .andExpect(jsonPath("$.disclaimer", containsString("not legal advice")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void uploadAndRead() throws Exception {
        String id = upload("lease.txt", V1);
        mvc.perform(get("/api/contracts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.clauses[1].title").value("Penalties"));
        mvc.perform(get("/api/contracts/nope")).andExpect(status().isNotFound());
    }

    @Test
    void analysisIsAskedOnceAndCarriesTheNotice() throws Exception {
        String id = upload("lease.txt", V1);
        when(analysis.analyze(any(), anyString())).thenReturn(Map.of("findings", List.of(), "counts", Map.of()));
        mvc.perform(post("/api/contracts/" + id + "/analysis")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disclaimer", containsString("not legal advice")));
        mvc.perform(post("/api/contracts/" + id + "/analysis")).andExpect(status().isOk());
        verify(analysis, times(1)).analyze(any(), anyString());
    }

    @Test
    void compareMarksTheChangedPenalty() throws Exception {
        String a = upload("v1.txt", V1), b = upload("v2.txt", V2);
        when(analysis.judge(anyString(), anyList())).thenReturn(Map.of("judgements",
                List.of(Map.of("id", "p1", "meaning", "changed", "explanation", "The daily penalty rose from 0.1% to 0.5%."))));
        mvc.perform(get("/api/compare").param("a", a).param("b", b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.modified").value(1))
                .andExpect(jsonPath("$.counts.same").value(1))
                .andExpect(jsonPath("$.entries[1].facts[0].before").value("0.1%"))
                .andExpect(jsonPath("$.entries[1].facts[0].after").value("0.5%"))
                .andExpect(jsonPath("$.entries[1].meaning.meaning").value("changed"));
    }

    @Test
    void compareStillWorksWithoutTheService() throws Exception {
        String a = upload("v1.txt", V1), b = upload("v2.txt", V2);
        when(analysis.judge(anyString(), anyList())).thenThrow(new ResourceAccessException("down"));
        mvc.perform(get("/api/compare").param("a", a).param("b", b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.note", containsString("not running")))
                .andExpect(jsonPath("$.counts.modified").value(1));
        mvc.perform(get("/api/compare").param("a", a).param("b", b).param("meaning", "false")).andExpect(status().isOk());
        verify(analysis, times(1)).judge(anyString(), anyList());
    }

    @Test
    void badFilesAreRefusedClearly() throws Exception {
        mvc.perform(multipart("/api/contracts").file(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[] {1, 2})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("PDF, DOCX")));
        mvc.perform(multipart("/api/contracts").file(new MockMultipartFile("file", "x.txt", "text/plain", "too short".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("OCR")));
        verify(analysis, never()).analyze(any(), anyString());
    }
}
