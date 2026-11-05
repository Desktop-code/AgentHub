package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentRuntimeConfigReq;
import com.agenthub.agent.api.dto.AgentRuntimeConfigResp;
import com.agenthub.agent.api.dto.MaCandidateReq;
import com.agenthub.agent.api.dto.MaCandidateResp;
import com.agenthub.agent.api.dto.MaDefDetailResp;
import com.agenthub.agent.api.dto.MaDefPageReq;
import com.agenthub.agent.api.dto.MaDefRowResp;
import com.agenthub.agent.api.dto.MaDefSaveReq;
import com.agenthub.agent.domain.model.AgentDef;
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
import com.agenthub.agent.domain.service.MaTenantProvider;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private final MaDefRepository mas;
    private final AgentDefRepository agents;
    private final MaTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param mas             方案仓储
     * @param agents          Agent 定义仓储
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param operLogWriter   操作日志（只写摘要，不写完整配置）
     */
    public MaDefAppService(MaDefRepository mas, AgentDefRepository agents,
                           MaTenantProvider tenantProvider, OperatorContext operatorContext,
                           OperLogWriter operLogWriter) {
        this.mas = mas;
        this.agents = agents;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 分页查询当前租户的方案。keyword 匹配名称/编码，可筛启停、模板与是否绑根。
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
        query.setHasRoot(req.getHasRoot());
        PageResult<MaDef> page = mas.page(query);

        List<Long> ids = page.getRecords().stream().map(MaDef::getId).toList();
        Map<Long, Integer> counts = mas.countEnabledCandidates(ids);
        Map<Long, AgentDef> roots = loadRoots(page.getRecords());
        List<MaDefRowResp> rows = new ArrayList<>(page.getRecords().size());
        for (MaDef def : page.getRecords()) {
            rows.add(toRow(def, roots.get(def.getRootAgentId()),
                    counts.getOrDefault(def.getId(), 0)));
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
     * 查某个 ROUTER 当前绑定的启用方案；旧「编排」Tab 用它提示改由方案页维护。
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
        AgentDef root = agents.findById(def.getRootAgentId()).orElse(null);
        Map<Long, Integer> counts = mas.countEnabledCandidates(List.of(def.getId()));
        return toRow(def, root, counts.getOrDefault(def.getId(), 0));
    }

    /**
     * 新建方案（候选可嵌在请求里整份保存）。
     *
     * @param req 方案、配置与候选
     * @return 新建后的列表行
     * @throws BizException 编码重复、模板非法、绑根不合法、候选不合法
     */
    @Transactional
    public MaDefRowResp create(MaDefSaveReq req) {
        long tenantId = tenantProvider.currentTenantId();
        Long operatorId = operatorContext.requireUserId();
        String code = requiredText(req.getCode(), 64, "方案编码");
        if (mas.findByTenantAndCode(tenantId, code).isPresent()) {
            throw new BizException(ErrorCode.DUPLICATE, "方案编码已存在：" + code);
        }
        String template = req.getTemplate().trim();
        MaDefPolicy.assertTemplate(template);
        AgentRuntimeConfig config = mergeConfig(req.getConfig(), template, null);
        String configJson = AgentRuntimeConfig.toStorageJson(config, AgentRunMode.ROUTER.name());

        AgentDef root = loadRoot(req.getRootAgentId());
        MaDefPolicy.assertRootBindable(root, req.getRootAgentId());
        int enabled = req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled();
        assertRootUnique(tenantId, root, enabled, null);

        List<MaCandidate> candidates = buildCandidates(req.getCandidates());
        MaDefPolicy.assertCandidates(config, req.getRootAgentId(), candidates, agents::findById);
        MaDefPolicy.assertCriticRoles(config, candidates, agents::findById);
        // 竞争选优：启用候选不足 2 个在保存时就拦，避免运行期才发现「只有 1 个选手」
        MaDefPolicy.assertCompeteCandidates(config, candidates);

        MaDef def = new MaDef();
        def.setTenantId(tenantId);
        def.setCode(code);
        def.setName(requiredText(req.getName(), 128, "方案名称"));
        def.setTemplate(template);
        def.setConfigJson(configJson);
        def.setRootAgentId(req.getRootAgentId());
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
        return toRow(saved, root, countEnabled(candidates));
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
        MaDefPolicy.assertTemplate(template);
        AgentRuntimeConfig config = mergeConfig(req.getConfig(), template, existing.getConfigJson());
        String configJson = AgentRuntimeConfig.toStorageJson(config, AgentRunMode.ROUTER.name());

        AgentDef root = loadRoot(req.getRootAgentId());
        MaDefPolicy.assertRootBindable(root, req.getRootAgentId());
        int enabled = req.getEnabled() == null
                ? (existing.getEnabled() == null ? AgentStatus.ENABLED : existing.getEnabled())
                : req.getEnabled();
        assertRootUnique(tenantId, root, enabled, id);

        List<MaCandidate> candidates = buildCandidates(req.getCandidates());
        MaDefPolicy.assertCandidates(config, req.getRootAgentId(), candidates, agents::findById);
        MaDefPolicy.assertCriticRoles(config, candidates, agents::findById);
        // 更新同样拦：把候选停到只剩 1 个时不允许保存成 COMPETE
        MaDefPolicy.assertCompeteCandidates(config, candidates);

        existing.setCode(code);
        existing.setName(requiredText(req.getName(), 128, "方案名称"));
        existing.setTemplate(template);
        existing.setConfigJson(configJson);
        existing.setRootAgentId(req.getRootAgentId());
        existing.setEnabled(enabled);
        mas.update(existing, operatorId);
        for (MaCandidate candidate : candidates) {
            candidate.setMaId(id);
        }
        mas.replaceCandidates(id, candidates);

        MaDef saved = mas.findById(id).orElseThrow();
        operLogWriter.write("agent", "ma_update", "ma_def", String.valueOf(id),
                "更新多智能体方案 code=" + code + " template=" + template);
        return toRow(saved, root, countEnabled(candidates));
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
        Long operatorId = operatorContext.requireUserId();
        if (enabled == AgentStatus.ENABLED) {
            AgentDef root = loadRoot(existing.getRootAgentId());
            assertRootUnique(existing.getTenantId(), root, AgentStatus.ENABLED, id);
        }
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
     * 绑根：null 走虚拟根；非空必须存在。
     */
    private AgentDef loadRoot(Long rootAgentId) {
        if (rootAgentId == null) {
            return null;
        }
        return agents.findById(rootAgentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "绑定的编排根不存在或已删除"));
    }

    /**
     * 启用状态下同一绑根只允许一条方案；虚拟根不参与。
     */
    private void assertRootUnique(long tenantId, AgentDef root, int enabled, Long excludeId) {
        if (root == null || enabled != AgentStatus.ENABLED) {
            return;
        }
        MaDefPolicy.assertRootUnique(mas.findEnabledByRoot(tenantId, root.getId(), excludeId));
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
            // 竞争选优策略：只落配置，执行侧按模板=COMPETE 时读取
            config.setCompeteStrategy(req.getCompeteStrategy());
        }
        return config;
    }

    /**
     * 请求候选 → 领域候选：补 route_key / sort_no 后再交给策略校验。
     * route_key 生成规则与旧 Tab 一致：优先子 Agent code，冲突回落 child_{id}。
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
            candidate.setAgentId(req.getAgentId());
            candidate.setRouteKey(key);
            candidate.setIntentDesc(req.getIntentDesc() == null ? null : req.getIntentDesc().trim());
            candidate.setSortNo(sortNo);
            candidate.setEnabled(req.getEnabled() == null ? AgentStatus.ENABLED : req.getEnabled());
            out.add(candidate);
        }
        MaDefPolicy.assertCandidateCount(out.size());
        return out;
    }

    /**
     * 批量补齐列表的绑定根名称；虚拟根不查库。
     */
    private Map<Long, AgentDef> loadRoots(List<MaDef> defs) {
        List<Long> rootIds = defs.stream()
                .map(MaDef::getRootAgentId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (rootIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AgentDef> roots = new HashMap<>();
        for (AgentDef agent : agents.findByIds(rootIds)) {
            roots.put(agent.getId(), agent);
        }
        return roots;
    }

    private MaDefRowResp toRow(MaDef def, AgentDef root, int candidateCount) {
        MaDefRowResp row = new MaDefRowResp();
        row.setId(def.getId());
        row.setCode(def.getCode());
        row.setName(def.getName());
        row.setTemplate(def.getTemplate());
        row.setEnabled(def.getEnabled());
        row.setRootAgentId(def.getRootAgentId());
        row.setRootAgentName(root == null ? null : root.getName());
        row.setCandidateEnabledCount(candidateCount);
        row.setVersion(def.getVersion());
        row.setUpdateTime(def.getUpdateTime());
        return row;
    }

    private MaDefDetailResp toDetail(MaDef def, List<MaCandidate> candidates) {
        MaDefDetailResp resp = new MaDefDetailResp();
        resp.setId(def.getId());
        resp.setCode(def.getCode());
        resp.setName(def.getName());
        resp.setTemplate(def.getTemplate());
        resp.setConfig(toConfigResp(AgentRuntimeConfig.parse(def.getConfigJson(), AgentRunMode.ROUTER.name())));
        resp.setRootAgentId(def.getRootAgentId());
        AgentDef root = def.getRootAgentId() == null ? null : agents.findById(def.getRootAgentId()).orElse(null);
        resp.setRootAgentName(root == null ? null : root.getName());
        resp.setEnabled(def.getEnabled());
        resp.setVersion(def.getVersion());
        resp.setCreateTime(def.getCreateTime());
        resp.setUpdateTime(def.getUpdateTime());

        List<Long> childIds = candidates.stream().map(MaCandidate::getAgentId).distinct().toList();
        Map<Long, AgentDef> children = new HashMap<>();
        for (AgentDef child : agents.findByIds(childIds)) {
            children.put(child.getId(), child);
        }
        List<MaCandidateResp> rows = new ArrayList<>(candidates.size());
        for (MaCandidate candidate : candidates) {
            rows.add(toCandidateResp(candidate, children.get(candidate.getAgentId())));
        }
        resp.setCandidates(rows);
        return resp;
    }

    private MaCandidateResp toCandidateResp(MaCandidate candidate, AgentDef child) {
        MaCandidateResp resp = new MaCandidateResp();
        resp.setId(candidate.getId());
        resp.setAgentId(candidate.getAgentId());
        if (child != null) {
            resp.setAgentName(child.getName());
            resp.setAgentCode(child.getCode());
            resp.setAgentRunMode(child.getRunMode());
            resp.setAgentStatus(child.getStatus());
            resp.setAgentAllowOrchestrated(child.allowOrchestratedOn() ? 1 : 0);
        }
        resp.setRouteKey(candidate.getRouteKey());
        resp.setIntentDesc(candidate.getIntentDesc());
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
