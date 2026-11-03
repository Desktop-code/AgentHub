package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalCaseResp;
import com.agenthub.eval.api.dto.EvalImportResp;
import com.agenthub.eval.api.dto.EvalSuiteCreateReq;
import com.agenthub.eval.api.dto.EvalSuiteDetailResp;
import com.agenthub.eval.api.dto.EvalSuitePageReq;
import com.agenthub.eval.api.dto.EvalSuiteResp;
import com.agenthub.eval.api.dto.EvalSuiteUpdateReq;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalSuiteQuery;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.agenthub.eval.domain.repository.EvalCaseRepository;
import com.agenthub.eval.domain.repository.EvalSuiteRepository;
import com.agenthub.eval.domain.service.EvalSpecs;
import com.agenthub.eval.domain.service.EvalTenantProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用例库（Case Store）用例。所属域：eval 的 application。
 * <p>本刀只做 suite / case 的存储与版本管理，**不**执行评测：
 * 没有 EvalRunner、没有 Trial 调用、没有 RuleScorer 执行、没有 OpenJudge、没有 SSE。
 * <p>版本规则（唯一不可协商的语义）：
 * <ul>
 *   <li>suite_version 不可变；任何 cases 内容变更都写**新版本行**，历史版本原样保留。</li>
 *   <li>一次导入只产生一个新版本号，且先 CAS 抢号再写用例，失败整事务回滚，不落半截版本。</li>
 * </ul>
 * <p>租户规则：读要同租户（或 tenant_id=0 只读样例）；写只允许本租户，平台样例一律拒绝。
 * 跨租户按 404 处理，不区分「不存在」与「没权限」，避免探测。
 */
@Slf4j
@Service
public class CaseStoreAppService {

    private final EvalSuiteRepository suites;
    private final EvalCaseRepository cases;
    private final EvalAssembler assembler;
    private final EvalSampleCatalog samples;
    private final EvalTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final ObjectMapper json;

    /**
     * @param suites          套件仓储
     * @param cases           用例仓储
     * @param assembler       出参装配
     * @param samples         classpath 平台样例
     * @param tenantProvider  当前租户
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志（只记名称与动作，不写用例正文）
     * @param json            JSON 序列化器
     */
    public CaseStoreAppService(EvalSuiteRepository suites, EvalCaseRepository cases, EvalAssembler assembler,
                               EvalSampleCatalog samples, EvalTenantProvider tenantProvider,
                               OperatorContext operatorContext, OperLogWriter operLogWriter, ObjectMapper json) {
        this.suites = suites;
        this.cases = cases;
        this.assembler = assembler;
        this.samples = samples;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.json = json;
    }

