package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体方案候选。所属域：agent。对应 ma_candidate。
 *
 * <p>语义对齐现网 {@link AgentRoute}：一条候选＝「方案 → 子 Agent」的一层关系，
 * route_key 在本方案内唯一，sort_no 决定串行顺序。真正的子 Agent 校验
 * （非 ROUTER、已启用、允许被编排、子模式获准）在
 * {@link com.agenthub.agent.domain.service.MaDefPolicy} 统一做。
 */
@Data
public class MaCandidate {

    private Long id;
    /** 所属方案 ma_def.id */
    private Long maId;
    /** 子 Agent；不能是 ROUTER */
    private Long agentId;
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
}
