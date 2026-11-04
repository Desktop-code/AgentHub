package com.agenthub.workflow.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowDefCreateReq;
import com.agenthub.workflow.api.dto.WorkflowDefPageReq;
import com.agenthub.workflow.api.dto.WorkflowDefResp;
import com.agenthub.workflow.api.dto.WorkflowDefUpdateReq;
import com.agenthub.workflow.api.dto.WorkflowDraftResp;
import com.agenthub.workflow.api.dto.WorkflowIssueResp;
import com.agenthub.workflow.api.dto.WorkflowPublishReq;
import com.agenthub.workflow.api.dto.WorkflowReleaseResp;
import com.agenthub.workflow.api.dto.WorkflowValidateResp;
import com.agenthub.workflow.domain.WorkflowConflictException;
import com.agenthub.workflow.domain.enums.WorkflowDefStatus;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowDefQuery;
import com.agenthub.workflow.domain.model.WorkflowDraft;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowDraftRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowFingerprint;
import com.agenthub.workflow.domain.service.WorkflowGraphValidator;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义 / 草稿 / 校验 / 发布用例。所属域：workflow 的 application。
 * <p>边界：本刀只读写 workflow_def / workflow_def_draft / workflow_release；
 * 不落 run / node_run / human_task，不调模型、不调试跑、不写 obs TASK。
 * <p>校验口径：草稿保存只做 JSON 结构校验（可存未通过业务校验的图）；
 * Validate 与 Publish 都按「发布严格模式」执行，发布必须 0 error。
 */
@Service
public class WorkflowDefAppService {

    /** 入参时间兼容带/不带毫秒，与全站 Jackson 写出口径一致 */
    private static final DateTimeFormatter INPUT_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private final WorkflowDefRepository defs;
    private final WorkflowDraftRepository drafts;
    private final WorkflowReleaseRepository releases;
    private final WorkflowTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final WorkflowGraphCodec codec;
    private final WorkflowBindingResolver bindingResolver;
    private final OperLogWriter operLogWriter;
    private final WorkflowProperties properties;

    /**
     * @param defs            定义仓储
     * @param drafts          草稿仓储
     * @param releases        发布版本仓储
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param codec           图 JSON 解析
     * @param bindingResolver 绑定快照
     * @param operLogWriter   操作日志（不写图正文）
     * @param properties      硬顶配置（max-nodes-per-graph）
     */
    public WorkflowDefAppService(WorkflowDefRepository defs,
                                 WorkflowDraftRepository drafts,
                                 WorkflowReleaseRepository releases,
                                 WorkflowTenantProvider tenantProvider,
                                 OperatorContext operatorContext,
                                 WorkflowGraphCodec codec,
                                 WorkflowBindingResolver bindingResolver,
                                 OperLogWriter operLogWriter,
                                 WorkflowProperties properties) {
        this.defs = defs;
        this.drafts = drafts;
        this.releases = releases;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.codec = codec;
        this.bindingResolver = bindingResolver;
        this.operLogWriter = operLogWriter;
        this.properties = properties;
    }

