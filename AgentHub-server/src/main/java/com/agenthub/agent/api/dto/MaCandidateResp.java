package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 方案候选行。所属域：agent。
 *
 * <p>比 ma_candidate 多带子 Agent 的名称、编码、运行模式与启停/编排开关，
 * 让前端编辑抽屉不必为每个候选再查一次 Agent。
 */
@Data
public class MaCandidateResp {

    private Long id;
    private Long agentId;
    private String agentName;
    private String agentCode;
    /** REACT / PLAN_AND_EXECUTE */
    private String agentRunMode;
    /** 子 Agent 自身启停 */
    private Integer agentStatus;
    /** 子 Agent 是否允许被编排；0=禁止，前端提前标红 */
    private Integer agentAllowOrchestrated;
    private String routeKey;
    private String intentDesc;
    private Integer sortNo;
    private Integer enabled;
}
