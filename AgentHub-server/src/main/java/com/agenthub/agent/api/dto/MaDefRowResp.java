package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体方案列表行。所属域：agent。
 *
 * <p>列表一行 = 一个 ma_def；绑定根名称由 agent_def 批量补齐，
 * 虚拟根显示为「虚拟根」，前端据此禁用试跑。
 */
@Data
public class MaDefRowResp {

    private Long id;
    /** 方案编码，租户内唯一 */
    private String code;
    private String name;
    /** 五值模板之一 */
    private String template;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    /** 绑定的 ROUTER 主键；空＝虚拟根 */
    private Long rootAgentId;
    /** 绑定的 ROUTER 名称；虚拟根为空 */
    private String rootAgentName;
    /** 启用中的候选数 */
    private Integer candidateEnabledCount;
    /** 方案版本，每次保存 +1 */
    private Integer version;
    private LocalDateTime updateTime;
}
