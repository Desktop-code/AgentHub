package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalResultPageReq;
import com.agenthub.eval.api.dto.EvalResultResp;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.service.EvalRunStateMachine;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.support.InMemoryEvalCaseRepository;
import com.agenthub.eval.support.InMemoryEvalResultRepository;
import com.agenthub.eval.support.InMemoryEvalRunRepository;
import com.agenthub.eval.support.InMemoryEvalSuiteRepository;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跑批壳核心语义。所属域：eval。
 * <p>覆盖：建跑落 PENDING + N 条占位结果、套件/版本/空用例等边界、
 * 取消两条收尾口径、租户隔离（A 不可读/取消 B）、非幂等连点行为。
 * <p>全程内存仓储 + mock 审计，不连库、不起服务、不出站。
 */
class EvalRunAppServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryEvalSuiteRepository suites = new InMemoryEvalSuiteRepository();
    private final InMemoryEvalCaseRepository cases = new InMemoryEvalCaseRepository();
    private final InMemoryEvalRunRepository runs = new InMemoryEvalRunRepository();
    private final InMemoryEvalResultRepository results = new InMemoryEvalResultRepository();
    private final OperLogWriter operLog = mock(OperLogWriter.class);
    private final ModelInstanceRepository modelInstances = mock(ModelInstanceRepository.class);

    private long currentTenant = 1L;
    private CaseStoreAppService caseStore;
    private EvalRunAppService service;

    /** 建跑测试共用的套件（3 条启用 + 1 条停用，停用不该被建进跑批） */
    private Long suiteId;

    @BeforeEach
    void setUp() {
        EvalProperties properties = new EvalProperties();
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        when(operator.userIdOrNull()).thenReturn(100L);

        EvalAssembler assembler = new EvalAssembler(json);
        caseStore = new CaseStoreAppService(suites, cases, assembler,
                new EvalSampleCatalog(new DefaultResourceLoader(), json, properties),
                () -> currentTenant, operator, operLog, json);
        EvalRunnerAppService runner = mock(EvalRunnerAppService.class);
        service = new EvalRunAppService(suites, cases, runs, results, assembler,
                () -> currentTenant, operator, operLog, json, runner, properties, modelInstances);

        suiteId = caseStore.create(suiteReq("t.run", 3, 1)).getId();
    }

    /**
     * 建跑：status=PENDING、total_cases=N（只数启用）、N 条占位结果行且 verdict 全 null。
     */
    @Test
    void createRunWritesPendingRunAndPlaceholderResults() {
        EvalRunResp resp = service.createRun(runReq(suiteId, null));

        assertEquals("PENDING", resp.getStatus());
        assertEquals(3, resp.getTotalCases());
        assertEquals(1, resp.getSuiteVersion());
        assertEquals(0, resp.getPassed());
        assertEquals(0, resp.getFailed());
        assertEquals(0, resp.getSkipped());
        assertNull(resp.getGatePassed(), "建跑时门禁结果必须是 null");
        assertNull(resp.getAgentSnapshotHash(), "本刀不计算指纹");
        assertNull(resp.getStartedTime());
        assertNull(resp.getFinishedTime());
        assertNull(resp.getReportPath(), "本刀不产报告");
        assertEquals(100L, resp.getTriggerUserId());

        List<EvalResultResp> rows = service.listResults(resp.getId(), pageReq()).getRecords();
        assertEquals(3, rows.size());
        rows.forEach(one -> assertNull(one.getVerdict(), "占位行不得预置判定"));
        rows.forEach(one -> assertNull(one.getSessionId()));
        rows.forEach(one -> assertNull(one.getTraceId()));
        // sort_order 升序，且与用例定义一致
        assertEquals(List.of("c0", "c1", "c2"), rows.stream().map(EvalResultResp::getCaseKey).toList());
    }

    /**
     * openjudge 开关：缺省关闭；suite 默认配置与请求覆盖深合并，覆盖方优先。
     * <p>只有 enabled=true 没有打分模型时仍按关闭处理（无模型 = 零 Judge 出站）。
     */
    @Test
    void createRunSnapshotsMergedConfig() {
        EvalRunResp byDefault = service.createRun(runReq(suiteId, null));
        assertEquals(0, byDefault.getOpenjudgeEnabled(), "缺配置时默认关闭");
        // 套件默认配置里有 max_parallel，应被快照带过来
        assertEquals(1, byDefault.getConfig().get("max_parallel").asInt());

        ObjectNode override = json.createObjectNode();
        override.putObject("openjudge").put("enabled", true);
        EvalRunResp byOverride = service.createRun(runReqWithOverride(suiteId, override));
        assertEquals(0, byOverride.getOpenjudgeEnabled(), "只开开关、没选模型 → 仍不打分");
        assertEquals(1, byOverride.getConfig().get("max_parallel").asInt(), "未覆盖的字段要保留基线值");
    }

    /**
     * 选了合法 CHAT 实例：把 id 钉进快照并启用软评分；出参可回显。
     */
    @Test
    void createRunPinsSelectedJudgeModel() {
        when(modelInstances.findById(501L)).thenReturn(Optional.of(model("501", 1, "CHAT")));

        EvalRunCreateReq req = runReq(suiteId, null);
        req.setJudgeModelInstanceId(501L);
        EvalRunResp resp = service.createRun(req);

        assertEquals(1, resp.getOpenjudgeEnabled());
        assertEquals(Long.valueOf(501L), resp.getJudgeModelInstanceId());
        assertTrue(resp.getConfig().get("openjudge").get("enabled").asBoolean());
        assertEquals(501L, resp.getConfig().get("openjudge").get("judge_model_instance_id").asLong());
    }

    /**
     * config_override 里带 judge_model_instance_id 等同选择（顶层字段优先，但本测只给 override）。
     */
    @Test
    void createRunAcceptsJudgeModelFromConfigOverride() {
        when(modelInstances.findById(502L)).thenReturn(Optional.of(model("502", 1, "CHAT")));
        ObjectNode override = json.createObjectNode();
        override.putObject("openjudge").put("judge_model_instance_id", 502L);

        EvalRunResp resp = service.createRun(runReqWithOverride(suiteId, override));

        assertEquals(1, resp.getOpenjudgeEnabled());
        assertEquals(Long.valueOf(502L), resp.getJudgeModelInstanceId());
    }

    /**
     * 套件默认开着 openjudge 且带默认 judge 模型，但建跑没选模型：
     * 本 run 必须覆盖成 DISABLED，且不得偷用套件默认模型。
     */
    @Test
    void createRunOverridesSuiteDefaultJudgeWhenNotSelected() {
        Long suiteWithDefault = caseStore.create(suiteReqWithJudgeDefault("t.oj.default")).getId();

        EvalRunResp resp = service.createRun(runReq(suiteWithDefault, null));

        assertEquals(0, resp.getOpenjudgeEnabled());
        assertNull(resp.getJudgeModelInstanceId());
        assertFalse(resp.getConfig().get("openjudge").get("enabled").asBoolean());
        assertTrue(resp.getConfig().get("openjudge").get("judge_model_instance_id").isNull());
    }

    /**
     * 非法打分模型：不存在 / 已停用 / 用途非 CHAT → 400，且不落 run / 占位结果。
     */
    @Test
    void createRunRejectsInvalidJudgeModelWithoutWritingRun() {
        long before = results.countByRun(1L);

        // 不存在（mock 默认空）
        EvalRunCreateReq missing = runReq(suiteId, null);
        missing.setJudgeModelInstanceId(999L);
        BizException ex1 = assertThrows(BizException.class, () -> service.createRun(missing));
        assertEquals(ErrorCode.BAD_REQUEST, ex1.getErrorCode());
        assertTrue(ex1.getMessage().contains("打分模型"));

        // 已停用
        when(modelInstances.findById(503L)).thenReturn(Optional.of(model("503", 0, "CHAT")));
        EvalRunCreateReq disabled = runReq(suiteId, null);
        disabled.setJudgeModelInstanceId(503L);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> service.createRun(disabled)).getErrorCode());

        // 用途不是 CHAT
        when(modelInstances.findById(504L)).thenReturn(Optional.of(model("504", 1, "EMBEDDING")));
        EvalRunCreateReq wrongCapability = runReq(suiteId, null);
        wrongCapability.setJudgeModelInstanceId(504L);
        BizException ex3 = assertThrows(BizException.class, () -> service.createRun(wrongCapability));
        assertTrue(ex3.getMessage().contains("CHAT"));

        assertEquals(before, results.countByRun(1L), "拒绝时不得残留任何结果行");
    }

    /**
     * 指定历史版本：钉死该版本，用例数按该版本算。
     */
    @Test
    void createRunPinsRequestedVersion() {
        // 升到版本 2，只留 2 条启用
        caseStore.importSuite(suiteId, spec("t.run", 2));

        EvalRunResp v1 = service.createRun(runReq(suiteId, 1));
        assertEquals(1, v1.getSuiteVersion());
        assertEquals(3, v1.getTotalCases());

        EvalRunResp v2 = service.createRun(runReq(suiteId, 2));
        assertEquals(2, v2.getSuiteVersion());
        assertEquals(2, v2.getTotalCases());
    }

    /**
     * 边界：版本超范围 / 版本号非法 → 400，且不落 run。
     */
    @Test
    void createRunRejectsBadVersionWithoutWritingRun() {
        long before = results.countByRun(1L);
        BizException tooHigh = assertThrows(BizException.class, () -> service.createRun(runReq(suiteId, 2)));
        assertEquals(ErrorCode.BAD_REQUEST, tooHigh.getErrorCode());
        assertTrue(tooHigh.getMessage().contains("套件版本"));

        assertThrows(BizException.class, () -> service.createRun(runReq(suiteId, 0)));
        assertEquals(before, results.countByRun(1L), "拒绝时不得写入任何结果行");
    }

    /**
     * 边界：套件不存在 / 跨租户 → 404；不泄露存在性。
     */
    @Test
    void createRunRejectsMissingOrCrossTenantSuite() {
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.createRun(runReq(9999L, null))).getErrorCode());

        currentTenant = 2L;
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.createRun(runReq(suiteId, null))).getErrorCode());
    }

    /**
     * 边界：套件被停用 → 400，不允许建跑。
     */
    @Test
    void createRunRejectsDisabledSuite() {
        com.agenthub.eval.api.dto.EvalSuiteUpdateReq update = new com.agenthub.eval.api.dto.EvalSuiteUpdateReq();
        update.setName("停用套件");
        update.setEnabled(0);
        caseStore.update(suiteId, update);

        BizException ex = assertThrows(BizException.class, () -> service.createRun(runReq(suiteId, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("停用"));
    }

    /**
     * 边界：该版本没有启用用例 → 400「无可跑用例」，不建空 run（N=0 的假绿不做）。
     */
    @Test
    void createRunRejectsVersionWithoutEnabledCases() {
        // 全部用例停用：没有任何可跑用例
        Long disabledSuite = caseStore.create(suiteReq("t.none", 0, 2)).getId();

        BizException ex = assertThrows(BizException.class, () -> service.createRun(runReq(disabledSuite, null)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("没有可跑用例"));
        assertEquals(0, results.countByRun(1L), "拒绝时不得残留任何结果行");
    }

    /**
     * 幂等口径：create 非幂等，同参数连点产生两条独立 PENDING（可接受，P0 不引入 client_token）。
     */
    @Test
    void createRunIsNotIdempotentByDesign() {
        EvalRunResp first = service.createRun(runReq(suiteId, null));
        EvalRunResp second = service.createRun(runReq(suiteId, null));

        assertFalse(first.getId().equals(second.getId()));
        assertEquals("PENDING", first.getStatus());
        assertEquals("PENDING", second.getStatus());
        assertEquals(3, results.countByRun(first.getId()));
        assertEquals(3, results.countByRun(second.getId()));
    }

    /**
     * 取消分支一：PENDING 直接取消、无任何 PASS/FAIL → SKIPPED 全打上，终态 FAILED。
     */
    @Test
    void cancelPendingWithoutJudgedEndsFailed() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));

        EvalRunResp canceled = service.cancel(created.getId());

        assertEquals("FAILED", canceled.getStatus());
        assertEquals(3, canceled.getSkipped());
        assertEquals(0, canceled.getPassed());
        assertEquals(0, canceled.getGatePassed());
        assertNotNull(canceled.getFinishedTime());

        List<EvalResultResp> rows = service.listResults(created.getId(), pageReq()).getRecords();
        rows.forEach(one -> assertEquals("SKIPPED", one.getVerdict(), "取消后未完成行必须是 SKIPPED"));
    }

    /**
     * 取消分支二：已有 PASS/FAIL 且其余未跑 → PARTIAL；已有判定不被覆盖。
     */
    @Test
    void cancelWithSomeJudgedEndsPartial() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));
        // 模拟刀2b/3 已回填一条 PASS（本刀生产路径不会写 PASS，仅测试构造）
        results.forceVerdict(created.getId(), "c0", EvalVerdict.PASS);

        EvalRunResp canceled = service.cancel(created.getId());

        assertEquals("PARTIAL", canceled.getStatus());
        assertEquals(1, canceled.getPassed());
        assertEquals(2, canceled.getSkipped());
        List<EvalResultResp> rows = service.listResults(created.getId(), pageReq()).getRecords();
        assertEquals("PASS", rows.get(0).getVerdict(), "已有判定不得被取消覆盖");
        assertEquals("SKIPPED", rows.get(1).getVerdict());
    }

    /**
     * 冲突口径：终态再取消 → 400，不静默成功。
     */
    @Test
    void cancelTerminalRunIsConflict() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));
        service.cancel(created.getId());

        BizException ex = assertThrows(BizException.class, () -> service.cancel(created.getId()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已结束"));
    }

    /**
     * 取消的幂等边界：已停在 CANCELING（上次收尾未落终态）时可以重试收尾，
     * 但收尾成终态后再来就一律冲突。
     */
    @Test
    void cancelOnCancelingFinishesIdempotently() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));
        // 直接改库模拟「CANCELING 卡住」：本刀生产路径不会停在这个态，属异常恢复场景
        EvalRun stuck = runs.findById(created.getId()).orElseThrow();
        EvalRunStateMachine.moveTo(stuck, EvalRunStatus.CANCELING);
        runs.updateState(stuck, 100L);

        EvalRunResp finished = service.cancel(created.getId());
        assertEquals("FAILED", finished.getStatus());
        assertEquals(3, finished.getSkipped());

        // 再次取消：终态冲突
        assertThrows(BizException.class, () -> service.cancel(created.getId()));
    }

    /**
     * 租户隔离：A 的 run 对 B 一律 404（读、列表、取消三条路径都不泄露）。
     */
    @Test
    void otherTenantCannotReadOrCancelRun() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));

        currentTenant = 2L;
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.getRun(created.getId())).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.listResults(created.getId(), pageReq())).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(BizException.class,
                () -> service.cancel(created.getId())).getErrorCode());

        // 回到 A 视角：状态没被 B 改动
        currentTenant = 1L;
        assertEquals("PENDING", service.getRun(created.getId()).getStatus());
    }

    /**
     * 结果分页：按 sort_order 升序，分页边界不重不漏。
     */
    @Test
    void resultsArePagedInSortOrder() {
        EvalRunResp created = service.createRun(runReq(suiteId, null));

        EvalResultPageReq first = new EvalResultPageReq();
        first.setCurrent(1);
        first.setSize(2);
        PageResult<EvalResultResp> page1 = service.listResults(created.getId(), first);
        assertEquals(3, page1.getTotal());
        assertEquals(List.of("c0", "c1"), page1.getRecords().stream().map(EvalResultResp::getCaseKey).toList());

        EvalResultPageReq second = new EvalResultPageReq();
        second.setCurrent(2);
        second.setSize(2);
        PageResult<EvalResultResp> page2 = service.listResults(created.getId(), second);
        assertEquals(List.of("c2"), page2.getRecords().stream().map(EvalResultResp::getCaseKey).toList());
    }

    // ------------------------------------------------------------------
    // 构造工具：只做数据准备，不含断言
    // ------------------------------------------------------------------

    private EvalResultPageReq pageReq() {
        EvalResultPageReq req = new EvalResultPageReq();
        req.setSize(50);
        return req;
    }

    private EvalRunCreateReq runReq(Long suiteId, Integer version) {
        EvalRunCreateReq req = new EvalRunCreateReq();
        req.setSuiteId(suiteId);
        req.setSuiteVersion(version);
        req.setAgentId(7L);
        return req;
    }

    private EvalRunCreateReq runReqWithOverride(Long suiteId, ObjectNode override) {
        EvalRunCreateReq req = runReq(suiteId, null);
        req.setConfigOverride(override);
        return req;
    }

    private EvalSuiteCreateReq suiteReq(String key, int enabledCount, int disabledCount) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(key);
        req.setName("跑批用套件");
        ObjectNode config = json.createObjectNode();
        config.put("max_parallel", 1);
        req.setDefaultConfig(config);
        req.setCases(caseSpecs(enabledCount, disabledCount));
        return req;
    }

    /**
     * 套件默认配置里就开着 openjudge 并带默认 Judge 模型，用于验「不选必须覆盖关闭」。
     */
    private EvalSuiteCreateReq suiteReqWithJudgeDefault(String key) {
        EvalSuiteCreateReq req = suiteReq(key, 1, 0);
        ObjectNode config = json.createObjectNode();
        config.put("max_parallel", 1);
        ObjectNode openjudge = config.putObject("openjudge");
        openjudge.put("enabled", true);
        openjudge.put("judge_model_instance_id", 999L);
        req.setDefaultConfig(config);
        return req;
    }

    /**
     * 构造模型实例（仅本测校验用字段）。
     *
     * @param id         实例 id
     * @param status     1 启用 / 0 停用
     * @param capability 用途码
     * @return 实例
     */
    private ModelInstance model(String id, int status, String capability) {
        ModelInstance one = new ModelInstance();
        one.setId(Long.parseLong(id));
        one.setStatus(status);
        one.setCapability(capability);
        one.setDisplayName("模型 " + id);
        return one;
    }

    private com.agenthub.eval.domain.model.EvalSuiteSpec spec(String key, int enabledCount) {
        com.agenthub.eval.domain.model.EvalSuiteSpec spec = new com.agenthub.eval.domain.model.EvalSuiteSpec();
        spec.setSuiteKey(key);
        spec.setName("导入版本");
        spec.setCases(caseSpecs(enabledCount, 0));
        return spec;
    }

    private List<EvalCaseSpec> caseSpecs(int enabledCount, int disabledCount) {
        List<EvalCaseSpec> list = new ArrayList<>();
        for (int i = 0; i < enabledCount; i++) {
            list.add(caseSpec("c" + i, i * 10, 1));
        }
        for (int i = 0; i < disabledCount; i++) {
            list.add(caseSpec("d" + i, 1000 + i * 10, 0));
        }
        return list;
    }

    private EvalCaseSpec caseSpec(String key, int sortOrder, int enabled) {
        EvalCaseSpec one = new EvalCaseSpec();
        one.setCaseKey(key);
        one.setTitle("用例 " + key);
        one.setInput(json.createObjectNode().put("message", "问题 " + key));
        one.setSortOrder(sortOrder);
        one.setEnabled(enabled);
        return one;
    }
}
