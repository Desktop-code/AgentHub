package com.agenthub.workflow.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条图校验问题。所属域：workflow 的 domain。
 * <p>code 供前端分支与后续 i18n；message 是中文可读文案；
 * nodeId / nodeCode 用于前端点击定位到画布节点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowIssue {

    /** 机器可读的问题码，如 CYCLE / CONDITION_DEFAULT_MISSING */
    private String code;
    /** 中文说明 */
    private String message;
    /** 关联节点 id，可空 */
    private String nodeId;
    /** 关联节点 code，可空 */
    private String nodeCode;
}
