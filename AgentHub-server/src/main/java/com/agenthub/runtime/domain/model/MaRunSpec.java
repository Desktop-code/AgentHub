package com.agenthub.runtime.domain.model;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.model.MaDef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一次编排试跑的执行来源（ma_def 方案）。所属域：runtime。
 *
 * <p><b>方案就是自己的根</b>：模板 / 上下文策略 / 候选池 / 判路由模型 / 形象全部来自 {@code ma_def}，
 * 不再有「绑根」与「虚拟根」两套执行口径，也不在运行期合成 Agent 壳。
 * {@code rootAgentId} 仍随记录带出（列未 DROP），但执行器与形象取值都不再读它。
 *
 * <p>本对象只在应用层与执行器之间传递，不落库；候选已按启用状态过滤，读取时不再查方案表，
 * 因此同一轮里「改方案候选」只影响下一轮试跑，不会让一轮执行中途换子。
 *
 * <p><b>刀5 目标方案</b>：{@code maTargets} 存放「本方案的 MA 候选 → 目标方案的执行来源」，
 * 键是目标 {@code ma_def.id}。<b>只装一层</b>：目标方案自身的 MA 候选在装载时就被剔除，
 * 执行器再遇到 MA 候选也没有下一层可查，因此不可能出现网关递归或回指默认接待。
 *
 * <p><b>刀5B 工作流目标</b>：{@code workflowTargets} 存放「本方案的 WORKFLOW 候选 →
 * 目标工作流的只读执行来源」，键是 {@code workflow_def.id}。与 MA 目标同样只装一层，
 * 目标工作流不参与本方案的路由池（工作流没有候选池）。
 *
 * @param maId                  ma_def.id
 * @param code                  方案编码（时间线 / obs 展示）
 * @param name                  方案名称（会话标题、obs 根步骤名）
 * @param description           方案说明（被当作路由目标时喂给判路由的职责简介）；可空
 * @param template              五值模板之一；执行时覆盖 config 内的同名字段
 * @param runtime               已归一化的编排配置
 * @param candidates            启用中的候选，sort_no 小的在前
 * @param rootAgentId           历史绑根值；已停用，执行与形象都不读它
 * @param judgeModelInstanceId  判路由 / 汇总模型实例；null 表示由工作台回落平台槽
 * @param receptionDefault      true 表示本方案是平台默认接待：判路由提示词按接待口径组装
 * @param maTargets             目标方案执行来源（键＝目标 ma_def.id）；只一层，可空
 * @param workflowTargets       目标工作流执行来源（键＝workflow_def.id）；只一层，可空
 * @param avatarConfigJson      方案自有形象 JSON；空＝未配置（前端按方案名兜底）
 */
