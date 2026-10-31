package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 预览请求。所属域：agent。
 * <p>
 * 允许「未保存直接预览」：只带四列的表单值 + 用途，不写库。
 * 变量一律用测试值或空值，禁止真调用模型。
 */
@Data
public class PromptPreviewReq {

    /** 用途码：CHAT / ROUTER / REWRITE / SUMMARY / MEMORY */
    private String purpose;

    /** 语言；空白回落 zh-CN */
    private String locale;

    /** 模板主键；必填 */
    private Long templateId;

    /** 钉死版本；必填，防止预览与保存口径不同 */
    private Integer templateVersion;

    /** soft 段覆盖 */
    private java.util.Map<String, String> override;

    /** 追加区 */
    private String promptExtra;

    /** Agent 名称，供 {{agent_name}}；可空 */
    private String agentName;

    /** 测试用用户原文，供 {{user_query}}；可空 */
    private String userQuery;
}
