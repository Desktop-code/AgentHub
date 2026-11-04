package com.agenthub.eval.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.eval.api.dto.EvalResultPageReq;
import com.agenthub.eval.api.dto.EvalResultResp;
import com.agenthub.eval.api.dto.EvalRunCreateReq;
import com.agenthub.eval.api.dto.EvalRunPageReq;
import com.agenthub.eval.api.dto.EvalRunResp;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalVerdict;
import com.agenthub.eval.domain.repository.EvalCaseRepository;
import com.agenthub.eval.domain.repository.EvalResultRepository;
import com.agenthub.eval.domain.repository.EvalRunRepository;
import com.agenthub.eval.domain.repository.EvalSuiteRepository;
import com.agenthub.eval.domain.service.EvalRunConfig;
import com.agenthub.eval.domain.service.EvalRunStateMachine;
import com.agenthub.eval.domain.service.EvalSpecs;
import com.agenthub.eval.domain.service.EvalTenantProvider;
import com.agenthub.eval.infra.EvalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测跑批用例。所属域：eval 的 application。
 * <p>本类负责跑批壳：建跑（PENDING + stubs）、查、结果分页、取消收尾。
 * 真执行在 {@link EvalRunnerAppService}；取消 RUNNING 时经 Runner 级联 cancel 现网 trial。
 * <p>租户口径：读/写一律本租户；跨租户按 404 处理，与刀1 一致（不暴露 id 是否存在）。
 * <p>状态口径：只有 {@link EvalRunStateMachine} 能改状态与计数器，本类只负责取数、调用、落库。
 */
@Slf4j
@Service
public class EvalRunAppService {

    private final EvalSuiteRepository suites;
    private final EvalCaseRepository cases;
    private final EvalRunRepository runs;
    private final EvalResultRepository results;
    private final EvalAssembler assembler;
    private final EvalTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final ObjectMapper json;
    private final EvalRunnerAppService runner;
    private final EvalProperties properties;

    /**
     * @param suites          套件仓储（校验套件归属与启用）
     * @param cases           用例仓储（取该版本的启用用例）
     * @param runs            跑批仓储
     * @param results         结果仓储
     * @param assembler       出参装配
     * @param tenantProvider  当前租户
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志（只记「建跑/取消」与计数，不写用例正文）
     * @param json            JSON 序列化器
     * @param runner          Runner（cancel 级联 in-flight trial；execute 不经本类）
     * @param properties      全局缺省（openjudge/report 基线）
     */
    public EvalRunAppService(EvalSuiteRepository suites, EvalCaseRepository cases, EvalRunRepository runs,
                             EvalResultRepository results, EvalAssembler assembler,
                             EvalTenantProvider tenantProvider, OperatorContext operatorContext,
                             OperLogWriter operLogWriter, ObjectMapper json, EvalRunnerAppService runner,
                             EvalProperties properties) {
        this.suites = suites;
        this.cases = cases;
        this.runs = runs;
        this.results = results;
        this.assembler = assembler;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.json = json;
        this.runner = runner;
        this.properties = properties;
    }