public record MaRunSpec(Long maId, String code, String name, String description, String template,
                        AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                        Long rootAgentId, Long judgeModelInstanceId, boolean receptionDefault,
                        Map<Long, MaRunSpec> maTargets, Map<Long, WorkflowRunSpec> workflowTargets,
                        String avatarConfigJson) {

    /** 空表常量：避免每处判空都新建 Map。 */
    private static final Map<Long, MaRunSpec> NO_TARGETS = Map.of();
    /** 工作流目标空表常量。 */
    private static final Map<Long, WorkflowRunSpec> NO_WORKFLOWS = Map.of();

    /** 目标表与工作流表防御性归一：null → 空表，且不再允许调用方改这份 Map。 */
    public MaRunSpec {
        maTargets = maTargets == null ? NO_TARGETS : Map.copyOf(maTargets);
        workflowTargets = workflowTargets == null ? NO_WORKFLOWS : Map.copyOf(workflowTargets);
    }

    /**
     * 兼容构造：没有目标方案（普通方案 / 目标方案自身）。
     *
     * @param maId                 ma_def.id
     * @param code                 方案编码
     * @param name                 方案名称
     * @param description          方案说明
     * @param template             模板码
     * @param runtime              编排配置
     * @param candidates           启用中的候选
     * @param rootAgentId          历史绑根值；已停用，执行与形象都不读它
     * @param judgeModelInstanceId 判路由模型实例
     * @param receptionDefault     是否默认接待
     */
    public MaRunSpec(Long maId, String code, String name, String description, String template,
                     AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                     Long rootAgentId, Long judgeModelInstanceId, boolean receptionDefault) {
        this(maId, code, name, description, template, runtime, candidates, rootAgentId,
                judgeModelInstanceId, receptionDefault, NO_TARGETS, NO_WORKFLOWS, null);
    }

    /**
     * 兼容构造：无说明、无目标方案（大量窄测直接 new 本对象）。
     *
     * @param maId                 ma_def.id
     * @param code                 方案编码
     * @param name                 方案名称
     * @param template             模板码
     * @param runtime              编排配置
     * @param candidates           启用中的候选
     * @param rootAgentId          历史绑根值；已停用，执行与形象都不读它
     * @param judgeModelInstanceId 判路由模型实例
     * @param receptionDefault     是否默认接待
     */
    public MaRunSpec(Long maId, String code, String name, String template,
                     AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                     Long rootAgentId, Long judgeModelInstanceId, boolean receptionDefault) {
        this(maId, code, name, null, template, runtime, candidates, rootAgentId,
                judgeModelInstanceId, receptionDefault, NO_TARGETS, NO_WORKFLOWS, null);
    }

    /**
     * 兼容构造：不显式声明是否默认接待（按「不是」处理）。
     *
     * <p>保留它是因为大量窄测直接 new 本对象；生产路径请用 {@link #of}，由方案标记决定。
     *
     * @param maId                 ma_def.id
     * @param code                 方案编码
     * @param name                 方案名称
     * @param template             模板码
     * @param runtime              编排配置
     * @param candidates           启用中的候选
     * @param rootAgentId          历史绑根值；已停用，执行与形象都不读它
     * @param judgeModelInstanceId 判路由模型实例
     */
    public MaRunSpec(Long maId, String code, String name, String template,
                     AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                     Long rootAgentId, Long judgeModelInstanceId) {
        this(maId, code, name, null, template, runtime, candidates, rootAgentId,
                judgeModelInstanceId, false, NO_TARGETS, NO_WORKFLOWS, null);
    }

    /**
     * 兼容构造：候选 / 目标表齐全但没有方案自有形象（多数窄测直接用它）。
     *
     * @param maId                 ma_def.id
     * @param code                 方案编码
     * @param name                 方案名称
     * @param description          方案说明
     * @param template             模板码
     * @param runtime              编排配置
     * @param candidates           启用中的候选
     * @param rootAgentId          历史绑根值；已停用，执行与形象都不读它
     * @param judgeModelInstanceId 判路由模型实例
     * @param receptionDefault     是否默认接待
     * @param maTargets            目标方案执行来源；可空
     * @param workflowTargets      目标工作流执行来源；可空
     */
    public MaRunSpec(Long maId, String code, String name, String description, String template,
                     AgentRuntimeConfig runtime, List<MaCandidate> candidates,
                     Long rootAgentId, Long judgeModelInstanceId, boolean receptionDefault,
                     Map<Long, MaRunSpec> maTargets, Map<Long, WorkflowRunSpec> workflowTargets) {
        this(maId, code, name, description, template, runtime, candidates, rootAgentId,
                judgeModelInstanceId, receptionDefault, maTargets, workflowTargets, null);
    }

    /**
     * 从方案与候选构造。
     *
     * @param def        方案定义
     * @param candidates 启用中的候选（可空）
     * @param judgeModel 已解析的判路由模型实例；null 表示交给绑根兜底
     * @param runtime    已归一化的配置（模板已按 {@code def.template} 覆盖）
     * @return 执行来源
     */
    public static MaRunSpec of(MaDef def, List<MaCandidate> candidates, Long judgeModel,
                               AgentRuntimeConfig runtime) {
        return of(def, candidates, judgeModel, runtime, NO_TARGETS, NO_WORKFLOWS);
    }

    /**
     * 从方案、候选与目标方案表构造（刀5）。工作流目标为空。
     *
     * @param def        方案定义
     * @param candidates 启用中的候选（可空）
     * @param judgeModel 已解析的判路由模型实例；null 表示未解析到，执行期判路由走关键词兜底
     * @param runtime    已归一化的配置
     * @param maTargets  目标方案执行来源（键＝目标 ma_def.id）；可空
     * @return 执行来源
     */
    public static MaRunSpec of(MaDef def, List<MaCandidate> candidates, Long judgeModel,
                               AgentRuntimeConfig runtime, Map<Long, MaRunSpec> maTargets) {
        return of(def, candidates, judgeModel, runtime, maTargets, NO_WORKFLOWS);
    }

    /**
     * 从方案、候选、目标方案表与工作流目标表构造（刀5B）。
     *
     * @param def             方案定义
     * @param candidates      启用中的候选（可空）
     * @param judgeModel      已解析的判路由模型实例；null 表示未解析到，执行期判路由走关键词兜底
     * @param runtime         已归一化的配置
     * @param maTargets       目标方案执行来源（键＝目标 ma_def.id）；可空
     * @param workflowTargets 目标工作流执行来源（键＝workflow_def.id）；可空
     * @return 执行来源
     */
    public static MaRunSpec of(MaDef def, List<MaCandidate> candidates, Long judgeModel,
                               AgentRuntimeConfig runtime, Map<Long, MaRunSpec> maTargets,
                               Map<Long, WorkflowRunSpec> workflowTargets) {
        boolean reception = def.getIsReceptionDefault() != null && def.getIsReceptionDefault() == 1;
        // 名称 / 说明走 displayName / displayDescription：入口在提示词与会话标题里也统一叫「Router」
        return new MaRunSpec(def.getId(), def.getCode(), def.displayName(), def.displayDescription(),
                def.getTemplate(),
                runtime, candidates == null ? List.of() : List.copyOf(candidates),
                def.getRootAgentId(), judgeModel, reception, maTargets, workflowTargets,
                def.getAvatarConfigJson());
    }

    /**
     * 换一个判路由模型实例，其余字段原样保留（含目标方案表）。
     *
     * <p>用途：工作台会话在方案未配判路由模型时，把解析到的平台 ROUTER / CHAT 实例
     * 补进本轮执行来源；不改库、不改方案配置，只影响这一轮。
     *
     * @param judgeModel 平台回落的模型实例 id；null 表示不覆盖
     * @return 新执行来源；judgeModel 为 null 时返回自身
     */
    public MaRunSpec withJudgeModel(Long judgeModel) {
        if (judgeModel == null) {
            return this;
        }
        return new MaRunSpec(maId, code, name, description, template, runtime, candidates, rootAgentId,
                judgeModel, receptionDefault, maTargets, workflowTargets, avatarConfigJson);
    }

    /**
     * @param targetMaId 目标方案主键
     * @return 目标方案的执行来源；未装载（已停用 / 已删除 / 超出深度）时为空
     */
    public Optional<MaRunSpec> maTarget(Long targetMaId) {
        if (targetMaId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(maTargets.get(targetMaId));
    }

    /**
     * 工作流目标的执行来源（刀5B）。
     *
     * @param workflowDefId workflow_def.id
     * @return 目标工作流快照；未装载（未发布 / 已删除 / 空说明）时为空
     */
    public Optional<WorkflowRunSpec> workflowTarget(Long workflowDefId) {
        if (workflowDefId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowTargets.get(workflowDefId));
    }

    /**
     * 排在第一位的候选（sort_no 最小者）。
     *
     * <p>默认接待里它就是「接待助手」：判路由派不出合适专家时统一回落到它。
     *
     * @return 首位候选；方案没有候选时为空
     */
    public Optional<MaCandidate> firstCandidate() {
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * 首位「子 Agent」候选（跳过另一套编排方案与工作流）。
     *
     * <p>为什么与 {@link #firstCandidate()} 分开：默认接待的接待助手要承担人设与寒暄兜底，
     * 人设只配在 Agent 的系统提示词上；把 MA / 工作流目标当兜底会让一句「你好」也去跑子编排或流程。
     * 因此刀5 / 刀5B 的非 Agent 候选即使排第一，也不接管兜底位。
     *
     * @return 首个 AGENT 候选；池里只有 MA / 工作流候选时为空
     */
    public Optional<MaCandidate> firstAgentCandidate() {
        return candidates.stream()
                .filter(item -> !item.isMaTarget() && !item.isWorkflowTarget())
                .findFirst();
    }

    /**
     * 时间线 / obs 用的方案标签：编码优先，缺失回退名称。
     *
     * @return 非空短标签
     */
    public String label() {
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return name == null ? "" : name.trim();
    }
}
