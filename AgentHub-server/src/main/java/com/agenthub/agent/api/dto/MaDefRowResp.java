package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体方案列表行。所属域：agent。
 *
 * <p>列表一行 = 一个 ma_def；方案形象读方案自己的 {@code avatar_config}，
 * 未配置时返回 null，由前端按方案名走 8 套机器人兜底。
 */
@Data
public class MaDefRowResp {

    private Long id;
    /** 方案编码，租户内唯一 */
    private String code;
    private String name;
    /**
     * 方案说明（刀5）：被别的方案当作路由目标时，它就是判路由提示词里的职责简介。
     * 为空表示还没写说明，此时不能被加入任何路由池。
     */
    private String description;
    /** 方案自有形象；null＝未配置（前端兜底），不再读任何 Agent 的形象 */
    private AgentAvatarConfigDto avatarConfig;
    /** 五值模板之一 */
    private String template;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    /** 历史绑根字段：已停用，恒为 null（列保留不 DROP） */
    private Long rootAgentId;
    /** 历史绑根名称：已停用，恒为 null */
    private String rootAgentName;
    /** 启用中的候选数 */
    private Integer candidateEnabledCount;
    /** 方案版本，每次保存 +1 */
    private Integer version;
    private LocalDateTime updateTime;
}
