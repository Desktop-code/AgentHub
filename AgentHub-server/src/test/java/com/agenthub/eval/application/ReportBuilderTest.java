package com.agenthub.eval.application;

import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReportBuilder 单测。所属域：eval。内存/临时目录；无 sidecar。
 */
class ReportBuilderTest {

    private final ObjectMapper json = new ObjectMapper();
    private EvalProperties properties;
    private ReportBuilder builder;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new EvalProperties();
        properties.getReport().setWorkspaceDir(tempDir.toString());
        properties.getReport().setWriteWorkspace(true);
        properties.getReport().setMarkdown(true);
        builder = new ReportBuilder(json, properties);
    }

    @Test
    void buildsRuleReportWithoutOpenJudgeAndWritesMarkdown() throws Exception {
        EvalRun run = new EvalRun();
        run.setId(9L);
        run.setSuiteId(1L);
        run.setSuiteVersion(1);
        run.setAgentId(7L);
        run.setStatus(EvalRunStatus.SUCCEEDED);
        run.setGatePassed(0);
        run.setTotalCases(2);
        run.setPassed(1);
        run.setFailed(1);
        run.setErrored(0);
        run.setSkipped(0);
        run.setConfigJson("{\"openjudge\":{\"enabled\":false}}");

        EvalResult pass = result("c0", EvalVerdict.PASS, "DISABLED", null, 10);
        EvalResult fail = result("c1", EvalVerdict.FAIL, "DISABLED",
                "[{\"type\":\"final_contains\",\"ok\":false,\"message\":\"未包含\"}]", 20);
        fail.setScoresJson("{\"rule\":{\"blocking_failed\":1}}");

        builder.buildAndAttach(run, List.of(pass, fail));

        assertNotNull(run.getReportJson());
        JsonNode report = json.readTree(run.getReportJson());
        assertEquals(false, report.path("run").path("gate_passed").asBoolean());
        assertEquals(1, report.path("failed_cases").size());
        assertEquals("规则门禁失败", report.path("failed_cases").get(0).path("kind").asText());
        assertFalse(report.path("openjudge").path("enabled").asBoolean());
        assertNotNull(run.getReportPath());
        assertTrue(Files.isRegularFile(Path.of(run.getReportPath())));
        String md = Files.readString(Path.of(run.getReportPath()));
        assertTrue(md.contains("规则失败"));
        assertTrue(md.contains("OpenJudge"));
    }

    @Test
    void writeWorkspaceFalseKeepsDbJsonOnly() {
        properties.getReport().setWriteWorkspace(false);
        builder = new ReportBuilder(json, properties);

        EvalRun run = new EvalRun();
        run.setId(3L);
        run.setStatus(EvalRunStatus.SUCCEEDED);
        run.setGatePassed(1);
        run.setTotalCases(1);
        run.setPassed(1);
        run.setFailed(0);
        run.setErrored(0);
        run.setSkipped(0);
        run.setConfigJson("{}");

        builder.buildAndAttach(run, List.of(result("c0", EvalVerdict.PASS, "DISABLED", null, 5)));

        assertNotNull(run.getReportJson());
        assertEquals(null, run.getReportPath());
    }

    private EvalResult result(String key, EvalVerdict verdict, String ojStatus,
                              String assertionDetails, int latency) {
        EvalResult one = new EvalResult();
        one.setCaseKey(key);
        one.setVerdict(verdict);
        one.setOpenjudgeStatus(ojStatus);
        one.setAssertionDetailsJson(assertionDetails);
        one.setLatencyMs(latency);
        return one;
    }
}