    /**
     * 分页查询当前租户的工作流。
     *
     * @param req 分页与筛选
     * @return 一页定义（含默认发布版本号）
     */
    public PageResult<WorkflowDefResp> page(WorkflowDefPageReq req) {
        WorkflowDefQuery query = new WorkflowDefQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setStatus(req.getStatus());
        query.setTenantId(tenantProvider.currentTenantId());
        PageResult<WorkflowDef> page = defs.page(query);
        List<WorkflowDefResp> rows = new ArrayList<>();
        for (WorkflowDef def : page.getRecords()) {
            rows.add(toDefResp(def));
        }
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 新建工作流，并自动生成「1 START + 1 END」的最小合法草稿。
     *
     * @param req 编码、名称、说明
     * @return 新建后的定义
     * @throws BizException 编码已存在或入参清洗后为空
     */
    @Transactional
    public WorkflowDefResp create(WorkflowDefCreateReq req) {
        long tenantId = tenantProvider.currentTenantId();
        String code = requiredText(req.getCode(), "工作流编码");
        if (defs.findByTenantAndCode(tenantId, code).isPresent()) {
            throw new BizException(ErrorCode.DUPLICATE, "工作流编码已存在：" + code);
        }
        Long operatorId = operatorContext.requireUserId();
        WorkflowDef def = new WorkflowDef();
        def.setTenantId(tenantId);
        def.setCode(code);
        def.setName(requiredText(req.getName(), "工作流名称"));
        def.setDescription(optionalText(req.getDescription()));
        def.setStatus(WorkflowDefStatus.initial());
        Long id = defs.insert(def, operatorId);

        // 新图直接给最小骨架，避免编辑页一进来就满屏「缺 START / 缺 END」
        WorkflowDraft draft = new WorkflowDraft();
        draft.setWorkflowId(id);
        draft.setGraphJson(codec.defaultGraph());
        drafts.insert(draft, operatorId);

        operLogWriter.write("workflow", "def_create", "workflow_def", String.valueOf(id),
                "新建工作流 code=" + code);
        return get(id);
    }

    /**
     * @param id 主键
     * @return 定义详情（含默认发布版本号）
     * @throws BizException 不存在或跨租户（统一 404）
     */
    public WorkflowDefResp get(Long id) {
        return toDefResp(requireDef(id));
    }

    /**
     * 改名称与说明；code 与发布指针不动。
     *
     * @param id  主键
     * @param req 新名称 / 说明
     * @return 更新后的定义
     */
    @Transactional
    public WorkflowDefResp update(Long id, WorkflowDefUpdateReq req) {
        WorkflowDef def = requireDef(id);
        def.setName(requiredText(req.getName(), "工作流名称"));
        def.setDescription(optionalText(req.getDescription()));
        Long operatorId = operatorContext.requireUserId();
        defs.updateMeta(def, operatorId);
        operLogWriter.write("workflow", "def_update", "workflow_def", String.valueOf(id),
                "更新工作流元数据 code=" + def.getCode());
        return get(id);
    }

    /**
     * 逻辑删除工作流定义。
     * <p>草稿与发布版本保留为历史快照；删除后列表、详情与应用触发均不可见。
     *
     * @param id 主键
     * @throws BizException 定义不存在或跨租户
     */
    @Transactional
    public void delete(Long id) {
        WorkflowDef def = requireDef(id);
        Long operatorId = operatorContext.requireUserId();
        int affected = defs.softDelete(id, def.getTenantId(), operatorId);
        if (affected == 0) {
            // 并发下已被他人删除：仍按 404 收口，避免前端误以为成功
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        operLogWriter.write("workflow", "def_delete", "workflow_def", String.valueOf(id),
                "删除工作流 code=" + def.getCode());
    }

    /**
     * 读取草稿。
     *
     * @param id 主键
     * @return 图 + 更新时间（供下次 PUT 做乐观锁）
     */
    public WorkflowDraftResp getDraft(Long id) {
        requireDef(id);
        return toDraftResp(requireDraft(id));
    }

    /**
     * 保存草稿。只拒绝结构非法的 JSON；业务校验错误允许先存草稿。
     *
     * @param id                 主键
     * @param graph              请求体图 JSON
     * @param expectedUpdateTime 可选，期望的草稿更新时间（HTTP 查询参数）
     * @return 保存后的草稿
     * @throws WorkflowConflictException 期望时间与库里不一致
     */
    @Transactional
    public WorkflowDraftResp saveDraft(Long id, JsonNode graph, String expectedUpdateTime) {
        WorkflowDef def = requireDef(id);
        String graphJson = codec.write(graph);
        // 结构校验在写库前做：nodes/edges 不是数组这类坏包不应落库
        codec.parse(graphJson);

        LocalDateTime expected = parseExpected(expectedUpdateTime);
        WorkflowDraft current = requireDraft(id);
        if (expected != null && current.getUpdateTime() != null
                && !sameSecond(current.getUpdateTime(), expected)) {
            throw new WorkflowConflictException("草稿已被他人修改，请刷新后重试");
        }
        Long operatorId = operatorContext.requireUserId();
        int touched = drafts.updateGraph(id, graphJson, expected, operatorId);
        if (touched == 0) {
            // 传了期望时间但 CAS 未命中：并发覆盖，明确 409 而不是静默后写覆盖
            throw new WorkflowConflictException("草稿已被他人修改，请刷新后重试");
        }
        defs.touch(def.getId(), def.getTenantId(), operatorId);
        return getDraft(id);
    }

    /**
     * 校验当前草稿。绑定不存在 / 停用按「发布严格模式」记 error。
     *
     * @param id 主键
     * @return errors / warns / passed
     */
    public WorkflowValidateResp validate(Long id) {
        requireDef(id);
        WorkflowDraft draft = requireDraft(id);
        WorkflowGraph graph = codec.parse(draft.getGraphJson());
        WorkflowBindingSnapshot bindings = bindingResolver.resolve(graph);
        WorkflowValidationResult result = WorkflowGraphValidator.validate(graph, bindings, true,
                properties.effectiveMaxNodesPerGraph());
        return toValidateResp(result);
    }

    /**
     * 发布当前草稿：校验 0 error → 生成不可变 release（version+1）→ 推进默认指针。
     *
     * @param id  主键
     * @param req 发布说明，可空
     * @return 新发布版本
     * @throws BizException 校验未通过或发布说明超长
     */
    @Transactional
    public WorkflowReleaseResp publish(Long id, WorkflowPublishReq req) {
        WorkflowDef def = requireDef(id);
        WorkflowDraft draft = requireDraft(id);
        WorkflowGraph graph = codec.parse(draft.getGraphJson());
        WorkflowBindingSnapshot bindings = bindingResolver.resolve(graph);
        WorkflowValidationResult result = WorkflowGraphValidator.validate(graph, bindings, true,
                properties.effectiveMaxNodesPerGraph());
        if (!result.passed()) {
            throw new BizException(ErrorCode.BAD_REQUEST, publishFailureMessage(result));
        }

        Long operatorId = operatorContext.requireUserId();
        int nextVersion = releases.maxVersion(id) + 1;
        WorkflowRelease release = new WorkflowRelease();
        release.setWorkflowId(id);
        release.setVersion(nextVersion);
        // 发布快照直接拷当前草稿原文，禁止把可变草稿作为线上执行真源
        release.setGraphJson(draft.getGraphJson());
        release.setFingerprint(WorkflowFingerprint.of(graph));
        release.setPublishNote(optionalText(req == null ? null : req.getNote()));
        release.setPublishedBy(operatorId);
        release.setCreatedBy(operatorId);
        release.setPublishTime(LocalDateTime.now());
        Long releaseId = releases.insert(release);
        defs.markPublished(id, def.getTenantId(), releaseId, operatorId);

        operLogWriter.write("workflow", "def_publish", "workflow_release", String.valueOf(releaseId),
                "发布工作流 code=" + def.getCode() + " v" + nextVersion);
        return toReleaseResp(releases.findById(releaseId).orElse(release));
    }

    /**
     * 版本列表，按版本号倒序；只返回元数据，不返回 graph_json。
     *
     * @param id 主键
     * @return 版本列表
     */
    public List<WorkflowReleaseResp> releases(Long id) {
        requireDef(id);
        return releases.listByWorkflowId(id).stream().map(this::toReleaseResp).toList();
    }

    /**
     * 取当前租户的定义；跨租户与不存在统一 404，避免探测其它租户数据。
     */
    private WorkflowDef requireDef(Long id) {
        WorkflowDef def = defs.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流不存在"));
        if (def.getTenantId() == null || def.getTenantId() != tenantProvider.currentTenantId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        return def;
    }

    /**
     * 取草稿；新建时已自动插入，缺失视为数据损坏。
     */
    private WorkflowDraft requireDraft(Long workspaceId) {
        return drafts.findByWorkflowId(workspaceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工作流草稿不存在"));
    }

    private WorkflowDefResp toDefResp(WorkflowDef def) {
        WorkflowDefResp resp = new WorkflowDefResp();
        resp.setId(def.getId());
        resp.setCode(def.getCode());
        resp.setName(def.getName());
        resp.setDescription(def.getDescription());
        resp.setStatus(def.getStatus());
        resp.setDefaultReleaseId(def.getDefaultReleaseId());
        if (def.getDefaultReleaseId() != null) {
            // 列表最多 10 行，逐条补版本号可接受；后续可换批量查询
            releases.findById(def.getDefaultReleaseId())
                    .ifPresent(release -> resp.setDefaultVersion(release.getVersion()));
        }
        resp.setCreateTime(def.getCreateTime());
        resp.setUpdateTime(def.getUpdateTime());
        return resp;
    }

    private WorkflowDraftResp toDraftResp(WorkflowDraft draft) {
        WorkflowDraftResp resp = new WorkflowDraftResp();
        resp.setWorkflowId(draft.getWorkflowId());
        resp.setGraph(codec.read(draft.getGraphJson()));
        resp.setUpdateTime(draft.getUpdateTime());
        return resp;
    }

    private WorkflowValidateResp toValidateResp(WorkflowValidationResult result) {
        WorkflowValidateResp resp = new WorkflowValidateResp();
        resp.setPassed(result.passed());
        resp.setErrors(result.getErrors().stream().map(WorkflowIssueResp::of).toList());
        resp.setWarns(result.getWarns().stream().map(WorkflowIssueResp::of).toList());
        return resp;
    }

    private WorkflowReleaseResp toReleaseResp(WorkflowRelease release) {
        WorkflowReleaseResp resp = new WorkflowReleaseResp();
        resp.setId(release.getId());
        resp.setWorkflowId(release.getWorkflowId());
        resp.setVersion(release.getVersion());
        resp.setFingerprint(release.getFingerprint());
        resp.setPublishNote(release.getPublishNote());
        resp.setPublishedBy(release.getPublishedBy());
        resp.setPublishTime(release.getPublishTime());
        return resp;
    }

    /**
     * 发布失败文案：最多带 3 条 error 摘要，完整列表由 Validate 接口返回。
     */
    private String publishFailureMessage(WorkflowValidationResult result) {
        List<String> messages = result.getErrors().stream()
                .map(issue -> issue.getMessage() == null ? issue.getCode() : issue.getMessage())
                .toList();
        String head = messages.stream().limit(3).reduce((left, right) -> left + "；" + right).orElse("");
        String suffix = messages.size() > 3 ? " 等 " + messages.size() + " 项" : "";
        return "发布校验未通过：" + head + suffix;
    }

    /**
     * 解析可选期望时间；空串视为不校验。
     */
    private LocalDateTime parseExpected(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(text, INPUT_TIME);
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "expectedUpdateTime 格式应为 yyyy-MM-dd HH:mm:ss[.SSS]");
        }
    }

    /**
     * 只比到秒：MySQL DATETIME 无小数秒，避免毫秒误差造成假冲突。
     */
    private boolean sameSecond(LocalDateTime left, LocalDateTime right) {
        return left.withNano(0).equals(right.withNano(0));
    }

    private String requiredText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + "不能为空");
        }
        return raw.trim();
    }

    private String optionalText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
