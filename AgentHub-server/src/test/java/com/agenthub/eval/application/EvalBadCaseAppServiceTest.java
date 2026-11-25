package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalBadCasePageReq;
import com.agenthub.eval.api.dto.EvalBadCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalPromoteFromResultReq;
import com.agenthub.eval.api.dto.EvalPromoteFromTraceReq;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.support.InMemoryEvalCaseRepository;
import com.agenthub.eval.support.InMemoryEvalResultRepository;
import com.agenthub.eval.support.InMemoryEvalRunRepository;
import com.agenthub.eval.support.InMemoryEvalSuiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 坏案列表与回流。所属域：eval。
 * <p>覆盖：FAIL 分页租户隔离、FAIL promote 升版、ERROR 需 force、非法 result、同 result 允许多次回流。
 * 全程内存仓储；不触发 trial / 模型。
 */
class EvalBadCaseAppServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryEvalSuiteRepository suites = new InMemoryEvalSuiteRepository();
    private final InMemoryEvalCaseRepository cases = new InMemoryEvalCaseRepository();
    private final InMemoryEvalRunRepository runs = new InMemoryEvalRunRepository();
    private final InMemoryEvalResultRepository results = new InMemoryEvalResultRepository();
    private final OperLogWriter operLog = mock(OperLogWriter.class);

    private long currentTenant = 1L;
    private CaseStoreAppService caseStore;
    private EvalRunAppService runService;
    private EvalBadCaseAppService badCases;
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
        // 本测不涉及打分模型：塞一个空 mock，避免为无关分支准备模型数据
        runService = new EvalRunAppService(suites, cases, runs, results, assembler,
                () -> currentTenant, operator, operLog, json, runner, properties,
                mock(com.agenthub.model.domain.repository.ModelInstanceRepository.class));
        badCases = new EvalBadCaseAppService(results, runs, caseStore, assembler,
                () -> currentTenant, operator, operLog, json);

        suiteId = caseStore.create(suiteReq("t.bad", 2, 0)).getId();
    }

    /**
     * FAIL 可回流且目标套件 current_version+1；旧版行不动。
     */
    @Test
    void promoteFailBumpsSuiteVersion() {
        Long failResultId = seedFailResult(suiteId, "c1");
        int before = suites.findById(suiteId).orElseThrow().getCurrentVersion();

        EvalPromoteFromResultReq req = new EvalPromoteFromResultReq();
        req.setResultId(failResultId);
        req.setCaseKey("bc-c1-once");
        req.setTitle("回流一次");
        EvalImportResp resp = badCases.promoteFromResult(suiteId, req);

        assertEquals(before + 1, resp.getCurrentVersion());
        assertEquals(before, resp.getPreviousVersion());
        assertTrue(resp.getCaseCount() >= 1);
        assertEquals(before + 1, suites.findById(suiteId).orElseThrow().getCurrentVersion());
    }

    /**
     * 同 result 多次 promote 允许生成多条（不同 case_key），避免静默丢回流。
     */
    @Test
    void promoteSameResultTwiceAllowedWithDistinctKeys() {
        Long failResultId = seedFailResult(suiteId, "c1");
        EvalPromoteFromResultReq first = new EvalPromoteFromResultReq();
        first.setResultId(failResultId);
        first.setCaseKey("bc-a");
        EvalPromoteFromResultReq second = new EvalPromoteFromResultReq();
        second.setResultId(failResultId);
        second.setCaseKey("bc-b");

        EvalImportResp r1 = badCases.promoteFromResult(suiteId, first);
        EvalImportResp r2 = badCases.promoteFromResult(suiteId, second);
        assertEquals(r1.getCurrentVersion() + 1, r2.getCurrentVersion());
    }

    /**
     * ERROR 默认拒绝；force=true 才可回流。
     */
    @Test
    void promoteErrorRequiresForce() {
        Long errorId = seedVerdictResult(suiteId, "c1", EvalVerdict.ERROR);
        EvalPromoteFromResultReq req = new EvalPromoteFromResultReq();
        req.setResultId(errorId);
        BizException denied = assertThrows(BizException.class, () -> badCases.promoteFromResult(suiteId, req));
        assertEquals(ErrorCode.BAD_REQUEST, denied.getErrorCode());

        req.setForce(true);
        EvalImportResp ok = badCases.promoteFromResult(suiteId, req);
        assertTrue(ok.getCurrentVersion() > 1);
    }

    /**
     * 跨租户 / 不存在 result → 404，不泄密。
     */
    @Test
    void promoteMissingOrCrossTenantIsNotFound() {
        Long failResultId = seedFailResult(suiteId, "c1");
        currentTenant = 99L;
        EvalPromoteFromResultReq req = new EvalPromoteFromResultReq();
        req.setResultId(failResultId);
        BizException cross = assertThrows(BizException.class, () -> badCases.promoteFromResult(suiteId, req));
        assertEquals(ErrorCode.NOT_FOUND, cross.getErrorCode());

        currentTenant = 1L;
        req.setResultId(9_999_999L);
        BizException missing = assertThrows(BizException.class, () -> badCases.promoteFromResult(suiteId, req));
        assertEquals(ErrorCode.NOT_FOUND, missing.getErrorCode());
    }

    /**
     * 坏案分页默认仅 FAIL；租户隔离。
     */
    @Test
    void pageBadCasesDefaultsToFailAndIsolatesTenant() {
        seedFailResult(suiteId, "c1");
        seedVerdictResult(suiteId, "c2", EvalVerdict.ERROR);
        EvalBadCasePageReq req = new EvalBadCasePageReq();
        req.setCurrent(1);
        req.setSize(20);
        PageResult<EvalBadCaseResp> page = badCases.pageBadCases(req);
        assertEquals(1, page.getTotal());
        assertEquals("FAIL", page.getRecords().get(0).getVerdict());

        currentTenant = 2L;
        assertEquals(0, badCases.pageBadCases(req).getTotal());
    }

    /**
     * from-trace 只吃前端摘要，升版写 case，不读 obs 表。
     */
    @Test
    void promoteFromTraceWritesCase() {
        int before = suites.findById(suiteId).orElseThrow().getCurrentVersion();
        EvalPromoteFromTraceReq req = new EvalPromoteFromTraceReq();
        req.setMessage("用户问了什么");
        req.setFinalAnswer("终答摘要");
        req.setTraceId("tr-abc");
        req.setCaseKey("bc-trace-x");
        EvalImportResp resp = badCases.promoteFromTrace(suiteId, req);
        assertEquals(before + 1, resp.getCurrentVersion());
    }

    private Long seedFailResult(Long sid, String caseKey) {
        return seedVerdictResult(sid, caseKey, EvalVerdict.FAIL);
    }

    private Long seedVerdictResult(Long sid, String caseKey, EvalVerdict verdict) {
        EvalRunCreateReq create = new EvalRunCreateReq();
        create.setSuiteId(sid);
        create.setAgentId(1L);
        EvalRunResp run = runService.createRun(create);
        results.forceVerdict(run.getId(), caseKey, verdict);
        return results.listByRun(run.getId()).stream()
                .filter(r -> caseKey.equals(r.getCaseKey()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private EvalSuiteCreateReq suiteReq(String key, int enabled, int disabled) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(key);
        req.setName("坏案套件");
        List<EvalCaseSpec> list = new ArrayList<>();
        for (int i = 1; i <= enabled; i++) {
            EvalCaseSpec c = new EvalCaseSpec();
            c.setCaseKey("c" + i);
            c.setTitle("用例" + i);
            c.setEnabled(1);
            c.setSortOrder(i);
            c.setInput(json.createObjectNode().put("message", "m" + i));
            list.add(c);
        }
        for (int i = 1; i <= disabled; i++) {
            EvalCaseSpec c = new EvalCaseSpec();
            c.setCaseKey("d" + i);
            c.setTitle("停用" + i);
            c.setEnabled(0);
            c.setSortOrder(100 + i);
            c.setInput(json.createObjectNode().put("message", "d" + i));
            list.add(c);
        }
        req.setCases(list);
        return req;
    }
}
