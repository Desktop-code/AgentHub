package com.agenthub.workflow.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用-工作流触发授权。所属域：workflow 的 domain。对应表 app_workflow_grant。
 * <p>语义：一行 = 某个 sys_app 可以触发某个 workflow_def。
 * <ul>
 *   <li>{@code releaseId} 为空：每次触发都解析 def 的默认发布指针（与试跑「用默认 release」对齐）；</li>
 *   <li>{@code releaseId} 非空：钉死该历史 release，后续再发布不影响这次授权；</li>
 *   <li>{@code status}=1 才允许入站触发，0 视为运营停用。</li>
 * </ul>
 * <p>只允许绑定「发布过至少一次」的 def（应用层校验），草稿永远不可被应用触发。
 */
@Data
public class AppWorkflowGrant {

    /** 主键 */
    private Long id;
    /** sys_app.id */
    private Long appId;
    /** workflow_def.id */
    private Long workflowDefId;
    /** 钉死的 release；空=跟随默认发布指针 */
    private Long releaseId;
    /** 1 启用 / 0 停用 */
    private Integer status;
    /** 创建人（控制台用户） */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
