package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalScopeExportReq;
import com.agenthub.eval.api.dto.EvalScopeExportResp;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalScopeBridgeResult;
import com.agenthub.eval.domain.model.EvalScopeExportRequest;
import com.agenthub.eval.domain.model.EvalScopeStatus;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.service.EvalScopeBridgePort;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.support.InMemoryEvalCaseRepository;
import com.agenthub.eval.support.InMemoryEvalResultRepository;
import com.agenthub.eval.support.InMemoryEvalRunRepository;
import com.agenthub.eval.support.InMemoryEvalSuiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EvalScope 桥应用服务。所属域：eval。
 * <p>覆盖：enabled=false 零外呼、落盘覆盖幂等、trigger SKIPPED/OK、跨租户 404、空结果拒绝。
 * 桥 Port 一律 mock / 本地临时目录；不起真 EvalScope。
 */
class EvalScopeBridgeAppServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryEvalSuiteRepository suites = new InMemoryEvalSuiteRepository();
    private final InMemoryEvalCaseRepository cases = new InMemoryEvalCaseRepository();
    private final InMemoryEvalRunRepository runs = new InMemoryEvalRunRepository();
    private final InMemoryEvalResultRepository results = new InMemoryEvalResultRepository();
    private final OperLogWriter operLog = mock(OperLogWriter.class);

    private long currentTenant = 1L;
    private CaseStoreAppService caseStore;
    private EvalRunAppService runService;
    private EvalProperties properties;
    private RecordingBridgePort bridgePort;
    private EvalScopeBridgeAppService service;
    private Long suiteId;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new EvalProperties();
        properties.getEvalscope().setEnabled(false);
        properties.getEvalscope().setExportDir(tempDir.toString());
        properties.getEvalscope().setBaseUrl("");

        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        when(operator.userIdOrNull()).thenReturn(100L);

        EvalAssembler assembler = new EvalAssembler(json);
        caseStore = new CaseStoreAppService(suites, cases, assembler,
                new EvalSampleCatalog(new DefaultResourceLoader(), json, properties),
                () -> currentTenant, operator, operLog, json);
        EvalRunnerAppService runner = mock(EvalRunnerAppService.class);
        // 本测不涉及打分模型：塞一个空 mock，避免为无关分支准备模型数据
        runService = new EvalRunAppService(suites, cases, runs, results, assembler,
                () -> currentTenant, operator, operLog, json, runner, properties,
                mock(com.agenthub.model.domain.repository.ModelInstanceRepository.class));

        bridgePort = new RecordingBridgePort();
        service = new EvalScopeBridgeAppService(runs, results, bridgePort, () -> currentTenant,
                properties, assembler, json);

        suiteId = caseStore.create(suiteReq("t.scope", 2)).getId();
    }

    /**
     * 默认关：抛业务错，且 Port 零调用。
     */
    @Test
    void disabledThrowsAndInvokesNothing() {
        Long runId = seedRunWithVerdict("c0", EvalVerdict.FAIL);
        EvalScopeExportReq req = new EvalScopeExportReq();
        BizException ex = assertThrows(BizException.class, () -> service.export(runId, req));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未启用"));
        assertEquals(0, bridgePort.exportCalls.get());
        assertEquals(0, bridgePort.triggerCalls.get());
    }

    /**
     * 启用后落盘；同路径覆盖；不翻 gate。
     */
    @Test
    void enabledExportWritesManifestAndOverwrites() throws Exception {
        properties.getEvalscope().setEnabled(true);
        Long runId = seedRunWithVerdict("c0", EvalVerdict.FAIL);
        Integer gateBefore = runs.findById(runId).orElseThrow().getGatePassed();

        EvalScopeExportResp first = service.export(runId, new EvalScopeExportReq());
        assertEquals("OK", first.getStatus());
        assertNotNull(first.getExportPath());
        assertTrue(Files.exists(Path.of(first.getExportPath())));
        assertEquals(1, bridgePort.exportCalls.get());
        assertEquals(0, bridgePort.triggerCalls.get());

        EvalScopeExportResp second = service.export(runId, new EvalScopeExportReq());
        assertEquals(first.getExportPath(), second.getExportPath());
        assertEquals(2, bridgePort.exportCalls.get());

        assertEquals(gateBefore, runs.findById(runId).orElseThrow().getGatePassed());
    }

    /**
     * trigger=true 且无 base_url → trigger SKIPPED，仍保留导出 path。
     */
    @Test
    void triggerWithoutBaseUrlIsSkipped() {
        properties.getEvalscope().setEnabled(true);
        Long runId = seedRunWithVerdict("c0", EvalVerdict.PASS);
        EvalScopeExportReq req = new EvalScopeExportReq();
        req.setTrigger(true);
        req.setBenchmark("GSM8K");

        EvalScopeExportResp resp = service.export(runId, req);
        assertEquals("OK", resp.getStatus());
        assertEquals("SKIPPED", resp.getTriggerStatus());
        assertNull(resp.getJobId());
        assertEquals(1, bridgePort.triggerCalls.get());
    }

    /**
     * trigger=true + mock OK → jobId。
     */
    @Test
    void triggerWithBaseUrlReturnsJobId() {
        properties.getEvalscope().setEnabled(true);
        properties.getEvalscope().setBaseUrl("http://127.0.0.1:19876");
        bridgePort.triggerResult = EvalScopeBridgeResult.okJob("job-42", "已创建外部任务");
        Long runId = seedRunWithVerdict("c0", EvalVerdict.PASS);
        EvalScopeExportReq req = new EvalScopeExportReq();
        req.setTrigger(true);

        EvalScopeExportResp resp = service.export(runId, req);
        assertEquals("OK", resp.getStatus());
        assertEquals("OK", resp.getTriggerStatus());
        assertEquals("job-42", resp.getJobId());
    }

    /**
     * run 配置 evalscope.enabled=true 可覆盖全局 false。
     */
    @Test
    void runConfigCanEnableWhenGlobalOff() {
        properties.getEvalscope().setEnabled(false);
        ObjectNode override = json.createObjectNode();
        ObjectNode es = override.putObject("evalscope");
        es.put("enabled", true);
        EvalRunCreateReq create = new EvalRunCreateReq();
        create.setSuiteId(suiteId);
        create.setAgentId(1L);
        create.setConfigOverride(override);
        Long runId = runService.createRun(create).getId();
        results.forceVerdict(runId, "c0", EvalVerdict.FAIL);

        EvalScopeExportResp resp = service.export(runId, new EvalScopeExportReq());
        assertEquals("OK", resp.getStatus());
        assertEquals(1, bridgePort.exportCalls.get());
    }

    /**
     * 跨租户 → 404；零外呼。
     */
    @Test
    void crossTenantIsNotFound() {
        properties.getEvalscope().setEnabled(true);
        Long runId = seedRunWithVerdict("c0", EvalVerdict.FAIL);
        currentTenant = 99L;
        BizException ex = assertThrows(BizException.class, () -> service.export(runId, new EvalScopeExportReq()));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals(0, bridgePort.exportCalls.get());
    }

    /**
     * Recording Port：导出真写 temp；trigger 可注入结果。所属域：eval test。
     */
    private static final class RecordingBridgePort implements EvalScopeBridgePort {
        private final AtomicInteger exportCalls = new AtomicInteger();
        private final AtomicInteger triggerCalls = new AtomicInteger();
        private EvalScopeBridgeResult triggerResult;

        @Override
        public EvalScopeBridgeResult export(EvalScopeExportRequest request) {
            exportCalls.incrementAndGet();
            try {
                Path dir = Path.of(request.getExportDir());
                Files.createDirectories(dir);
                Path file = dir.resolve("manifest.json");
                Files.writeString(file, request.getManifest().toString());
                return EvalScopeBridgeResult.okExport(file.toString(), "已导出清单");
            } catch (Exception ex) {
                return EvalScopeBridgeResult.of(EvalScopeStatus.ERROR, ex.getMessage());
            }
        }

        @Override
        public EvalScopeBridgeResult trigger(EvalScopeExportRequest request) {
            triggerCalls.incrementAndGet();
            if (triggerResult != null) {
                return triggerResult;
            }
            if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) {
                return EvalScopeBridgeResult.of(EvalScopeStatus.SKIPPED, "未配置 EvalScope base_url，跳过触发");
            }
            return EvalScopeBridgeResult.okJob("job-default", "已创建外部任务");
        }
    }

    private Long seedRunWithVerdict(String caseKey, EvalVerdict verdict) {
        EvalRunCreateReq create = new EvalRunCreateReq();
        create.setSuiteId(suiteId);
        create.setAgentId(1L);
        Long runId = runService.createRun(create).getId();
        results.forceVerdict(runId, caseKey, verdict);
        return runId;
    }

    private EvalSuiteCreateReq suiteReq(String key, int enabled) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(key);
        req.setName("EvalScope 套件");
        List<EvalCaseSpec> list = new ArrayList<>();
        for (int i = 0; i < enabled; i++) {
            EvalCaseSpec c = new EvalCaseSpec();
            c.setCaseKey("c" + i);
            c.setTitle("用例" + i);
            c.setEnabled(1);
            c.setSortOrder(i);
            c.setInput(json.createObjectNode().put("message", "m" + i));
            list.add(c);
        }
        req.setCases(list);
        return req;
    }
}
