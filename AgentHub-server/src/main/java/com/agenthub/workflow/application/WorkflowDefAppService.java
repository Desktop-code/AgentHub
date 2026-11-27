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
import com.agenthub.workflow.api.dto.WorkflowReleaseActivateReq;
import com.agenthub.workflow.api.dto.WorkflowReleaseActivateResp;
import com.agenthub.workflow.api.dto.WorkflowReleaseInputSchemaResp;
import com.agenthub.workflow.api.dto.WorkflowReleaseResp;
import com.agenthub.workflow.api.dto.WorkflowValidateResp;
import com.agenthub.workflow.domain.WorkflowConflictException;
import com.agenthub.workflow.domain.enums.WorkflowDefStatus;
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
import com.agenthub.workflow.domain.service.WorkflowInputDeclaration;
import com.agenthub.workflow.domain.service.WorkflowRunLimits;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    /** 校验 / 发布 / 调试入口共用的发布口径检查 */
    private final WorkflowPublishCheck publishCheck;
    private final OperLogWriter operLogWriter;
    private final WorkflowProperties properties;

    /**
     * @param defs            定义仓储
     * @param drafts          草稿仓储
     * @param releases        发布版本仓储
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param codec           图 JSON 解析
     * @param publishCheck    发布口径的图检查（校验、发布、调试入口共用）
     * @param operLogWriter   操作日志（不写图正文）
     * @param properties      硬顶配置（max-nodes-per-graph）
     */
    public WorkflowDefAppService(WorkflowDefRepository defs,
                                 WorkflowDraftRepository drafts,
                                 WorkflowReleaseRepository releases,
                                 WorkflowTenantProvider tenantProvider,
                                 OperatorContext operatorContext,
                                 WorkflowGraphCodec codec,
                                 WorkflowPublishCheck publishCheck,
                                 OperLogWriter operLogWriter,
                                 WorkflowProperties properties) {
        this.defs = defs;
        this.drafts = drafts;
        this.releases = releases;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.codec = codec;
        this.publishCheck = publishCheck;
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
        WorkflowGraph parsed = codec.parse(graphJson);
        /*
         * 运行时限超过平台硬顶时直接拒绝保存：
         * 这类值发布后也跑不起来，先存下来只会让配置者和运行者对不上；
         * 文案只用分钟，不把 yml 键名 / 毫秒抛给页面。
         */
        rejectInvalidLimits(parsed);
        // 子图链路的环 / 超深 / 版本不可用直接拒绝保存；其它业务错误仍允许先存草稿
        publishCheck.requireSubgraphSafe(def.getId(), def.getTenantId(), parsed);

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
        WorkflowDef def = requireDef(id);
        WorkflowDraft draft = requireDraft(id);
        return toValidateResp(publishCheck.check(def.getId(), def.getTenantId(),
                codec.parse(draft.getGraphJson())));
    }

    /**
     * 校验调用方交来的图，不落库。
     * <p>调试按钮要按画布此刻的编排检查，不能只读已经保存的草稿。
     * 规则与 {@link #validate(Long)}、发布前检查相同。
     *
     * @param id    主键（只用来确认定义存在且属于当前租户）
     * @param graph 画布此刻的 graph_json
     * @return errors / warns / passed
     * @throws BizException 图结构不合法，或定义不存在
     */
    public WorkflowValidateResp validateGraph(Long id, JsonNode graph) {
        WorkflowDef def = requireDef(id);
        WorkflowGraph parsed = codec.parse(codec.write(graph));
        return toValidateResp(publishCheck.check(def.getId(), def.getTenantId(), parsed));
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
        WorkflowValidationResult result = publishCheck.check(def.getId(), def.getTenantId(), graph);
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
     * 某个已发布版本的 START 入参声明。
     * <p>子图节点必须按「钉死版本」的声明出槽：这里直接读该版本的 graph_json，
     * 不读被调工作流当前草稿，也不按当前默认发布反推，避免旧绑定悄悄变形。
     * <p>跨租户与不存在统一 404，避免探测其它租户的发布版本。
     *
     * @param id        工作流定义 id
     * @param releaseId 发布版本 id
     * @return 版本号、声明与必填键；该版本没有声明时 inputSchema 为 null
     * @throws BizException 定义/版本不存在、版本不属于该工作流
     */
    public WorkflowReleaseInputSchemaResp releaseInputSchema(Long id, Long releaseId) {
        requireDef(id);
        WorkflowRelease release = releases.findById(releaseId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "发布版本不存在"));
        if (!id.equals(release.getWorkflowId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "发布版本不属于该工作流");
        }
        JsonNode declaration = WorkflowInputDeclaration.startDeclaration(codec.parse(release.getGraphJson()));
        WorkflowReleaseInputSchemaResp resp = new WorkflowReleaseInputSchemaResp();
        resp.setWorkflowId(release.getWorkflowId());
        resp.setReleaseId(release.getId());
        resp.setVersion(release.getVersion());
        resp.setInputSchema(declaration);
        resp.setRequiredKeys(WorkflowInputDeclaration.requiredKeys(declaration));
        return resp;
    }

    /**
     * 把画布草稿恢复成某个已发布版本，并把默认发布指针指回该版本。
     * <p>不新增版本号，也不改那条 release 的图。工作台之后的「运行」读这个指针，
     * 已经跑完的 run 仍用自己建当时拷下的图。
     * <p>草稿与当前默认发布的图不一致时，必须由调用方明确 overwrite，否则 409。
     *
     * @param id        工作流定义 id
     * @param releaseId 要切过去的已发布版本
     * @param req       overwrite=true 表示用户已确认覆盖未发布改动
     * @return 版本元数据、该版的图、草稿新的更新时间
     * @throws WorkflowConflictException 还有未发布改动且未确认覆盖
     * @throws BizException              版本不存在或不属于该工作流
     */
    @Transactional
    public WorkflowReleaseActivateResp activate(Long id, Long releaseId, WorkflowReleaseActivateReq req) {
        WorkflowDef def = requireDef(id);
        WorkflowRelease target = releases.findById(releaseId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "发布版本不存在"));
        if (!id.equals(target.getWorkflowId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "发布版本不属于该工作流");
        }
        WorkflowDraft draft = requireDraft(id);
        boolean overwrite = req != null && Boolean.TRUE.equals(req.getOverwrite());
        // 未发布改动只存在于草稿：与当前默认发布的图不同（或还从未发布）就要先确认
        if (!overwrite && hasUnpublishedChanges(def, draft)) {
            throw new WorkflowConflictException(
                    "画布上还有未发布的改动，确认后将覆盖为所选版本，并把线上默认切过去");
        }
        Long operatorId = operatorContext.requireUserId();
        int touched = drafts.updateGraph(id, target.getGraphJson(), null, operatorId);
        if (touched == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "工作流草稿不存在");
        }
        // 只移动默认指针，不插入新的 release，版本号保持原样
        defs.markPublished(id, def.getTenantId(), target.getId(), operatorId);
        operLogWriter.write("workflow", "def_activate", "workflow_release", String.valueOf(target.getId()),
                "切换默认发布 code=" + def.getCode() + " v" + target.getVersion());
        WorkflowDraft fresh = requireDraft(id);
        WorkflowReleaseActivateResp resp = toActivateResp(target);
        resp.setGraph(codec.read(target.getGraphJson()));
        resp.setDraftUpdateTime(fresh.getUpdateTime());
        return resp;
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
        // 平台硬顶折算成分钟给配置页：页面上只出现分钟，不出现 yml 键名或毫秒
        resp.setPlatformMaxMinutes(properties.effectiveWallMinutes());
        resp.setPlatformMaxLoopIterations(properties.effectiveMaxLoopIterations());
        return resp;
    }

    /**
     * 保存草稿前的运行时限校验：任一问题即拒绝保存。
     *
     * @param graph 已结构校验的图
     * @throws BizException 整段 / 单节点上限超过平台硬顶或彼此倒挂
     */
    private void rejectInvalidLimits(WorkflowGraph graph) {
        List<String> issues = WorkflowRunLimits.issues(graph, properties.effectiveWallMs());
        if (!issues.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, String.join("；", issues));
        }
    }

    /**
     * 草稿相对当前线上版本还有没有没发布的改动。
     * <p>从未发布，或默认发布记录缺失，都算还有未发布内容，切换前必须确认。
     * 只比节点、连线和时限；视口平移不算改动。
     */
    private boolean hasUnpublishedChanges(WorkflowDef def, WorkflowDraft draft) {
        if (def.getDefaultReleaseId() == null) {
            return true;
        }
        WorkflowRelease current = releases.findById(def.getDefaultReleaseId()).orElse(null);
        if (current == null) {
            return true;
        }
        return !sameCanvas(draft.getGraphJson(), current.getGraphJson());
    }

    /**
     * 两份图去掉视口后是否相同。解析失败视为不同，避免静默覆盖一份读不出来的草稿。
     */
    private boolean sameCanvas(String left, String right) {
        try {
            JsonNode a = codec.read(left);
            JsonNode b = codec.read(right);
            if (a instanceof ObjectNode object) {
                object.remove("viewport");
            }
            if (b instanceof ObjectNode object) {
                object.remove("viewport");
            }
            return a.equals(b);
        } catch (RuntimeException ex) {
            return false;
        }
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

    private WorkflowReleaseActivateResp toActivateResp(WorkflowRelease release) {
        WorkflowReleaseActivateResp resp = new WorkflowReleaseActivateResp();
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
