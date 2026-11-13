package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 工作台对话就绪信息。所属域：runtime。
 *
 * <p>{@code ready=false} 时页面只展示 {@code reason} 并禁用输入，不去猜别的方案；
 * 判路由模型缺口的修复入口在模型中心，不在工作台页再堆字段。
 */
@Data
public class WorkbenchConfigResp {

    /** 是否可新建会话 / 发送消息 */
    private boolean ready;
    /** 未就绪原因；ready=true 时为 null */
    private String reason;

    /** 默认接待方案；未配置时为 null */
    private Long schemeId;
    private String schemeCode;
    private String schemeName;
    private String template;

    /** 候选名单（sort_no 升序）；排序第一的「Agent」候选是接待助手，MA 方案候选不接管兜底 */
    private List<WorkbenchCandidateResp> candidates;
}
