package com.agenthub.runtime.api.dto;

import lombok.Data;

/**
 * 试跑页可选的已启用 Agent。所属域：runtime。不含系统提示词。
 */
@Data
public class AgentOptionResp {

    private Long id;
    private String name;
    private String code;
    private String runMode;
}
