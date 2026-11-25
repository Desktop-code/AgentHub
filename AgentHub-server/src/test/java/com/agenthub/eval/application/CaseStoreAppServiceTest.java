package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.api.dto.EvalSuiteDetailResp;
import com.agenthub.eval.api.dto.EvalSuitePageReq;
import com.agenthub.eval.api.dto.EvalSuiteUpdateReq;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.agenthub.eval.infra.EvalProperties;
import com.agenthub.eval.support.InMemoryEvalCaseRepository;
import com.agenthub.eval.support.InMemoryEvalSuiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用例库核心语义：建套件、导入升版本、旧版本保留、租户隔离、平台行只读、键冲突。
 * 所属域：eval。全程内存仓储，不连数据库、不出站。
 */
class CaseStoreAppServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemoryEvalSuiteRepository suites = new InMemoryEvalSuiteRepository();
    private final InMemoryEvalCaseRepository cases = new InMemoryEvalCaseRepository();
    private final OperLogWriter operLog = mock(OperLogWriter.class);

    private long currentTenant = 1L;
    private CaseStoreAppService service;

    @BeforeEach
    void setUp() {
        EvalProperties properties = new EvalProperties();
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        when(operator.userIdOrNull()).thenReturn(100L);

        EvalAssembler assembler = new EvalAssembler(json);
        EvalSampleCatalog catalog = new EvalSampleCatalog(new DefaultResourceLoader(), json, properties);
        service = new CaseStoreAppService(suites, cases, assembler, catalog,
                () -> currentTenant, operator, operLog, json);
    }

    /**
     * 导入升版本：旧版本用例行仍在库，current_version 前进一位。
     */
    @Test
    void importCreatesNewVersionAndKeepsOldRows() {
        Long id = createSuite("t.import", 2);

        EvalImportResp first = service.importSuite(id, spec("t.import", 3));
        assertEquals(1, first.getPreviousVersion());
        assertEquals(2, first.getCurrentVersion());
        assertEquals(3, first.getCaseCount());
        assertEquals(5, cases.countAllVersions(id), "版本1的2条 + 版本2的3条都应在库");

        long v1 = cases.countByVersion(id, 1);
        long v2 = cases.countByVersion(id, 2);
        assertEquals(2, v1);
        assertEquals(3, v2);

        EvalImportResp second = service.importSuite(id, spec("t.import", 1));
        assertEquals(3, second.getCurrentVersion());
        assertEquals(2, second.getPreviousVersion());
        assertEquals(6, cases.countAllVersions(id));

        // 历史版本可通过 listCases 回读，且 currentVersion 只增
        EvalSuiteDetailResp detail = service.get(id);
        assertEquals(3, detail.getCurrentVersion());
        assertEquals(2, service.listCases(id, 1).size());
        assertEquals(3, service.listCases(id, 2).size());
        assertEquals(1, service.listCases(id, 3).size());
    }

    /**
     * 幂等边界：空 cases 的导入被拒，且不推进版本（不落半截版本）。
     */
    @Test
    void importWithEmptyCasesIsRejectedAndVersionUnchanged() {
        Long id = createSuite("t.empty", 1);
        EvalSuiteSpec empty = new EvalSuiteSpec();
        empty.setCases(List.of());

        assertThrows(BizException.class, () -> service.importSuite(id, empty));
        assertEquals(1, service.get(id).getCurrentVersion());
        assertEquals(1, cases.countByVersion(id, 1));
    }

    /**
     * 结构化校验失败时整套回滚：不写任何用例行。
     */
    @Test
    void importWithBadCaseLeavesNothingWritten() {
        Long id = createSuite("t.bad", 1);
        EvalSuiteSpec bad = spec("t.bad", 2);
        // 第二条缺 input.message → 整批 400
        bad.getCases().get(1).setInput(json.createObjectNode());

        assertThrows(BizException.class, () -> service.importSuite(id, bad));
        assertEquals(1, service.get(id).getCurrentVersion());
        assertEquals(1, cases.countAllVersions(id));
    }

    /**
     * 租户隔离：A 的套件对 B 一律 404，且 B 改不动。
     */
    @Test
    void otherTenantSeesNotFoundAndCannotWrite() {
        Long id = createSuite("t.iso", 1);

        currentTenant = 2L;
        BizException read = assertThrows(BizException.class, () -> service.get(id));
        assertEquals(ErrorCode.NOT_FOUND, read.getErrorCode());
        assertThrows(BizException.class, () -> service.update(id, updateReq("改")));
        assertThrows(BizException.class, () -> service.importSuite(id, spec("t.iso", 2)));
        assertThrows(BizException.class, () -> service.remove(id));

        // 回到 A 视角，数据未被动过
        currentTenant = 1L;
        assertEquals("租户A套件", service.get(id).getName());
        assertEquals(1, service.get(id).getCurrentVersion());
    }

    /**
     * 平台只读样例：租户能看，不能改。
     */
    @Test
    void platformSuiteIsReadableButNotWritable() {
        Long platformId = suites.seedPlatformSuite("platform.sample");

        EvalSuiteDetailResp readable = service.get(platformId);
        assertTrue(readable.getReadOnly());

        BizException write = assertThrows(BizException.class, () -> service.update(platformId, updateReq("改")));
        assertEquals(ErrorCode.FORBIDDEN, write.getErrorCode());
        assertThrows(BizException.class, () -> service.importSuite(platformId, spec("platform.sample", 1)));
        assertThrows(BizException.class, () -> service.remove(platformId));
    }

    /**
     * 同租户重复 suite_key：明确冲突，不双插。
     */
    @Test
    void duplicateSuiteKeyIsRejected() {
        createSuite("t.dup", 1);
        EvalSuiteCreateReq again = createReq("t.dup", 1);
        BizException ex = assertThrows(BizException.class, () -> service.create(again));
        assertEquals(ErrorCode.DUPLICATE, ex.getErrorCode());
    }

    /**
     * 已删除套件的键位仍占用物理 UK；再次创建给出可读提示而不是数据库报错。
     */
    @Test
    void deletedSuiteKeepsKeyOccupied() {
        Long id = createSuite("t.dead", 1);
        service.remove(id);
        assertThrows(BizException.class, () -> service.get(id));

        BizException ex = assertThrows(BizException.class, () -> service.create(createReq("t.dead", 1)));
        assertEquals(ErrorCode.DUPLICATE, ex.getErrorCode());
    }

    /**
     * 元数据更新不动 cases、不动版本。
     */
    @Test
    void updateMetaKeepsCasesAndVersion() {
        Long id = createSuite("t.meta", 2);

        EvalSuiteUpdateReq req = new EvalSuiteUpdateReq();
        req.setName("新名字");
        req.setDescription("新描述");
        req.setEnabled(0);
        EvalSuiteDetailResp detail = service.update(id, req);

        assertEquals("新名字", detail.getName());
        assertEquals(0, detail.getEnabled());
        assertEquals(1, detail.getCurrentVersion());
        assertEquals(2, detail.getCases().size());
    }

    /**
     * 导入时请求体自带 suite_key 且与目标不一致 → 400，避免「贴错文件」被静默接受。
     */
    @Test
    void importWithMismatchedSuiteKeyIsRejected() {
        Long id = createSuite("t.match", 1);
        BizException ex = assertThrows(BizException.class, () -> service.importSuite(id, spec("other.key", 2)));
        assertTrue(ex.getMessage().contains("suite_key"));
        assertEquals(1, service.get(id).getCurrentVersion());
        assertEquals(1, cases.countAllVersions(id), "拒绝时不得写入任何用例行");
    }

    /**
     * 单用例写：复制旧版本到新版本，只替换目标 case_key。
     */
    @Test
    void upsertCaseCreatesNewVersionWithCopiedRows() {
        Long id = createSuite("t.case", 2);

        EvalCaseSpec change = new EvalCaseSpec();
        change.setCaseKey("c0");
        change.setTitle("改过的标题");
        change.setInput(json.createObjectNode().put("message", "改过的输入"));
        EvalImportResp resp = service.upsertCase(id, change);

        assertEquals(2, resp.getCurrentVersion());
        assertEquals(2, resp.getCaseCount());
        assertEquals(2, cases.countByVersion(id, 2));
        assertEquals(2, cases.countByVersion(id, 1), "旧版本行必须原样保留");

        List<EvalCaseResp> v2 = service.listCases(id, 2);
        EvalCaseResp replaced = v2.stream().filter(one -> "c0".equals(one.getCaseKey())).findFirst().orElseThrow();
        assertEquals("改过的标题", replaced.getTitle());
        assertEquals("原标题 c0", service.listCases(id, 1).get(0).getTitle());
    }

    /**
     * 删用例同样是升版本；删空被拒。
     */
    @Test
    void deleteCaseBumpsVersionAndRefusesEmpty() {
        Long id = createSuite("t.del", 2);
        EvalImportResp resp = service.deleteCase(id, "c0");
        assertEquals(2, resp.getCurrentVersion());
        assertEquals(1, resp.getCaseCount());
        assertTrue(service.listCases(id, 2).stream().noneMatch(one -> "c0".equals(one.getCaseKey())));

        BizException ex = assertThrows(BizException.class, () -> service.deleteCase(id, "c1"));
        assertTrue(ex.getMessage().contains("最后一条"));
        assertThrows(BizException.class, () -> service.deleteCase(id, "not-exist"));
    }

    /**
     * classpath 平台样例能装进当前租户；suite_key / cases 数与 JSON 一致；检索工具名为实符。
     */
    @Test
    void platformSampleImportsIntoCurrentTenant() {
        EvalImportResp resp = service.importPlatformSample();
        assertNull(resp.getPreviousVersion());
        assertEquals(1, resp.getCurrentVersion());
        assertEquals(5, resp.getCaseCount(), "与 platform-smoke-suite.json 的 cases 条数一致");

        EvalSuiteDetailResp detail = service.get(resp.getSuiteId());
        assertEquals("platform.smoke.rag", detail.getSuiteKey());
        assertFalse(detail.getReadOnly());
        assertEquals(1L, detail.getTenantId());

        // 落库后断言工具名已是 searchKnowledgeBase（非旧草名 searchKB）
        boolean hasSearchKb = service.listCases(resp.getSuiteId(), 1).stream().anyMatch(one -> {
            String raw = one.getAssertions() == null ? "" : one.getAssertions().toString();
            return raw.contains("searchKnowledgeBase") && !raw.contains("searchKB\"");
        });
        assertTrue(hasSearchKb, "样例断言须含 searchKnowledgeBase");

        // 再次调用 = 升版本，不是新建
        EvalImportResp again = service.importPlatformSample();
        assertEquals(resp.getSuiteId(), again.getSuiteId());
        assertEquals(2, again.getCurrentVersion());
    }

    /**
     * 样例资源缺失时导入接口给明确 4xx（含路径），不落库、不发明 cases。
     */
    @Test
    void missingSampleFailsLoudly() {
        EvalProperties properties = new EvalProperties();
        properties.getSamples().setLocation("classpath:eval/samples/not-exists.json");
        OperatorContext operator = mock(OperatorContext.class);
        when(operator.requireUserId()).thenReturn(100L);
        CaseStoreAppService broken = new CaseStoreAppService(suites, cases, new EvalAssembler(json),
                new EvalSampleCatalog(new DefaultResourceLoader(), json, properties),
                () -> currentTenant, operator, operLog, json);

        assertTrue(suites.findByTenantAndKey(1L, "platform.smoke.rag").isEmpty());
        BizException ex = assertThrows(BizException.class, broken::importPlatformSample);
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("平台样例资源缺失"));
        assertTrue(ex.getMessage().contains("eval/samples/not-exists.json"));
        assertTrue(suites.findByTenantAndKey(1L, "platform.smoke.rag").isEmpty(), "缺资源不得落库");
    }

    /**
     * 列表默认并集平台样例，便于「导入到本租户」。
     */
    @Test
    void pageIncludesPlatformRowsByDefault() {
        createSuite("t.page", 1);
        suites.seedPlatformSuite("platform.only");

        EvalSuitePageReq req = new EvalSuitePageReq();
        req.setSize(50);
        assertEquals(2, service.page(req).getTotal());

        req.setIncludePlatform(false);
        assertEquals(1, service.page(req).getTotal());
    }

    // ------------------------------------------------------------------
    // 以下为构造工具：只做数据准备，不含断言
    // ------------------------------------------------------------------

    private Long createSuite(String suiteKey, int caseCount) {
        EvalSuiteCreateReq req = createReq(suiteKey, caseCount);
        return service.create(req).getId();
    }

    private EvalSuiteCreateReq createReq(String suiteKey, int caseCount) {
        EvalSuiteCreateReq req = new EvalSuiteCreateReq();
        req.setSuiteKey(suiteKey);
        req.setName("租户A套件");
        req.setDescription("说明");
        ObjectNode config = json.createObjectNode();
        config.put("max_parallel", 1);
        req.setDefaultConfig(config);
        req.setCases(caseSpecs(caseCount));
        return req;
    }

    private EvalSuiteUpdateReq updateReq(String name) {
        EvalSuiteUpdateReq req = new EvalSuiteUpdateReq();
        req.setName(name);
        return req;
    }

    private EvalSuiteSpec spec(String suiteKey, int caseCount) {
        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setSuiteKey(suiteKey);
        spec.setName("导入版本");
        spec.setCases(caseSpecs(caseCount));
        return spec;
    }

    private List<EvalCaseSpec> caseSpecs(int count) {
        List<EvalCaseSpec> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            EvalCaseSpec one = new EvalCaseSpec();
            one.setCaseKey("c" + i);
            one.setTitle("原标题 c" + i);
            one.setInput(json.createObjectNode().put("message", "问题 " + i));
            one.setSortOrder(i * 10);
            list.add(one);
        }
        return list;
    }
}
