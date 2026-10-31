package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 工具绑定行。所属域：agent。来源名来自工具源，不带鉴权信息。
 */
@Data
public class ToolBindResp {

    private Long id;
    private Long agentId;
    private Long toolItemId;
    private String name;
    private String title;
    private String sourceName;
    private Integer status;
    /** 工具项当前启停；项已删时为空 */
    private Integer targetStatus;
    /** ROUTER 绑得过多时的保存警告。超硬顶不会到这里，接口会直接拒绝 */
    private String warning;
}
