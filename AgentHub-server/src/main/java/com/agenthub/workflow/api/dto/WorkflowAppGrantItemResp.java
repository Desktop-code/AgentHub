package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用-工作流授权行。所属域：workflow 的 api。
 * <p>同时给「应用详情 → 工作流授权」和「工作流列表 → 应用触发」两处展示：
 * 前者勾选绑定，后者说明当前已授权几个应用。
 */
@Data
public class WorkflowAppGrantItemResp {

    /** workflow_def.id */
    private Long workflowId;
    /** 租户内逻辑编码 */
    private String code;
    /** 中文名 */
    private String name;
    /** 定义当前默认发布指针；空=已取消发布能力（不应出现） */
    private Long defaultReleaseId;
    /** 默认发布版本号；未发布为空 */
    private Integer defaultVersion;
    /** 授权钉死的 release；空=跟随默认发布指针 */
    private Long releaseId;
    /** 1 启用 / 0 停用 */
    private Integer status;
    private LocalDateTime createTime;
}
