package com.agenthub.eval.infra.evalscope;

import com.agenthub.eval.domain.model.EvalScopeBridgeResult;
import com.agenthub.eval.domain.model.EvalScopeExportRequest;
import com.agenthub.eval.domain.model.EvalScopeStatus;
import com.agenthub.eval.infra.evalscope.EvalScopeHttpClient.EvalScopeHttpException;
import com.agenthub.eval.infra.evalscope.EvalScopeHttpClient.HttpJsonResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvalScope Adapter 单元测。所属域：eval。
 * <p>落盘覆盖、trigger 无 base_url=SKIPPED、HTTP OK/ERROR；一律 mock 客户端。
 */
class EvalScopeBridgeAdapterTest {

    private final ObjectMapper json = new ObjectMapper();
    private EvalScopeHttpClient client;
    private EvalScopeBridgeAdapter adapter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        client = mock(EvalScopeHttpClient.class);
        adapter = new EvalScopeBridgeAdapter(client, json);
    }

    @Test
    void exportOverwritesSamePath() throws Exception {
        EvalScopeExportRequest req = baseReq();
        ObjectNode manifest = json.createObjectNode().put("v", 1);
        req.setManifest(manifest);

        EvalScopeBridgeResult first = adapter.export(req);
        assertEquals(EvalScopeStatus.OK, first.getStatus());
        assertTrue(Files.exists(Path.of(first.getExportPath())));

        manifest.put("v", 2);
        EvalScopeBridgeResult second = adapter.export(req);
        assertEquals(first.getExportPath(), second.getExportPath());
        String body = Files.readString(Path.of(second.getExportPath()));
        assertTrue(body.contains("\"v\" : 2") || body.contains("\"v\":2") || body.contains("\"v\": 2"));
        verify(client, never()).createJob(anyString(), anyLong(), any());
    }

    @Test
    void triggerWithoutBaseUrlSkippedNoHttp() {
        EvalScopeExportRequest req = baseReq();
        req.setBaseUrl("  ");
        EvalScopeBridgeResult result = adapter.trigger(req);
        assertEquals(EvalScopeStatus.SKIPPED, result.getStatus());
        verify(client, never()).createJob(anyString(), anyLong(), any());
    }

    @Test
    void triggerHttpOkMapsJobId() {
        ObjectNode body = json.createObjectNode().put("job_id", "j-9");
        when(client.createJob(anyString(), anyLong(), any()))
                .thenReturn(new HttpJsonResponse(200, body, body.toString()));
        EvalScopeExportRequest req = baseReq();
        req.setBaseUrl("http://127.0.0.1:19876");
        EvalScopeBridgeResult result = adapter.trigger(req);
        assertEquals(EvalScopeStatus.OK, result.getStatus());
        assertEquals("j-9", result.getJobId());
    }

    @Test
    void triggerHttpErrorMapsError() {
        when(client.createJob(anyString(), anyLong(), any()))
                .thenThrow(new EvalScopeHttpException("EvalScope 超时", 0, null, null));
        EvalScopeExportRequest req = baseReq();
        req.setBaseUrl("http://127.0.0.1:19876");
        EvalScopeBridgeResult result = adapter.trigger(req);
        assertEquals(EvalScopeStatus.ERROR, result.getStatus());
        assertTrue(result.getMessage().contains("超时"));
    }

    private EvalScopeExportRequest baseReq() {
        EvalScopeExportRequest req = new EvalScopeExportRequest();
        req.setRunId(7L);
        req.setExportDir(tempDir.resolve("eval-7").resolve("evalscope").toString());
        req.setTimeoutMs(5_000L);
        req.setClientToken("run-7");
        return req;
    }
}
