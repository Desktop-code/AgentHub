package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 列表行。所属域：agent。不含系统提示词。
 */
@Data
public class AgentCardResp {

    private Long id;
    private String name;
    private String code;
    private String description;
    /** 形象配置；null 表示未配置 */
    private AgentAvatarConfigDto avatarConfig;
    private String runMode;
    /**
     * 是否允许被编排挂为子专家。编排「添加候选」弹层据此过滤私有 Agent；
     * ROUTER 恒为 false，列表里不会出现可被挂的 ROUTER。
     */
    private Boolean allowOrchestrated;
    private Integer status;
    private LocalDateTime updateTime;
}
