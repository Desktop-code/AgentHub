package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import com.agenthub.agent.api.dto.AgentRuntimeConfigReq;
import com.agenthub.agent.api.dto.AgentRuntimeConfigResp;
import com.agenthub.agent.api.dto.MaCandidateReq;
import com.agenthub.agent.api.dto.MaCandidateResp;
import com.agenthub.agent.api.dto.MaDefDetailResp;
import com.agenthub.agent.api.dto.MaDefPageReq;
import com.agenthub.agent.api.dto.MaDefRowResp;
import com.agenthub.agent.api.dto.MaDefSaveReq;
import com.agenthub.agent.api.dto.MaJudgeModelOptionResp;
import com.agenthub.agent.api.dto.PromptBindingReq;
import com.agenthub.agent.api.dto.PromptBindingResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentAvatarConfig;
import com.agenthub.agent.domain.model.AgentPromptBinding;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.model.MaDefQuery;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.MaDefRepository;
import com.agenthub.agent.domain.service.AgentRouteDefaults;
import com.agenthub.agent.domain.service.MaDefPolicy;
import com.agenthub.agent.domain.service.MaRouteKeys;
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.agent.domain.service.RoutePoolDedupPolicy;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.text.TextClip;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多智能体方案用例。所属域：agent 的 application。
 *
 * <p>当前职责（MA-P2 刀3 起为唯一写路径）：
 * <ul>
 *   <li>ma_def + ma_candidate 的列表 / 详情 / 新建 / 更新 / 启停 / 删除；</li>
 *   <li>绑根唯一启用、候选纪律（复用旧 Tab 同一套门闩）、评审环角色校验；</li>
 *   <li><b>保存只落 ma_def / ma_candidate</b>：不再镜像回 {@code agent_route} 或
 *       {@code agent_def.runtime_config_json}，历史 route 行保持原样。</li>
 * </ul>
 *
 * <p>边界：不做虚拟根执行、不做 ma 版本历史、不做调用边审计；
 * 执行读源在刀2 已切到 ma（{@code RuntimeMaLoader}），本类不再负责同步。
 */
@Service
public class MaDefAppService {

    /** ma_candidate.intent_desc 的列宽；简介回填时按此截断 */
    private static final int INTENT_DESC_MAX = 512;

    /**
     * 方案上 ROUTER 提示词的用途码：固定 ROUTER。
     * <p>用字面量而不是 prompt 域枚举，避免 agent 域反向依赖 prompt 域的包结构。
     */
    private static final String PURPOSE_ROUTER = "ROUTER";

    private final MaDefRepository mas;
    private final AgentDefRepository agents;
    private final MaTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    /** 刀5B：工作流候选的存在性 / 发布 / 说明校验与展示信息（只读） */
    private final WorkflowDefRepository workflowDefs;
    /**
     * 判路由模型下拉（只读）。
     * <p>方案编辑页要能配入口 / 场景 Router 的模型槽，但不该逼用户去申请模型中心权限，
     * 因此由本域按「启用 + 对话形态」筛出候选实例。
     */
    private final ModelInstanceRepository modelInstances;
    /**
     * ROUTER 提示词保存闸：与专家配提示词共用 {@code normalize}，不复制一套段落规则。
     * <p>方案（含入口）的 ROUTER 模板引用同样落在 {@code ma_def.config_json}，由它统一校验。
     */
    private final AgentPromptAppService prompts;

    /**
     * @param mas             方案仓储
     * @param agents          Agent 定义仓储
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param operLogWriter   操作日志（只写摘要，不写完整配置）
     * @param workflowDefs    工作流定义（刀5B 工作流候选）
     * @param modelInstances  判路由模型下拉
     * @param prompts         ROUTER 提示词保存闸 / 回显
     */
    public MaDefAppService(MaDefRepository mas, AgentDefRepository agents,
                           MaTenantProvider tenantProvider, OperatorContext operatorContext,
                           OperLogWriter operLogWriter, WorkflowDefRepository workflowDefs,
                           ModelInstanceRepository modelInstances, AgentPromptAppService prompts) {
        this.mas = mas;
        this.agents = agents;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.workflowDefs = workflowDefs;
        this.modelInstances = modelInstances;
        this.prompts = prompts;
    }

