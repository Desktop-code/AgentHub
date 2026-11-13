package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体方案候选。所属域：agent。对应 ma_candidate。
 *
 * <p>语义对齐现网 {@link AgentRoute}：一条候选＝「方案 → 子 Agent / 另一套方案」的一层关系，
 * route_key 在本方案内唯一，sort_no 决定串行顺序。真正的子 Agent 校验
 * （非 ROUTER、已启用、允许被编排、子模式获准）在
 * {@link com.agenthub.agent.domain.service.MaDefPolicy} 统一做。
 *
 * <p><b>刀5 目标类型</b>：{@code target_type=AGENT} 走原有子 Agent 链路；
 * {@code target_type=MA} 指向另一套 {@code ma_def}，命中后在同一条会话内按目标方案执行，
 * 不得挂自己、不得互挂成环，也不得回指默认接待。嵌套深度由运行时上限拦住。
 *
 * <p><b>刀5B 目标类型</b>：{@code target_type=WORKFLOW} 指向已发布的 {@code workflow_def}，
 * 命中后仍在同一条工作台会话里看结果，但执行走现网 {@code workflow_run} 引擎（不并进对话栈）。
 * 工作流候选只允许出现在会判路由的方案里，且目标 MA 仍不得再挂工作流（只下一层）。
 */
@Data
public class MaCandidate {

    /** 目标类型：子 Agent（存量语义，默认值） */
    public static final String TARGET_AGENT = "AGENT";
    /** 目标类型：另一套多智能体方案（刀5） */
    public static final String TARGET_MA = "MA";
    /** 目标类型：已发布工作流（刀5B） */
    public static final String TARGET_WORKFLOW = "WORKFLOW";

    private Long id;
    /** 所属方案 ma_def.id */
    private Long maId;
    /** 目标类型：{@link #TARGET_AGENT} / {@link #TARGET_MA} / {@link #TARGET_WORKFLOW}；库里是 NOT NULL，默认 AGENT */
    private String targetType;
    /** 子 Agent；target_type=AGENT 时必填，不能是 ROUTER */
    private Long agentId;
    /**
     * 目标方案 ma_def.id；target_type=MA 时必填。
     *
     * <p>刀5：默认接待的路由池除了子 Agent，还能挂另一套编排方案；命中后在同一条工作台会话里
     * 按目标方案自己的模板执行（嵌套有上限，不得再派回网关 / 另一个默认接待）。
     */
    private Long maDefId;
    /**
     * 目标工作流 workflow_def.id；target_type=WORKFLOW 时必填。
     *
     * <p>刀5B：只绑该工作流的默认发布指针（禁跑草稿）；意图说明读
     * {@code workflow_def.description}，候选上不再另编一份。
     */
    private Long workflowDefId;
    /** 方案内唯一路由键，判路由与串行顺序提示都用它 */
    private String routeKey;
    /** 意图描述（给判路由 LLM 看的中文说明） */
    private String intentDesc;
    /** 排序：数值小的在前；并行模板用于稳定展示顺序，串行模板决定执行顺序 */
    private Integer sortNo;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * @return true 表示本条候选指向另一套编排方案（MA 目标）
     */
    public boolean isMaTarget() {
        return TARGET_MA.equalsIgnoreCase(targetType);
    }

    /**
     * @return true 表示本条候选指向已发布工作流（WORKFLOW 目标）
     */
    public boolean isWorkflowTarget() {
        return TARGET_WORKFLOW.equalsIgnoreCase(targetType);
    }

    /**
     * 归一化后的目标类型：只输出三个合法值，脏数据按 AGENT 处理。
     *
     * <p>写库与回显都用它，避免各处各写一份三元表达式导致「WORKFLOW 被显示成 AGENT」。
     *
     * @return AGENT / MA / WORKFLOW
     */
    public String targetTypeOrDefault() {
        if (isMaTarget()) {
            return TARGET_MA;
        }
        if (isWorkflowTarget()) {
            return TARGET_WORKFLOW;
        }
        return TARGET_AGENT;
    }

    /**
     * 目标主键：MA 目标返回 maDefId，工作流目标返回 workflowDefId，Agent 目标返回 agentId。
     *
     * @return 目标主键；都没填时为空
     */
    public Long targetRefId() {
        if (isMaTarget()) {
            return maDefId;
        }
        if (isWorkflowTarget()) {
            return workflowDefId;
        }
        return agentId;
    }
}
