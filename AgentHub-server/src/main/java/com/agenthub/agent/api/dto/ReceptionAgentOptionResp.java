package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 可加入默认接待的 Agent 选项。所属域：agent。
 *
 * <p>只包含现网合法子类型（REACT / PLAN_AND_EXECUTE、启用、允许被编排），
 * 且已剔除被任一套多智能体方案占用的 Agent。
 *
 * <p>{@code descriptionMissing=true} 表示该 Agent 还没有简介：仍然列出来，
 * 但加不进路由池（判路由要用简介），前端需明确标出。
 */
@Data
public class ReceptionAgentOptionResp {

    private Long id;
    private String name;
    private String code;
    private String runMode;
    /** Agent 简介：加进池子后就是这条线路的意图说明 */
    private String description;
    /** true 表示还没填简介，添加会被拒绝 */
    private boolean descriptionMissing;
}
