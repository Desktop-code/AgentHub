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
    private String runMode;
    private Integer status;
    private LocalDateTime updateTime;
}