    /**
     * 建跑：落一条 PENDING 的 eval_run + N 条占位 eval_result，同一事务。
     * <p>边界（任一不满足即 4xx，**不落半截 run**）：
     * <ul>
     *   <li>套件不存在 / 跨租户 → 404；</li>
     *   <li>套件被停用 → 400；平台只读样例可跑（它是用例来源，不是租户数据修改）；</li>
     *   <li>指定版本超出 [1, current_version] → 400；</li>
     *   <li>该版本没有启用用例 → 400「无可跑用例」（N=0 不建空 run，避免空跑刷出无意义的绿）；</li>
     *   <li>并发插入撞 result 唯一键 → 事务回滚，不残留 run。</li>
     * </ul>
     *
     * @param req 建跑请求
     * @return 新跑批的只读出参（status=PENDING）
     * @throws BizException 上述边界或写库失败
     */
    @Transactional
    public EvalRunResp createRun(EvalRunCreateReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "建跑请求不能为空");
        }
        long tenantId = tenantProvider.currentTenantId();
        EvalSuite suite = requireRunnableSuite(req.getSuiteId(), tenantId);
        int version = resolveVersion(suite, req.getSuiteVersion());

        List<EvalCase> enabledCases = cases.listByVersion(suite.getId(), version).stream()
                .filter(one -> one.getEnabled() == null || one.getEnabled() == EvalSuite.ENABLED)
                .toList();
        // N=0 明确拒绝：空跑既验证不了任何东西，又会在列表里堆一堆「成功 0/0」的假绿记录
        if (enabledCases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "套件版本 " + version + " 没有可跑用例，请先导入启用的用例");
        }
        if (enabledCases.size() > EvalSpecs.CASES_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单个套件最多 " + EvalSpecs.CASES_MAX + " 条用例");
        }

        // 三级覆盖：全局 YAML 基线 → suite default_config → 本次 override
        JsonNode mergedConfig = EvalRunConfig.merge(
                EvalRunConfig.merge(globalConfigBaseline(), assembler.readNode(suite.getDefaultConfigJson())),
                req.getConfigOverride());
        Long operatorId = operatorContext.requireUserId();

        EvalRun run = new EvalRun();
        run.setTenantId(tenantId);
        run.setSuiteId(suite.getId());
        run.setSuiteVersion(version);
        run.setAgentId(req.getAgentId());
        // 真指纹要装配完 runtime/模型槽才算得出，属刀2b；本刀留空并在出参里注明
        run.setAgentSnapshotHash(null);
        run.setStatus(EvalRunStatus.PENDING);
        run.setGatePassed(null);
        run.setTotalCases(enabledCases.size());
        run.setOpenjudgeEnabled(EvalRunConfig.openjudgeEnabled(mergedConfig) ? 1 : 0);
        run.setConfigJson(EvalSpecs.writeJson(json, mergedConfig));
        run.setTriggerUserId(operatorId);
        Long runId = runs.insert(run, operatorId);

        results.insertBatch(toPlaceholderRows(runId, tenantId, enabledCases), operatorId);

        operLogWriter.write("eval", "createRun", "eval_run", String.valueOf(runId),
                "建评测跑批：套件 " + suite.getSuiteKey() + " v" + version
                        + "，Agent " + req.getAgentId() + "，计划 " + enabledCases.size() + " 条");
        log.info("评测跑批已建单 run={} suite={} v{} cases={}", runId, suite.getSuiteKey(), version,
                enabledCases.size());
        return getRun(runId);
    }

    /**
     * 查单条跑批（租户隔离）。
     *
     * @param id 主键
     * @return 出参
     * @throws BizException 不存在或跨租户（统一 404）
     */
    public EvalRunResp getRun(Long id) {
        return assembler.toRunResp(requireReadableRun(id));
    }

    /**
     * 本租户跑批分页；新在前。
     *
     * @param req 筛选
     * @return 分页
     */
    public PageResult<EvalRunResp> pageRuns(EvalRunPageReq req) {
        long tenantId = tenantProvider.currentTenantId();
        long current = req == null ? 1 : req.getCurrent();
        long size = req == null ? 20 : req.getSize();
        Long suiteId = req == null ? null : req.getSuiteId();
        Long agentId = req == null ? null : req.getAgentId();
        String status = req == null ? null : req.getStatus();
        PageResult<EvalRun> page = runs.page(tenantId, suiteId, agentId, status, current, size);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(assembler::toRunResp).toList());
    }

    /**
     * 分页列出某跑批的结果行，按 sort_order 升序；占位行的 verdict 为 null。
     *
     * @param id  跑批主键
     * @param req 分页参数
     * @return 结果页
     * @throws BizException 跑批不存在或跨租户（404）
     */
    public PageResult<EvalResultResp> listResults(Long id, EvalResultPageReq req) {
        EvalRun run = requireReadableRun(id);
        long current = req == null ? 1 : req.getCurrent();
        long size = req == null ? 20 : req.getSize();
        PageResult<EvalResult> page = results.pageByRun(run.getId(), run.getTenantId(), current, size);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(assembler::toResultResp).toList());
    }

    /**
     * 取消跑批。
     * <p>本刀没有 Trial 可级联取消，因此直接同步收尾（不必真异步）：
     * 未出判定的结果行全部置 SKIPPED，再按「已有 PASS/FAIL ⇒ PARTIAL，否则 FAILED」落到终态。
     * <p>幂等/冲突口径：
     * <ul>
     *   <li>PENDING / RUNNING → 正常受理；</li>
     *   <li>CANCELING → 幂等收尾（上次收尾未落终态时可重试）；</li>
     *   <li>已终态 → 明确冲突错（400），不静默成功。</li>
     * </ul>
     *
     * @param id 跑批主键
     * @return 收尾后的跑批出参
     * @throws BizException 不存在 / 跨租户（404）、已终态（400）
     */
    @Transactional
    public EvalRunResp cancel(Long id) {
        EvalRun run = requireWritableRun(id);
        if (run.isTerminal()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测跑批已结束（" + run.getStatus().getLabel() + "），无需取消");
        }
        Long operatorId = operatorContext.requireUserId();
        long tenantId = run.getTenantId();

        // RUNNING 时先级联取消 in-flight trial（经 TrialEvalPort → 现网 cancel 门面）
        if (run.getStatus() == EvalRunStatus.RUNNING || run.getStatus() == EvalRunStatus.CANCELING) {
            runner.cancelInFlightTrial(run.getId());
        }

        // 状态机负责迁到 CANCELING → FAILED/PARTIAL；本类只负责把库里的未完成行先打成 SKIPPED，
        // 再取全量结果交给状态机重算计数器（两边口径一致，不各自算数）
        results.markUnfinished(run.getId(), tenantId, EvalVerdict.SKIPPED, operatorId);
        List<EvalResult> all = results.listByRun(run.getId());
        EvalRunStateMachine.applyCancelFinish(run, all);
        if (runs.updateState(run, operatorId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在");
        }
        // 取消也出「不完整报告」；写文件失败不阻断收尾（Runner 内再 updateState 覆盖 report 字段）
        try {
            runner.attachReport(run, all, operatorId);
        } catch (RuntimeException ex) {
            log.warn("取消收尾写报告失败 run={}：{}", id, ex.toString());
        }

        operLogWriter.write("eval", "cancelRun", "eval_run", String.valueOf(id),
                "取消评测跑批，收尾为 " + run.getStatus().name()
                        + "（通过 " + run.getPassed() + "，失败 " + run.getFailed()
                        + "，跳过 " + run.getSkipped() + "）");
        log.info("评测跑批已取消 run={} status={} passed={} failed={} skipped={}", id, run.getStatus(),
                run.getPassed(), run.getFailed(), run.getSkipped());
        return getRun(id);
    }

    /**
     * 读取报告。未生成 → 404。
     * <p>{@code format=md} 优先返回已落盘 Markdown 文本；否则由 report_json 现场渲染简版。
     *
     * @param id     跑批
     * @param format json（默认）或 md
     * @return JSON 节点或 Markdown 字符串
     * @throws BizException 不存在 / 跨租户 / 报告未生成
     */
    public Object getReport(Long id, String format) {
        EvalRun run = requireReadableRun(id);
        if (run.getReportJson() == null || run.getReportJson().isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成");
        }
        boolean wantMd = format != null && "md".equalsIgnoreCase(format.trim());
        if (!wantMd) {
            JsonNode node = assembler.readNode(run.getReportJson());
            return node == null ? json.createObjectNode() : node;
        }
        if (run.getReportPath() != null && !run.getReportPath().isBlank()) {
            try {
                Path path = Path.of(run.getReportPath());
                if (Files.isRegularFile(path)) {
                    return Files.readString(path, StandardCharsets.UTF_8);
                }
            } catch (Exception ex) {
                log.warn("读取报告 MD 失败 run={} path={}：{}", id, run.getReportPath(), ex.toString());
            }
        }
        // 无落盘文件时用机读字段拼简版 MD，避免 404
        return "# 评测报告\n\n（无落盘 Markdown；以下为 report_json 摘要）\n\n```json\n"
                + run.getReportJson() + "\n```\n";
    }

    /**
     * 把 YAML 全局 openjudge/report 缺省写成可合并的 JSON 基线。
     */
    private ObjectNode globalConfigBaseline() {
        ObjectNode root = json.createObjectNode();
        ObjectNode openjudge = root.putObject("openjudge");
        openjudge.put("enabled", properties.getOpenjudge().isEnabled());
        openjudge.put("required", properties.getOpenjudge().isRequired());
        openjudge.put("base_url", properties.getOpenjudge().getBaseUrl());
        openjudge.put("timeout_ms", properties.getOpenjudge().getTimeoutMs());
        ArrayNode graders = openjudge.putArray("default_graders");
        if (properties.getOpenjudge().getDefaultGraders() != null) {
            for (String one : properties.getOpenjudge().getDefaultGraders()) {
                if (one != null && !one.isBlank()) {
                    graders.add(one.trim());
                }
            }
        }
        ObjectNode report = root.putObject("report");
        report.put("write_workspace", properties.getReport().isWriteWorkspace());
        report.put("markdown", properties.getReport().isMarkdown());
        return root;
    }

    // ------------------------------------------------------------------
    // 以下为内部校验与装配
    // ------------------------------------------------------------------

    /**
     * 建跑前的套件校验：必须存在、属本租户（平台样例允许作为用例来源）、且启用。
     *
     * @param suiteId  套件
     * @param tenantId 当前租户
     * @return 可跑套件
     * @throws BizException 404 或 400
     */
    private EvalSuite requireRunnableSuite(Long suiteId, long tenantId) {
        if (suiteId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "suite_id 不能为空");
        }
        EvalSuite suite = suites.findById(suiteId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测套件不存在"));
        // 跨租户与不存在给同一个 404：不暴露其它租户的 id 是否存在
        if (!suite.isPlatformOwned() && !suite.getTenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测套件不存在");
        }
        if (suite.getEnabled() != null && suite.getEnabled() != EvalSuite.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件已停用，不能建跑");
        }
        return suite;
    }

    /**
     * 解析实际跑批版本：缺省取 current_version；显式给出则必须落在 [1, current_version]。
     *
     * @param suite   套件
     * @param requested 请求版本，可空
     * @return 钉死版本
     * @throws BizException 版本超范围
     */
    private int resolveVersion(EvalSuite suite, Integer requested) {
        int current = suite.getCurrentVersion() == null || suite.getCurrentVersion() < 1
                ? 1 : suite.getCurrentVersion();
        if (requested == null) {
            return current;
        }
        if (requested < 1 || requested > current) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "套件版本只能是 1～" + current + "，当前 " + current);
        }
        return requested;
    }

    /**
     * 启用用例 → 占位结果行。除定位字段外全部留空，等后续刀回填。
     *
     * @param runId   跑批
     * @param tenantId 租户
     * @param enabledCases 启用用例
     * @return 结果行
     */
    private List<EvalResult> toPlaceholderRows(Long runId, long tenantId, List<EvalCase> enabledCases) {
        List<EvalResult> rows = new ArrayList<>();
        int index = 0;
        for (EvalCase one : enabledCases) {
            EvalResult row = new EvalResult();
            row.setTenantId(tenantId);
            row.setRunId(runId);
            row.setCaseKey(one.getCaseKey());
            row.setCaseId(one.getId());
            // 执行序沿用用例的 sort_order；用例未填时按数组下标兜底，保证结果页顺序稳定
            row.setSortOrder(one.getSortOrder() == null ? index * 10 : one.getSortOrder());
            row.setVerdict(null);
            rows.add(row);
            index++;
        }
        return rows;
    }

    /**
     * 读校验：必须存在且属本租户。
     *
     * @param id 主键
     * @return 可读跑批
     * @throws BizException 404
     */
    private EvalRun requireReadableRun(Long id) {
        EvalRun run = runs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在"));
        if (!run.getTenantId().equals(tenantProvider.currentTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测跑批不存在");
        }
        return run;
    }

    /**
     * 写校验：本刀与读校验同口径（跑批没有「平台只读」语义）。
     *
     * @param id 主键
     * @return 可写跑批
     * @throws BizException 404
     */
    private EvalRun requireWritableRun(Long id) {
        return requireReadableRun(id);
    }
}
