package com.agenthub.workflow.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工待办列表行（WF-P0 刀3）。所属域：workflow 的 api。
 *
 * <p>与 {@link WorkflowHumanTaskResp} 的区别：本 DTO 面向「待办中心」列表，
 * 额外带上 run / 工作流上下文与「当前用户是否可处理」，避免前端再回查 run 详情
 * （run 详情需要 {@code workflow:run:trial}，而处理人未必有该权限）。
 *
 * <p>不含 form_json / result_json 正文：列表只做导航，不搬运大字段。
 */
@Data
public class WorkflowHumanTaskItemResp {

    /** 待办主键 */
    private Long id;
    /** run 主键 */
    private Long runId;
    /** 所属工作流主键 */
    private Long workflowId;
    /** 工作流编码，便于对账 */
    private String workflowCode;
    /** 工作流名称；定义被删除时为空串 */
    private String workflowName;
    /** run 绑定的发布版本号 */
    private Integer releaseVersion;
    /** run 当前状态（OPEN 待办通常为 PAUSED_HUMAN） */
    private String runStatus;
    /** 节点编码 */
    private String nodeCode;
    /** 待办状态 OPEN / DONE / EXPIRED */
    private String status;
    /** 处理人规则原文（空=任何人） */
    private String assigneeRule;
    /** 处理人规则中文描述，服务端统一给，避免前后端文案漂移 */
    private String assigneeText;
    /** 超时策略 */
    private String timeoutPolicy;
    /** 到期时间；null 表示不超时 */
    private LocalDateTime expireTime;
    /** 待办创建时间 */
    private LocalDateTime createTime;
    /** 当前用户此刻能否处理（OPEN + run 等待人工 + 规则命中） */
    private boolean actionable;
}