    /**
     * 分页列出套件。
     *
     * @param req 分页与筛选；includePlatform 默认 true
     * @return 套件页，不含用例
     */
    public PageResult<EvalSuiteResp> page(EvalSuitePageReq req) {
        EvalSuiteQuery query = new EvalSuiteQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setTenantId(tenantProvider.currentTenantId());
        query.setIncludePlatform(!Boolean.FALSE.equals(req.getIncludePlatform()));
        PageResult<EvalSuite> page = suites.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(assembler::toResp).toList());
    }

    /**
     * 套件详情，含当前版本用例。
     *
     * @param id 主键
     * @return 元数据 + 当前版本用例
     * @throws BizException 不存在或不属于当前租户（404）
     */
    public EvalSuiteDetailResp get(Long id) {
        EvalSuite suite = requireReadable(id);
        return assembler.toDetail(suite, cases.listByVersion(suite.getId(), versionOf(suite)));
    }

    /**
     * 列出指定版本的用例。历史版本可读，供报告与复现。
     *
     * @param id           套件主键
     * @param suiteVersion 版本；空则取当前版本
     * @return 用例列表
     * @throws BizException 套件不可读，或版本号非法/超前
     */
    public List<EvalCaseResp> listCases(Long id, Integer suiteVersion) {
        EvalSuite suite = requireReadable(id);
        int version = versionOf(suite);
        if (suiteVersion != null) {
            if (suiteVersion < 1 || suiteVersion > version) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "套件版本只能是 1～" + version + "，当前 " + version);
            }
            version = suiteVersion;
        }
        return cases.listByVersion(suite.getId(), version).stream().map(assembler::toCaseResp).toList();
    }

    /**
     * 新建套件。可一次带全 cases（作为版本 1），也可先建空壳。
     *
     * @param req 新套件
     * @return 详情
     * @throws BizException key 非法/重复、用例结构不合法
     */
    @Transactional
    public EvalSuiteDetailResp create(EvalSuiteCreateReq req) {
        long tenantId = tenantProvider.currentTenantId();
        String suiteKey = EvalSpecs.checkSuiteKeyPattern(
                EvalSpecs.requiredText(req.getSuiteKey(), EvalSpecs.KEY_MAX, "suite_key"));

        // 物理 UK (tenant_id, suite_key) 对软删行同样生效，故按「含软删」统计，给出可读冲突提示
        if (suites.countByTenantAndKeyIncludingDeleted(tenantId, suiteKey) > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "套件标识已存在：" + suiteKey);
        }

        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setSuiteKey(suiteKey);
        spec.setName(req.getName());
        spec.setDescription(req.getDescription());
        spec.setDefaultConfig(req.getDefaultConfig());
        List<EvalCaseSpec> caseSpecs = req.getCases() == null ? new ArrayList<>() : new ArrayList<>(req.getCases());
        spec.setCases(caseSpecs);
        // 与导入共用同一套校验：key 唯一、input.message 必填、断言结构合法
        EvalSpecs.normalize(spec);

        EvalSuite suite = new EvalSuite();
        suite.setTenantId(tenantId);
        suite.setSuiteKey(suiteKey);
        suite.setName(spec.getName());
        suite.setDescription(spec.getDescription());
        suite.setCurrentVersion(1);
        suite.setDefaultConfigJson(writeDefaultConfig(spec.getDefaultConfig()));
        // 新建未传启停时默认启用；传了就必须是 1/0
        suite.setEnabled(req.getEnabled() == null ? EvalSuite.ENABLED : exactEnabled(req.getEnabled()));
        Long id = suites.insert(suite, operatorContext.requireUserId());

        writeCases(id, 1, spec, operatorContext.requireUserId());
        operLogWriter.write("eval", "create", "eval_suite", String.valueOf(id),
                "新建评测套件 " + suite.getName());
        return get(id);
    }

    /**
     * 更新套件元数据。**不动 cases**：内容变更必须走导入或单用例写接口，那里才升版本。
     *
     * @param id  主键
     * @param req 新元数据
     * @return 更新后的详情
     * @throws BizException 不存在/跨租户（404）、平台样例（403）、重复 key
     */
    @Transactional
    public EvalSuiteDetailResp update(Long id, EvalSuiteUpdateReq req) {
        EvalSuite suite = requireWritable(id);
        suite.setName(EvalSpecs.requiredText(req.getName(), EvalSpecs.NAME_MAX, "名称"));
        suite.setDescription(EvalSpecs.optionalText(req.getDescription(), EvalSpecs.DESCRIPTION_MAX, "描述"));
        suite.setDefaultConfigJson(writeDefaultConfig(req.getDefaultConfig()));
        if (req.getEnabled() != null) {
            suite.setEnabled(exactEnabled(req.getEnabled()));
        }
        suites.updateMeta(suite, operatorContext.requireUserId());
        operLogWriter.write("eval", "update", "eval_suite", String.valueOf(id),
                "更新评测套件 " + suite.getName());
        return get(id);
    }

    /**
     * 导入完整套件 JSON：写入**新版本**，旧版本用例行原样保留，随后推进 current_version。
     *
     * <p>幂等口径：一次请求只产生一个新版本；同内容重复导入**依然会** +1（可复现的显式动作），
     * 想避免重复请调用方比对 current_version。
     *
     * @param id   套件主键
     * @param spec 已解析的 JSON（来自请求体）
     * @return 新旧版本号与新版本用例数
     * @throws BizException 套件不可写、cases 为空、结构非法、版本被并发变更
     */
    @Transactional
    public EvalImportResp importSuite(Long id, EvalSuiteSpec spec) {
        EvalSuite suite = requireWritable(id);
        EvalSpecs.normalize(spec);
        requireNonEmptyCases(spec, "导入");
        // 请求体若自带 suite_key，必须与目标套件一致：不一致多半是贴错了文件，
        // 静默忽略会让用户以为导入到了另一个套件
        if (spec.getSuiteKey() != null && !suite.getSuiteKey().equals(spec.getSuiteKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "suite_key 与目标套件不一致：请求 " + spec.getSuiteKey() + "，目标 " + suite.getSuiteKey());
        }
        return importIntoExisting(suite, spec);
    }

    /**
     * 把 classpath 平台样例装进当前租户。
     * <p>首次导入新建套件（版本 1）；同名 suite_key 已存在则按导入语义升版本。
     * <p>与 importSuite 的区别：这里跳过「suite_key 必须与目标一致」的校验，
     * 因为样例自带 key，正是用它去定位/创建目标套件。
     *
     * @return 导入结果
     * @throws BizException 样例资源缺失/损坏，或写入失败
     */
    @Transactional
    public EvalImportResp importPlatformSample() {
        long tenantId = tenantProvider.currentTenantId();
        // 只读 classpath JSON；缺文件/坏 JSON 由 catalog 抛 BizException，禁止代码内兜底造 case
        EvalSuiteSpec spec = samples.requirePlatformSample();
        EvalSpecs.normalize(spec);
        String suiteKey = EvalSpecs.checkSuiteKeyPattern(
                EvalSpecs.requiredText(spec.getSuiteKey(), EvalSpecs.KEY_MAX, "suite_key"));

        Optional<EvalSuite> existing = suites.findByTenantAndKey(tenantId, suiteKey);
        if (existing.isPresent()) {
            return importIntoExisting(existing.get(), spec);
        }
        if (suites.countByTenantAndKeyIncludingDeleted(tenantId, suiteKey) > 0) {
            // 键位被已删除的同名套件占着：物理 UK 不释放，只能让用户换 key 或人工清理
            throw new BizException(ErrorCode.DUPLICATE,
                    "套件标识 " + suiteKey + " 已被已删除记录占用，请换 suite_key 后重试");
        }
        EvalSuite suite = new EvalSuite();
        suite.setTenantId(tenantId);
        suite.setSuiteKey(suiteKey);
        suite.setName(EvalSpecs.requiredText(spec.getName(), EvalSpecs.NAME_MAX, "名称"));
        suite.setDescription(spec.getDescription());
        suite.setCurrentVersion(1);
        suite.setDefaultConfigJson(writeDefaultConfig(spec.getDefaultConfig()));
        suite.setEnabled(EvalSuite.ENABLED);
        Long id = suites.insert(suite, operatorContext.requireUserId());
        writeCases(id, 1, spec, operatorContext.requireUserId());

        operLogWriter.write("eval", "importSample", "eval_suite", String.valueOf(id),
                "从平台样例导入评测套件 " + suite.getName());
        EvalImportResp resp = new EvalImportResp();
        resp.setSuiteId(id);
        resp.setSuiteKey(suiteKey);
        resp.setPreviousVersion(null);
        resp.setCurrentVersion(1);
        resp.setCaseCount(spec.getCases().size());
        return resp;
    }

    /**
     * 向已有套件写入一个新版本（导入与样例导入共用）。
     * <p>先 CAS 抢版本号再写用例行：抢不到说明有人并发导入，直接提示重试，
     * 避免两条请求算出同一个新版本号后在唯一键上互相踩。
     *
     * @param suite 已通过可写校验的套件
     * @param spec  已归一化的新版本内容
     * @return 新旧版本号与新版本用例数
     * @throws BizException 版本被并发变更
     */
    private EvalImportResp importIntoExisting(EvalSuite suite, EvalSuiteSpec spec) {
        requireNonEmptyCases(spec, "导入");
        Long operatorId = operatorContext.requireUserId();
        int previous = versionOf(suite);
        int next = previous + 1;
        int bumped = suites.bumpVersion(suite.getId(), tenantProvider.currentTenantId(), previous, next, operatorId);
        if (bumped == 0) {
            throw new BizException(ErrorCode.DUPLICATE, "套件版本已被其它操作更新，请刷新后重试");
        }
        writeCases(suite.getId(), next, spec, operatorId);

        operLogWriter.write("eval", "import", "eval_suite", String.valueOf(suite.getId()),
                "导入评测套件 " + suite.getName() + " 至版本 " + next);
        EvalImportResp resp = new EvalImportResp();
        resp.setSuiteId(suite.getId());
        resp.setSuiteKey(suite.getSuiteKey());
        resp.setPreviousVersion(previous);
        resp.setCurrentVersion(next);
        resp.setCaseCount(spec.getCases().size());
        return resp;
    }

    /**
     * 写单条用例（新增或替换），**同样升版本**：把当前版本整份复制到新版本，仅覆盖该 case_key。
     * <p>为什么复制而不是原地改：历史版本可能已被某次 run 引用，原地改会让报告无法复现。
     *
     * @param id      套件主键
     * @param spec    单条用例（case_key 必填）
     * @return 新版本号与该版本的用例总数
     * @throws BizException 套件不可写、用例结构非法、版本并发变更
     */
    @Transactional
    public EvalImportResp upsertCase(Long id, EvalCaseSpec spec) {
        EvalSuite suite = requireWritable(id);
        if (spec == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例不能为空");
        }
        EvalSpecs.normalizeCase(spec);

        int previous = versionOf(suite);
        List<EvalCase> base = cases.listByVersion(id, previous);
        int next = previous + 1;
        int bumped = suites.bumpVersion(id, tenantProvider.currentTenantId(), previous, next,
                operatorContext.requireUserId());
        if (bumped == 0) {
            throw new BizException(ErrorCode.DUPLICATE, "套件版本已被其它操作更新，请刷新后重试");
        }

        // 复制旧版本 → 新版本；命中 case_key 的做替换，其余原样带过去
        Long operatorId = operatorContext.requireUserId();
        List<EvalCase> rows = new ArrayList<>();
        boolean replaced = false;
        for (EvalCase old : base) {
            if (old.getCaseKey().equals(spec.getCaseKey())) {
                rows.add(toRow(id, next, spec, operatorId));
                replaced = true;
            } else {
                rows.add(copyRow(old, id, next));
            }
        }
        if (!replaced) {
            rows.add(toRow(id, next, spec, operatorId));
        }
        if (rows.size() > EvalSpecs.CASES_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单个套件最多 " + EvalSpecs.CASES_MAX + " 条用例");
        }
        cases.insertBatch(rows, operatorId);

        operLogWriter.write("eval", replaced ? "updateCase" : "addCase", "eval_case", spec.getCaseKey(),
                "套件 " + suite.getSuiteKey() + " 写用例 " + spec.getCaseKey() + " 至版本 " + next);
        EvalImportResp resp = new EvalImportResp();
        resp.setSuiteId(id);
        resp.setSuiteKey(suite.getSuiteKey());
        resp.setPreviousVersion(previous);
        resp.setCurrentVersion(next);
        resp.setCaseCount(rows.size());
        return resp;
    }

    /**
     * 删除单条用例：复制当前版本到新版本但剔除该 case_key（同样是升版本语义）。
     *
     * @param id      套件主键
     * @param caseKey 用例键
     * @return 新版本号与该版本剩余用例数
     * @throws BizException 套件不可写、用例不存在、版本并发变更、删空被拒
     */
    @Transactional
    public EvalImportResp deleteCase(Long id, String caseKey) {
        EvalSuite suite = requireWritable(id);
        int previous = versionOf(suite);
        List<EvalCase> base = cases.listByVersion(id, previous);
        List<EvalCase> kept = base.stream().filter(one -> !one.getCaseKey().equals(caseKey)).toList();
        if (kept.size() == base.size()) {
            throw new BizException(ErrorCode.NOT_FOUND, "用例不存在：" + caseKey);
        }
        // 允许删到 0 条会让套件变得「可跑但空跑」，宁可让用户先导入替代内容再删
        if (kept.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能删除该版本的最后一条用例；请先导入替代用例");
        }
        int next = previous + 1;
        int bumped = suites.bumpVersion(id, tenantProvider.currentTenantId(), previous, next,
                operatorContext.requireUserId());
        if (bumped == 0) {
            throw new BizException(ErrorCode.DUPLICATE, "套件版本已被其它操作更新，请刷新后重试");
        }
        List<EvalCase> rows = kept.stream().map(one -> copyRow(one, id, next)).toList();
        cases.insertBatch(rows, operatorContext.requireUserId());

        operLogWriter.write("eval", "removeCase", "eval_case", caseKey,
                "套件 " + suite.getSuiteKey() + " 删除用例 " + caseKey + "，新版本 " + next);
        EvalImportResp resp = new EvalImportResp();
        resp.setSuiteId(id);
        resp.setSuiteKey(suite.getSuiteKey());
        resp.setPreviousVersion(previous);
        resp.setCurrentVersion(next);
        resp.setCaseCount(rows.size());
        return resp;
    }

    /**
     * 逻辑删除套件（连同其全部版本用例墓碑化）。历史行保留，便于事后排查。
     *
     * @param id 主键
     * @throws BizException 不存在/跨租户（404）、平台样例（403）
     */
    @Transactional
    public void remove(Long id) {
        EvalSuite suite = requireWritable(id);
        Long operatorId = operatorContext.requireUserId();
        int touched = suites.logicalDelete(id, tenantProvider.currentTenantId(), operatorId);
        if (touched == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测套件不存在");
        }
        cases.logicalDeleteBySuite(id, operatorId);
        operLogWriter.write("eval", "remove", "eval_suite", String.valueOf(id),
                "删除评测套件 " + suite.getName());
    }

    /**
     * 读校验：本租户或平台只读样例；其它租户一律当不存在。
     *
     * @param id 主键
     * @return 可读套件
     * @throws BizException 404
     */
    private EvalSuite requireReadable(Long id) {
        EvalSuite suite = suites.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测套件不存在"));
        long tenantId = tenantProvider.currentTenantId();
        // 跨租户与不存在给同一个 404：不暴露其它租户的 id 是否存在
        if (!suite.isPlatformOwned() && !suite.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测套件不存在");
        }
        return suite;
    }

    /**
     * 写校验：只允许本租户；平台内置样例只读。
     *
     * @param id 主键
     * @return 可写套件
     * @throws BizException 404 或 403
     */
    private EvalSuite requireWritable(Long id) {
        EvalSuite suite = requireReadable(id);
        if (suite.isPlatformOwned()) {
            throw new BizException(ErrorCode.FORBIDDEN, "平台内置样例只读，请先导入到本租户后再修改");
        }
        return suite;
    }

    /**
     * 当前版本号；库内异常为 null 时按 1 兜底（表默认值即 1）。
     */
    private int versionOf(EvalSuite suite) {
        return suite.getCurrentVersion() == null || suite.getCurrentVersion() < 1
                ? 1 : suite.getCurrentVersion();
    }

    /**
     * 启停取值：只接受 1/0。
     */
    private int exactEnabled(Integer raw) {
        if (raw == null || (raw != EvalSuite.ENABLED && raw != EvalSuite.DISABLED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "启用状态只能是 1 或 0");
        }
        return raw;
    }

    /**
     * 导入语义要求至少一条用例：空导入会得到一个「可跑但没有任何 case」的新版本。
     */
    private void requireNonEmptyCases(EvalSuiteSpec spec, String action) {
        if (spec.getCases() == null || spec.getCases().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, action + "的 cases 不能为空；如需停用请改 enabled");
        }
    }

    /**
     * 把 spec 的用例写到指定版本。
     */
    private void writeCases(Long suiteId, int version, EvalSuiteSpec spec, Long operatorId) {
        List<EvalCase> rows = EvalSpecs.toCaseRows(suiteId, version, spec, operatorId, json);
        if (!rows.isEmpty()) {
            cases.insertBatch(rows, operatorId);
        }
    }

    /**
     * 单条 spec → 待入库行（复用批量转换，保证与导入路径的字段映射完全一致）。
     */
    private EvalCase toRow(Long suiteId, int version, EvalCaseSpec spec, Long operatorId) {
        EvalSuiteSpec wrapper = new EvalSuiteSpec();
        wrapper.setCases(List.of(spec));
        return EvalSpecs.toCaseRows(suiteId, version, wrapper, operatorId, json).get(0);
    }

    /**
     * 旧版本行 → 新版本行（内容不变，只换版本号并清空主键/审计）。
     */
    private EvalCase copyRow(EvalCase old, Long suiteId, int version) {
        EvalCase row = new EvalCase();
        row.setSuiteId(suiteId);
        row.setSuiteVersion(version);
        row.setCaseKey(old.getCaseKey());
        row.setTitle(old.getTitle());
        row.setTagsJson(old.getTagsJson());
        row.setInputJson(old.getInputJson());
        row.setExpectJson(old.getExpectJson());
        row.setAssertionsJson(old.getAssertionsJson());
        row.setScoringJson(old.getScoringJson());
        row.setEnabled(old.getEnabled());
        row.setSortOrder(old.getSortOrder());
        return row;
    }

    /**
     * 默认配置 JSON 文本；空节点写 null（不写空串，便于判空统一）。
     */
    private String writeDefaultConfig(JsonNode node) {
        return EvalSpecs.writeJson(json, node);
    }
}