    /**
     * 分页查询当前租户的方案。keyword 匹配名称/编码，可筛启停、模板与是否绑根。
     *
     * <p>工作台入口（{@code ENTRY_ROUTER}）与普通方案出现在同一张列表里：它是平台唯一入口，
     * 但运营需要在编排列表里看得见、点得开去配模型与 ROUTER 提示词。
     * 身份差异不靠「隐藏」表达，而靠固定的展示名「Router」+ 只读锁定（见 {@code MaDefPolicy}）。
     *
     * @param req 分页与筛选
     * @return 一页方案；绑定根名称与启用候选数已批量补齐
     */
    public PageResult<MaDefRowResp> page(MaDefPageReq req) {
        long tenantId = tenantProvider.currentTenantId();
        MaDefQuery query = new MaDefQuery();
        query.setTenantId(tenantId);
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setEnabled(req.getEnabled());
        query.setTemplate(req.getTemplate());
        PageResult<MaDef> page = mas.page(query);

        List<Long> ids = page.getRecords().stream().map(MaDef::getId).toList();
        Map<Long, Integer> counts = mas.countEnabledCandidates(ids);
        List<MaDefRowResp> rows = new ArrayList<>(page.getRecords().size());
        for (MaDef def : page.getRecords()) {
            // 形象读方案自己的 avatar_config：不再按 root_agent_id 批量查 Agent
            rows.add(toRow(def, counts.getOrDefault(def.getId(), 0)));
        }
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * 方案详情：含候选与子 Agent 展示信息。
     *
     * @param id 主键
     * @return 详情
     * @throws BizException 不存在或跨租户（统一 404）
     */
    public MaDefDetailResp get(Long id) {
        MaDef def = requireOwn(id);
        return toDetail(def, mas.listCandidates(def.getId()));
    }

    /**
     * 查某个 ROUTER 当前绑定的启用方案（历史兼容接口）。
     *
     * <p>方案已与 Agent 根断开，新页面不再按根查方案；这里只为仍在调用旧接口的客户端保留，
     * 读取的是历史列 {@code root_agent_id}，不参与任何运行 / 形象路径。
     *
     * @param rootAgentId ROUTER 主键
     * @return 绑定方案；没有返回 null
     */
    public MaDefRowResp getByRoot(Long rootAgentId) {
        Optional<MaDef> bound = mas.findEnabledByRoot(tenantProvider.currentTenantId(), rootAgentId);
        if (bound.isEmpty()) {
            return null;
        }
        MaDef def = bound.get();
        Map<Long, Integer> counts = mas.countEnabledCandidates(List.of(def.getId()));
        return toRow(def, counts.getOrDefault(def.getId(), 0));
    }

    /**
     * 方案编辑页「判路由模型」下拉：启用中、用途属于对话形态的实例。
     *
     * <p>入口 Router 与普通场景 Router 共用这一份列表；只读、不校验凭证，
     * 实例是否真的能出站仍由执行侧在开跑时判定（与 {@code MaTrialSpecLoader} 同口径）。
     *
     * @return 可选实例；没有时为空列表
     */
    public List<MaJudgeModelOptionResp> judgeModels() {
        Map<Long, MaJudgeModelOptionResp> rows = new HashMap<>();
        for (ModelCapability capability : List.of(ModelCapability.ROUTER, ModelCapability.CHAT,
                ModelCapability.REWRITE, ModelCapability.SUMMARY, ModelCapability.MEMORY)) {
            for (ModelInstance instance : modelInstances.listEnabledByCapability(capability.name())) {
                // 用途白名单再兜一层：兼容历史上把 capability 写成非对话形态的脏行
                boolean usable = ModelCapability.tryParse(instance.getCapability())
                        .map(ModelCapability::usesChat)
                        .orElse(false);
                if (!usable || rows.containsKey(instance.getId())) {
                    continue;
                }
                MaJudgeModelOptionResp row = new MaJudgeModelOptionResp();
                row.setId(instance.getId());
                row.setDisplayName(instance.getDisplayName());
                row.setModelId(instance.getModelId());
                row.setCapability(instance.getCapability());
                row.setStatus(instance.getStatus());
                rows.put(instance.getId(), row);
            }
        }
        return new ArrayList<>(rows.values());
    }

    /**
     * 新建方案（候选可嵌在请求里整份保存）。
     *
     * <p>方案就是自己的根：不再校验 / 不再写入任何 Agent 根，{@code rootAgentId} 恒落 null；
     * 形象落在方案自己的 {@code avatar_config}。
     *
     * @param req 方案、配置与候选
     * @return 新建后的列表行
     * @throws BizException 编码重复、模板非法、候选不合法
     */
    @Transactional
    public MaDefRowResp create(MaDefSaveReq req) {
        long tenantId = tenantProvider.currentTenantId();
        Long operatorId = operatorContext.requireUserId();
        String code = requiredText(req.getCode(), 64, "方案编码");
        if (mas.findByTenantAndCode(tenantId, code).isPresent()) {
            throw new BizException(ErrorCode.DUPLICATE, "方案编码已存在：" + code);
        }
        // 历史并行类 / 串行类在这里收成两种协作模式，落库不再保留旧模板码
        String template = MaDefPolicy.assertTemplate(req.getTemplate().trim());
        AgentRuntimeConfig config = mergeConfig(req.getConfig(), template, null);
        MaDefPolicy.assertSerialContextLocked(template, config);
        applyJudgePrompt(config, req.getConfig());
        String configJson = AgentRuntimeConfig.toStorageJson(config, AgentRunMode.ROUTER.name());

        int enabled = req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled();

        List<MaCandidate> candidates = dedupePool(buildCandidates(req.getCandidates()));
        // 候选纪律只认方案自己的运行配置：不再为了让断言通过而合成一个 ROUTER 父壳
        MaDefPolicy.assertCandidates(config, candidates, agents::findById);
        // 新建方案：所有候选都是新绑，简介为空一律拒绝（简介是意图说明唯一来源）
        MaDefPolicy.assertCandidateDescriptions(candidates, agents::findById, Set.of());
        // 刀5：MA 目标必须已启用、非默认接待、自己是叶子方案、有方案说明
        MaDefPolicy.assertMaTargets(candidates, null, template, tenantId, mas::findById,
                mas::listCandidates, Set.of());
        // 刀5B：工作流目标必须已发布（有默认发布指针）且有说明，模板同样要会判路由
        MaDefPolicy.assertWorkflowTargets(candidates, template, tenantId,
                this::workflowTargetInfo, Set.of());
        MaDefPolicy.assertCriticRoles(config, candidates, agents::findById);
        // 竞争选优：启用候选不足 2 个在保存时就拦，避免运行期才发现「只有 1 个选手」
        MaDefPolicy.assertCompeteCandidates(config, candidates);

        MaDef def = new MaDef();
        def.setTenantId(tenantId);
        def.setCode(code);
        def.setName(requiredText(req.getName(), 128, "方案名称"));
        def.setDescription(clipDescription(req.getDescription()));
        def.setAvatarConfigJson(toAvatarJson(req.getAvatarConfig()));
        def.setTemplate(template);
        def.setConfigJson(configJson);
        // 方案不再绑 Agent 根：历史字段恒写 null，列保留但不再承载运行语义
        def.setRootAgentId(null);
        def.setEnabled(enabled);
        def.setVersion(1);
        Long id = mas.insert(def, operatorId);
        for (MaCandidate candidate : candidates) {
            candidate.setMaId(id);
        }
        mas.replaceCandidates(id, candidates);

        MaDef saved = mas.findById(id).orElseThrow();
        operLogWriter.write("agent", "ma_create", "ma_def", String.valueOf(id),
                "新建多智能体方案 code=" + code + " template=" + template);
        return toRow(saved, countEnabled(candidates));
    }

    /**
     * 更新方案；候选整份替换。只写 ma_def / ma_candidate，历史 agent_route 行保持不动。
     *
     * @param id  主键
     * @param req 方案、配置与候选
     * @return 更新后的列表行
     * @throws BizException 不存在、跨租户、编码重复或校验失败
     */
    @Transactional
    public MaDefRowResp update(Long id, MaDefSaveReq req) {
        MaDef existing = requireOwn(id);
        Long operatorId = operatorContext.requireUserId();
        long tenantId = existing.getTenantId();
        String code = requiredText(req.getCode(), 64, "方案编码");
        Optional<MaDef> sameCode = mas.findByTenantAndCode(tenantId, code);
        if (sameCode.isPresent() && !sameCode.get().getId().equals(id)) {
            throw new BizException(ErrorCode.DUPLICATE, "方案编码已存在：" + code);
        }
        String template = req.getTemplate().trim();
        // 入口类型是数据锁定值：只能在库内保持 ENTRY_ROUTER，不能被页面改成普通方案，也不能换绑根
        MaDefPolicy.assertEntryLocked(existing, template);
        boolean entry = AgentRuntimeConfig.isEntryRouterTemplate(existing.getTemplate());
        if (entry) {
            // 身份字段（编码 / 名称 / 备注）同样锁定：有值且与固定值不一致就明确拒绝，不静默丢弃
            MaDefPolicy.assertEntryIdentity(existing, code, req.getName(), req.getDescription());
            // 入口类型不在「页面可写」白名单里，assertTemplate 会拒；这里直接以库内值继续
            template = AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER;
        } else {
            template = MaDefPolicy.assertTemplate(template);
        }
        AgentRuntimeConfig config = mergeConfig(req.getConfig(), template, existing.getConfigJson());
        MaDefPolicy.assertSerialContextLocked(template, config);
        applyJudgePrompt(config, req.getConfig());
        String configJson = AgentRuntimeConfig.toStorageJson(config, AgentRunMode.ROUTER.name());

        int enabled = req.getEnabled() == null
                ? (existing.getEnabled() == null ? AgentStatus.ENABLED : existing.getEnabled())
                : req.getEnabled();

        // 保存与运行同一套去重：方案优先，去掉已被入池方案包含的裸专家。入口和普通方案都走
        List<MaCandidate> candidates = dedupePool(buildCandidates(req.getCandidates()));
        MaDefPolicy.assertCandidates(config, candidates, agents::findById);
        // 保存前的既有候选只读一次：编辑旧方案时要放行「历史上就没填说明」的 Agent / 方案目标
        List<MaCandidate> previous = mas.listCandidates(id);
        Set<Long> alreadyBound = previous.stream()
                .map(MaCandidate::getAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        MaDefPolicy.assertCandidateDescriptions(candidates, agents::findById, alreadyBound);
        // MA 目标的历史放行口径同 Agent：已绑定的目标允许说明为空，新加入的必须已填说明
        Set<Long> alreadyBoundTargets = previous.stream()
                .filter(MaCandidate::isMaTarget)
                .map(MaCandidate::getMaDefId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        MaDefPolicy.assertMaTargets(candidates, id, template, tenantId, mas::findById,
                mas::listCandidates, alreadyBoundTargets);
        // 工作流目标的历史放行口径同 Agent / 方案：已绑定的允许说明为空，新加入的必须已发布且有说明
        Set<Long> alreadyBoundWorkflows = previous.stream()
                .filter(MaCandidate::isWorkflowTarget)
                .map(MaCandidate::getWorkflowDefId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        MaDefPolicy.assertWorkflowTargets(candidates, template, tenantId,
                this::workflowTargetInfo, alreadyBoundWorkflows);
        MaDefPolicy.assertCriticRoles(config, candidates, agents::findById);
        // 更新同样拦：把候选停到只剩 1 个时不允许保存成 COMPETE
        MaDefPolicy.assertCompeteCandidates(config, candidates);

        existing.setCode(code);
        existing.setName(requiredText(req.getName(), 128, "方案名称"));
        existing.setDescription(clipDescription(req.getDescription()));
        // 形象属于方案自己：入口与普通方案都可改，不受身份字段锁定影响
        existing.setAvatarConfigJson(toAvatarJson(req.getAvatarConfig()));
        existing.setTemplate(template);
        if (entry) {
            // 入口身份字段以平台锁定值为准：上面的比对已放行请求，这里落库归一，
            // 保证历史库里叫「默认接待」/备注为空的老行保存一次后也收敛到「Router」+ 固定备注
            existing.setName(AgentRuntimeConfig.ENTRY_ROUTER_NAME);
            existing.setDescription(AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION);
        }
        existing.setConfigJson(configJson);
        // 方案不再绑 Agent 根：请求里的旧值忽略，落库恒为 null（不强制用户先手清）
        existing.setRootAgentId(null);
        existing.setEnabled(enabled);
        mas.update(existing, operatorId);
        for (MaCandidate candidate : candidates) {
            candidate.setMaId(id);
        }
        mas.replaceCandidates(id, candidates);

        MaDef saved = mas.findById(id).orElseThrow();
        operLogWriter.write("agent", "ma_update", "ma_def", String.valueOf(id),
                "更新多智能体方案 code=" + code + " template=" + template);
        return toRow(saved, countEnabled(candidates));
    }

    /**
     * 启停方案。启用时再次校验同绑根唯一。
     *
     * <p>刀3 起启停只改 ma_def 自身状态：不再把配置/候选镜像到绑定 ROUTER，
     * 执行侧由刀2 的 ma 读源直接取启用方案。
     *
     * @param id  主键
     * @param enabled 1 启用 / 0 停用
     * @throws BizException 不存在、跨租户或同根已有启用方案
     */
    @Transactional
    public void setEnabled(Long id, int enabled) {
        MaDef existing = requireOwn(id);
        // 入口是工作台唯一网关：停用它等于把工作台关掉，明确拒绝而不是让首页突然不可用
        if (AgentRuntimeConfig.isEntryRouterTemplate(existing.getTemplate())
                && enabled != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口不能停用：它是对话首页唯一入口，停用后所有人都无法开始对话");
        }
        Long operatorId = operatorContext.requireUserId();
        // 方案以自己为根：启用不再校验 / 占用任何 Agent 根，只要方案自身启用即可
        int affected = mas.updateEnabled(id, existing.getTenantId(), enabled, operatorId);
        if (affected == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不存在");
        }
        operLogWriter.write("agent", enabled == AgentStatus.ENABLED ? "ma_enable" : "ma_disable",
                "ma_def", String.valueOf(id), "多智能体方案启停 code=" + existing.getCode());
    }

    /**
     * 物理删除方案并清理候选；绑定的 ROUTER 保持现状，不反向清空执行配置。
     *
     * @param id 主键
     * @throws BizException 不存在或跨租户
     */
    @Transactional
    public void delete(Long id) {
        MaDef existing = requireOwn(id);
        // 入口是数据锁定的唯一行：不允许删除，避免整平台丢失工作台入口
        if (AgentRuntimeConfig.isEntryRouterTemplate(existing.getTemplate())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "对话入口不能删除：它是平台唯一入口；如需调整请改它的模型与提示词");
        }
        int affected = mas.delete(id, existing.getTenantId());
        if (affected == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "方案不存在");
        }
        operLogWriter.write("agent", "ma_delete", "ma_def", String.valueOf(id),
                "删除多智能体方案 code=" + existing.getCode());
    }

    /**
     * 取本租户方案；跨租户与不存在统一 404，避免探测其它租户数据。
     */
    private MaDef requireOwn(Long id) {
        MaDef def = mas.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "多智能体方案不存在"));
        if (def.getTenantId() == null || def.getTenantId() != tenantProvider.currentTenantId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "多智能体方案不存在");
        }
        return def;
    }

    /**
     * 合并「顶层 template」与配置请求：顶层模板优先，其余字段原样交给归一化。
     *
     * <p>更新时以库内既有 config 为底：请求里没带的字段保持原值，避免只改名称就把
     * 并行上限、评审轮次等高级配置洗回默认。
     *
     * @param req        配置请求，可空
     * @param template   顶层模板
     * @param baseJson   库内既有配置；新建传 null
     * @return 待归一化的运行配置
     */
    private AgentRuntimeConfig mergeConfig(AgentRuntimeConfigReq req, String template, String baseJson) {
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(baseJson, AgentRunMode.ROUTER.name());
        config.setMaTemplate(template);
        if (req != null) {
            config.setMaContextPolicy(req.getMaContextPolicy());
            config.setMaAllowParentHistoryWhenSingle(req.getMaAllowParentHistoryWhenSingle());
            config.setMultiIntentEnabled(req.getMultiIntentEnabled());
            config.setAnswerMode(req.getAnswerMode());
            config.setMaxRouteChildren(req.getMaxRouteChildren());
            config.setRequirePlanConfirm(req.getRequirePlanConfirm());
            config.setMaxPlanSteps(req.getMaxPlanSteps());
            config.setMaxStepRetries(req.getMaxStepRetries());
            config.setMaChildRunModesAllowed(req.getMaChildRunModesAllowed());
            config.setMaCriticMaxRounds(req.getMaCriticMaxRounds());
            config.setMaCriticAgentId(req.getMaCriticAgentId());
            // 判路由模型：虚拟根试跑的唯一模型来源，必须允许随方案保存
            config.setMaJudgeModelInstanceId(req.getMaJudgeModelInstanceId());
            config.setMaJudgeModel(req.getMaJudgeModel());
            // ROUTER 提示词：入口与场景 Router 共用同一套 L3 语义；段落级校验由 applyJudgePrompt 统一做
            config.setMaJudgePromptTemplateId(req.getMaJudgePromptTemplateId());
            config.setMaJudgePromptTemplateVersion(req.getMaJudgePromptTemplateVersion());
            config.setMaJudgePromptExtra(req.getMaJudgePromptExtra());
            // 竞争选优策略：只落配置，执行侧按模板=COMPETE 时读取
            config.setCompeteStrategy(req.getCompeteStrategy());
        }
        return config;
    }

    /**
     * ROUTER 提示词保存闸：把请求里的四列交给与专家共用的 {@code normalize}，
     * 再写回 {@link AgentRuntimeConfig}（模板 id / 版本 / soft 覆盖 JSON / 追加区）。
     *
     * <p>为什么不自己校验段落：锁定段、未知段、版本不一致这些规则只在 prompt 域有一份实现，
     * 复制一套必然漂移。未带 config 的更新（只改名称等）直接跳过，保留库内原值。
     *
     * @param config 已合并的运行配置
     * @param req    原始配置请求；可空
     * @throws BizException 模板未发布、用途不匹配、覆盖了锁定段等
     */
    private void applyJudgePrompt(AgentRuntimeConfig config, AgentRuntimeConfigReq req) {
        if (req == null) {
            return;
        }
        PromptBindingReq bindingReq = new PromptBindingReq();
        bindingReq.setTemplateId(req.getMaJudgePromptTemplateId());
        bindingReq.setTemplateVersion(req.getMaJudgePromptTemplateVersion());
        bindingReq.setOverride(req.getMaJudgePromptOverride());
        bindingReq.setPromptExtra(req.getMaJudgePromptExtra());
        AgentPromptBinding binding = prompts.normalize(PURPOSE_ROUTER, bindingReq);
        config.setMaJudgePromptTemplateId(binding.getTemplateId());
        config.setMaJudgePromptTemplateVersion(binding.getTemplateVersion());
        config.setMaJudgePromptOverrideJson(binding.getOverrideJson());
        config.setMaJudgePromptExtra(binding.getPromptExtra());
    }

    /**
     * 入口路由池保存前去重：方案优先，去掉已被某套入池方案包含的裸专家。
     *
     * <p>与运行侧 {@code MaTrialSpecLoader} 共用 {@link RoutePoolDedupPolicy}，
     * 不在保存侧另写一套判定。两套入池方案成员重叠时不合并、不删方案（交给入口挑）。
     *
     * @param candidates 请求解析出来的候选（含 AGENT / MA / WORKFLOW 三类）
     * @return 裁剪后的候选；没有重复时原样返回
     */
    private List<MaCandidate> dedupePool(List<MaCandidate> candidates) {
        List<MaCandidate> source = candidates == null ? List.of() : candidates;
        List<MaCandidate> pruned = RoutePoolDedupPolicy.preferSchemes(source, mas::listCandidates);
        int dropped = source.size() - pruned.size();
        if (dropped > 0) {
            // 只记数量，不记名单：避免把专家编码等配置细节写进操作日志
            operLogWriter.write("agent", "ma_pool_dedup", "ma_def", "-",
                    "路由池保存去掉被方案覆盖的裸专家 " + dropped + " 条");
        }
        return pruned;
    }

    /**
     * 请求候选 → 领域候选：补 route_key / sort_no 后再交给策略校验。
     * route_key 生成规则与旧 Tab 一致：优先子 Agent code，冲突回落 child_{id}。
     *
     * <p><b>意图说明单一真源（刀4）</b>：候选不再接受调用方传入的 {@code intentDesc}，
     * 一律在保存时用该 Agent 当时的简介回填；列因 NOT NULL 保留，但不作为路由输入。
     * 请求里带的 intentDesc 若与当前简介一致（旧前端回显）直接忽略；
     * 若想借方案单独改文案则明确失败，避免「界面改了但路由读的是另一份」。
     */
    private List<MaCandidate> buildCandidates(List<MaCandidateReq> reqs) {
        List<MaCandidateReq> list = reqs == null ? List.of() : reqs;
        Set<String> usedKeys = new HashSet<>();
        int maxSort = 0;
        // 第一遍：收集显式 route_key 与最大排序，避免生成键与后面的显式键撞车
        for (MaCandidateReq req : list) {
            if (req.getRouteKey() != null && !req.getRouteKey().isBlank()) {
                usedKeys.add(req.getRouteKey().trim().toLowerCase(Locale.ROOT));
            }
            if (req.getSortNo() != null) {
                maxSort = Math.max(maxSort, req.getSortNo());
            }
        }
        List<MaCandidate> out = new ArrayList<>(list.size());
        for (MaCandidateReq req : list) {
            // 刀5：候选可以指向另一套编排方案，路由键按目标方案 code 派生
            if (req.isMaTarget()) {
                MaCandidate target = buildMaTargetCandidate(req, usedKeys, maxSort);
                maxSort = Math.max(maxSort, target.getSortNo());
                out.add(target);
                continue;
            }
            // 刀5B：候选可以指向已发布工作流，路由键按工作流 code 派生
            if (req.isWorkflowTarget()) {
                MaCandidate target = buildWorkflowTargetCandidate(req, usedKeys, maxSort);
                maxSort = Math.max(maxSort, target.getSortNo());
                out.add(target);
                continue;
            }
            AgentDef child = agents.findById(req.getAgentId())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "候选子 Agent 不存在或已删除：" + req.getAgentId()));
            String key = req.getRouteKey() == null || req.getRouteKey().isBlank()
                    ? AgentRouteDefaults.resolveKey(child.getCode(), child.getId(), null, usedKeys)
                    : req.getRouteKey().trim();
            usedKeys.add(key.toLowerCase(Locale.ROOT));
            int sortNo = req.getSortNo() == null
                    ? AgentRouteDefaults.nextSort(maxSort == 0 ? null : maxSort)
                    : req.getSortNo();
            maxSort = Math.max(maxSort, sortNo);

            MaCandidate candidate = new MaCandidate();
            candidate.setTargetType(MaCandidate.TARGET_AGENT);
            candidate.setAgentId(req.getAgentId());
            candidate.setRouteKey(key);
            // 回填 Agent 简介：老代码仍读该列时看到的是同一份文案，不会与新版提示词分叉
            String derivedIntent = clipDescription(child.getDescription());
            // 刀4 拦口：请求想把意图说明改成别的内容 → 明确失败（改文案请去 Agent 管理改简介）
            String submittedIntent = req.getIntentDesc() == null ? null : TextClip.trimmed(req.getIntentDesc(), INTENT_DESC_MAX);
            if (submittedIntent != null && !submittedIntent.isBlank() && !submittedIntent.equals(derivedIntent)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "意图说明只来自 Agent 简介，不能在多智能体方案里单独修改；"
                                + "请到 Agent 管理改「" + child.getName() + "」的简介");
            }
            candidate.setIntentDesc(derivedIntent);
            candidate.setSortNo(sortNo);
            candidate.setEnabled(req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled());
            out.add(candidate);
        }
        MaDefPolicy.assertCandidateCount(out.size());
        return out;
    }

    /**
     * MA 目标候选 → 领域候选（刀5）。所属域：agent 的 application。
     *
     * <p>路由键与排序的生成规则与 Agent 候选一致：优先目标方案 code，冲突回落 {@code ma_{id}}；
     * 意图说明列用目标方案的说明回填（列 NOT NULL，不写 null），真正判路由仍读 ma_def.description。
     *
     * <p>存在性与防循环不在这里判：统一交给 {@link MaDefPolicy#assertMaTargets}，
     * 保证「加进池」与「默认接待页加候选」两条入口口径一致。
     *
     * @param req     候选请求（maDefId 非空）
     * @param usedKeys 已占用的路由键（会被本方法追加）
     * @param maxSort  当前最大排序
     * @return 领域候选（targetType=MA）
     * @throws BizException 目标方案不存在或跨租户
     */
    private MaCandidate buildMaTargetCandidate(MaCandidateReq req, Set<String> usedKeys, int maxSort) {
        if (req.getMaDefId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "候选目标为编排方案时必须指定方案");
        }
        MaDef target = mas.findById(req.getMaDefId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "要加入的编排方案不存在或已删除：" + req.getMaDefId()));
        String key = req.getRouteKey() == null || req.getRouteKey().isBlank()
                ? MaRouteKeys.forTarget(target.getId(), target.getCode(), usedKeys)
                : req.getRouteKey().trim();
        usedKeys.add(key.toLowerCase(Locale.ROOT));
        int sortNo = req.getSortNo() == null
                ? AgentRouteDefaults.nextSort(maxSort == 0 ? null : maxSort)
                : req.getSortNo();

        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_MA);
        candidate.setMaDefId(target.getId());
        candidate.setRouteKey(key);
        candidate.setIntentDesc(clipDescription(target.getDescription()));
        candidate.setSortNo(sortNo);
        candidate.setEnabled(req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled());
        return candidate;
    }

    /**
     * 工作流目标候选 → 领域候选（刀5B）。所属域：agent 的 application。
     *
     * <p>路由键与排序的生成规则与 Agent / MA 候选一致：优先工作流 code，冲突回落
     * {@code wf_{id}}；意图说明列用工作流说明回填（列 NOT NULL，不写 null），
     * 真正判路由仍读 {@code workflow_def.description}。
     *
     * <p>存在性 / 发布 / 说明 / 模板不在这里判：统一交给
     * {@link MaDefPolicy#assertWorkflowTargets}，保证「方案保存」与「默认接待页加候选」
     * 两条入口口径一致。
     *
     * @param req      候选请求（workflowDefId 非空）
     * @param usedKeys 已占用的路由键（会被本方法追加）
     * @param maxSort  当前最大排序
     * @return 领域候选（targetType=WORKFLOW）
     * @throws BizException 目标工作流不存在或已删除
     */
    private MaCandidate buildWorkflowTargetCandidate(MaCandidateReq req, Set<String> usedKeys, int maxSort) {
        if (req.getWorkflowDefId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "候选目标为工作流时必须指定工作流");
        }
        WorkflowDef target = workflowDefs.findById(req.getWorkflowDefId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "要加入的工作流不存在或已删除：" + req.getWorkflowDefId()));
        String key = req.getRouteKey() == null || req.getRouteKey().isBlank()
                ? MaRouteKeys.forWorkflow(target.getId(), target.getCode(), usedKeys)
                : req.getRouteKey().trim();
        usedKeys.add(key.toLowerCase(Locale.ROOT));
        int sortNo = req.getSortNo() == null
                ? AgentRouteDefaults.nextSort(maxSort == 0 ? null : maxSort)
                : req.getSortNo();

        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(target.getId());
        candidate.setRouteKey(key);
        candidate.setIntentDesc(clipDescription(target.getDescription()));
        candidate.setSortNo(sortNo);
        candidate.setEnabled(req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled());
        return candidate;
    }

    /**
     * 工作流目标的校验快照：只把 agent 域需要的字段翻译过去，不把实体交给策略。
     *
     * @param workflowId workflow_def.id
     * @return 只读快照；不存在为空
     */
    private Optional<MaDefPolicy.WorkflowTargetInfo> workflowTargetInfo(Long workflowId) {
        return workflowDefs.findById(workflowId).map(def -> new MaDefPolicy.WorkflowTargetInfo(
                def.getId(), def.getTenantId(), def.getName(), def.getCode(),
                def.getDescription(), def.getStatus(), def.getDefaultReleaseId()));
    }

    /**
     * Agent 简介 → 候选的意图说明列（varchar(512)）。
     *
     * @param description Agent 简介；空表示没填
     * @return 截断到 512 的简介；没有简介返回空串（列 NOT NULL，不能写 null）
     */
    private static String clipDescription(String description) {
        return description == null ? "" : TextClip.trimmed(description, INTENT_DESC_MAX);
    }

    /**
     * 方案形象请求 → 落库 JSON。与单兵形象共用同一套 preset / custom 校验与白名单。
     *
     * @param dto 请求体；可空
     * @return JSON；未配置时 null（表示清空）
     * @throws BizException 预置键 / 色值 / 上传 URL 非法
     */
    private static String toAvatarJson(AgentAvatarConfigDto dto) {
        if (dto == null) {
            return null;
        }
        AgentAvatarConfig config = AgentAvatarConfig.of(
                dto.getType(), dto.getIcon(), dto.getColor(), dto.getUrl());
        return config == null ? null : config.toJson();
    }

    /**
     * 列表 / 详情展示用的协作模式。
     *
     * <p>入口保持 {@code ENTRY_ROUTER}。其余历史模板收成两种模式之一，
     * 这样筛选和编辑下拉不用等用户先保存才看得到新名字。库里的旧码要等下次保存才改写。
     *
     * @param stored 库内模板码
     * @return 页面应看到的模板码
     */
    private static String displayTemplate(String stored) {
        if (AgentRuntimeConfig.isEntryRouterTemplate(stored)) {
            return AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER;
        }
        String canonical = AgentRuntimeConfig.canonicalMaTemplate(stored);
        return canonical == null ? AgentRuntimeConfig.TEMPLATE_ROUTER_PARALLEL : canonical;
    }

    private MaDefRowResp toRow(MaDef def, int candidateCount) {
        MaDefRowResp row = new MaDefRowResp();
        row.setId(def.getId());
        row.setCode(def.getCode());
        // 入口展示名 / 备注由平台锁定（见 MaDef.displayName / displayDescription），历史库里的老名称在这里归一
        row.setName(def.displayName());
        row.setDescription(def.displayDescription());
        // 形象只读方案自己：未配置返回 null，前端按方案名走 8 套机器人兜底
        row.setAvatarConfig(AgentAvatarConfigDto.fromJson(def.getAvatarConfigJson()));
        row.setTemplate(displayTemplate(def.getTemplate()));
        row.setEnabled(def.getEnabled());
        // 历史绑根字段恒回 null：运行与形象都不再依赖 Agent 根，页面也不再展示绑定关系
        row.setRootAgentId(null);
        row.setRootAgentName(null);
        row.setCandidateEnabledCount(candidateCount);
        row.setVersion(def.getVersion());
        row.setUpdateTime(def.getUpdateTime());
        return row;
    }

    private MaDefDetailResp toDetail(MaDef def, List<MaCandidate> candidates) {
        MaDefDetailResp resp = new MaDefDetailResp();
        resp.setId(def.getId());
        resp.setCode(def.getCode());
        // 与列表同口径：入口的名称 / 备注永远回显平台锁定值，页面据此只读展示
        resp.setName(def.displayName());
        resp.setDescription(def.displayDescription());
        String shown = displayTemplate(def.getTemplate());
        resp.setTemplate(shown);
        AgentRuntimeConfigResp configResp = toConfigResp(
                AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name()));
        // 详情只做展示收口，不走保存拒绝：历史「路由后串行 + 隔离」回显成继承，打开抽屉不报错。
        // 库要等用户下次保存才改写；入口和意图路由分发不改策略。
        configResp.setMaTemplate(shown);
        if (AgentRuntimeConfig.TEMPLATE_ROUTER_THEN_SEQUENCE.equals(shown)) {
            configResp.setMaContextPolicy(AgentRuntimeConfig.CONTEXT_PRIOR_SUMMARY_ONLY);
        }
        resp.setConfig(configResp);
        // 方案自有形象：编辑抽屉「基础信息」回显；未配置为 null
        resp.setAvatarConfig(AgentAvatarConfigDto.fromJson(def.getAvatarConfigJson()));
        // 历史绑根字段恒回 null：页面不再展示 / 不再要求绑定 Agent 根
        resp.setRootAgentId(null);
        resp.setRootAgentName(null);
        resp.setEnabled(def.getEnabled());
        resp.setVersion(def.getVersion());
        resp.setCreateTime(def.getCreateTime());
        resp.setUpdateTime(def.getUpdateTime());

        List<Long> childIds = candidates.stream().map(MaCandidate::getAgentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AgentDef> children = new HashMap<>();
        for (AgentDef child : agents.findByIds(childIds)) {
            children.put(child.getId(), child);
        }
        // 刀5：MA 候选要回显目标方案名 / 编码 / 说明，一次批量取，避免逐行查库
        List<Long> targetIds = candidates.stream().map(MaCandidate::getMaDefId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, MaDef> targets = new HashMap<>();
        for (MaDef target : mas.findByIds(targetIds)) {
            targets.put(target.getId(), target);
        }
        // 刀5B：工作流候选同样批量取定义（仓储无批量接口，按 id 去重后逐个取）
        Map<Long, WorkflowDef> workflows = new HashMap<>();
        for (Long workflowId : candidates.stream().map(MaCandidate::getWorkflowDefId)
                .filter(Objects::nonNull).distinct().toList()) {
            workflowDefs.findById(workflowId).ifPresent(found -> workflows.put(found.getId(), found));
        }
        List<MaCandidateResp> rows = new ArrayList<>(candidates.size());
        for (MaCandidate candidate : candidates) {
            rows.add(toCandidateResp(candidate, children.get(candidate.getAgentId()),
                    targets.get(candidate.getMaDefId()), workflows.get(candidate.getWorkflowDefId())));
        }
        resp.setCandidates(rows);
        return resp;
    }

    private MaCandidateResp toCandidateResp(MaCandidate candidate, AgentDef child, MaDef target,
                                            WorkflowDef workflow) {
        MaCandidateResp resp = new MaCandidateResp();
        resp.setId(candidate.getId());
        resp.setTargetType(candidate.targetTypeOrDefault());
        resp.setAgentId(candidate.getAgentId());
        resp.setMaDefId(candidate.getMaDefId());
        resp.setWorkflowDefId(candidate.getWorkflowDefId());
        if (child != null) {
            resp.setAgentName(child.getName());
            resp.setAgentCode(child.getCode());
            resp.setAgentRunMode(child.getRunMode());
            resp.setAgentStatus(child.getStatus());
            resp.setAgentAllowOrchestrated(child.allowOrchestratedOn() ? 1 : 0);
        }
        if (target != null) {
            resp.setMaName(target.getName());
            resp.setMaCode(target.getCode());
            resp.setMaTemplate(target.getTemplate());
            resp.setMaStatus(target.getEnabled());
        }
        if (workflow != null) {
            resp.setWorkflowName(workflow.getName());
            resp.setWorkflowCode(workflow.getCode());
            // 「线路是否已失效」：取消发布或默认发布指针被清掉都算不可运行
            resp.setWorkflowHasRelease("HAS_RELEASE".equals(workflow.getStatus())
                    && workflow.getDefaultReleaseId() != null);
        }
        // 意图说明单一真源：AGENT 回显 live 的 Agent 简介，MA 回显方案说明，WORKFLOW 回显工作流说明；
        // 为空时明确标出来，页面据此提示去对应管理页补
        String description;
        if (candidate.isWorkflowTarget()) {
            description = workflow == null ? null : TextClip.trimmed(workflow.getDescription(), INTENT_DESC_MAX);
        } else if (candidate.isMaTarget()) {
            description = target == null ? null : TextClip.trimmed(target.getDescription(), INTENT_DESC_MAX);
        } else {
            description = child == null ? null : TextClip.trimmed(child.getDescription(), INTENT_DESC_MAX);
        }
        resp.setIntentDesc(description);
        resp.setIntentDescMissing(description == null || description.isBlank());
        resp.setRouteKey(candidate.getRouteKey());
        resp.setSortNo(candidate.getSortNo());
        resp.setEnabled(candidate.getEnabled());
        return resp;
    }

    private AgentRuntimeConfigResp toConfigResp(AgentRuntimeConfig config) {
        AgentRuntimeConfigResp resp = new AgentRuntimeConfigResp();
        resp.setMaTemplate(config.maTemplateOrDefault());
        resp.setMaContextPolicy(config.maContextPolicyOrDefault());
        resp.setMaAllowParentHistoryWhenSingle(config.allowParentHistoryWhenSingleOn());
        resp.setMultiIntentEnabled(config.multiIntentOn());
        resp.setAnswerMode(config.answerModeEnum().name());
        resp.setMaxRouteChildren(config.maxRouteChildrenOrDefault());
        resp.setRequirePlanConfirm(config.requirePlanConfirmOn());
        resp.setMaxPlanSteps(config.maxPlanStepsOrDefault());
        resp.setMaxStepRetries(config.maxStepRetriesOrDefault());
        resp.setMaChildRunModesAllowed(config.childRunModesAllowedOrDefault());
        resp.setMaCriticMaxRounds(config.criticMaxRoundsOrDefault());
        resp.setMaCriticAgentId(config.getMaCriticAgentId());
        resp.setMaJudgeModelInstanceId(config.getMaJudgeModelInstanceId());
        resp.setMaJudgeModel(config.getMaJudgeModel());
        // ROUTER 提示词回显：与专家配提示词同一套转换（含 supported 与 override 解析），页面直接绑定弹窗
        PromptBindingResp prompt = prompts.toResp(PURPOSE_ROUTER, config.getMaJudgePromptTemplateId(),
                config.getMaJudgePromptTemplateVersion(), config.getMaJudgePromptOverrideJson(),
                config.getMaJudgePromptExtra());
        resp.setMaJudgePromptTemplateId(prompt.getTemplateId());
        resp.setMaJudgePromptTemplateVersion(prompt.getTemplateVersion());
        resp.setMaJudgePromptOverride(prompt.getOverride());
        resp.setMaJudgePromptExtra(prompt.getPromptExtra());
        resp.setCompeteStrategy(config.competeStrategyOrDefault());
        return resp;
    }

    private int countEnabled(List<MaCandidate> candidates) {
        int count = 0;
        for (MaCandidate candidate : candidates) {
            if (candidate.getEnabled() != null && candidate.getEnabled() == AgentStatus.ENABLED) {
                count++;
            }
        }
        return count;
    }

    private String requiredText(String raw, int max, String field) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + "不能为空");
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + "最长 " + max + " 字符");
        }
        return text;
    }
}
