package com.agenthub.agent.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 默认接待简易配置回显。所属域：agent。
 *
 * <p>刀4 起本页只暴露<b>路由池名单</b>：方案只读信息 + 候选名单 + 就绪状态。
 * 接待人设在其 Agent 的系统提示词里改，意图说明来自 Agent 简介，本 DTO 都不再回传提示词正文。
 *
 * <p>{@code ready=false} 时 {@code reason} 是可以直接展示给运营的中文原因，
 * 页面据此提示「去模型中心」或「先添加接待助手」。
 */
@Data
public class ReceptionConfigResp {

    /** 默认接待方案主键；未配置时为 null */
    private Long schemeId;
    private String schemeCode;
    private String schemeName;
    private String template;
    /** 1 启用 / 0 停用 */
    private Integer enabled;

    /** 当前是否可直接开始对话（方案启用 + 有候选 + 判路由模型可用） */
    private boolean ready;
    /** 未就绪原因；ready=true 时为 null */
    private String reason;

    /** 接待助手（排序第一的候选）；没有候选时为 null。只读：人设去 Agent 管理改 */
    private Long promptAgentId;
    private String promptAgentName;

    /** 候选名单，sort_no 小的在前 */
    private List<ReceptionCandidateResp> candidates;

    /**
     * 本次动作的附加说明（人话），当前只有「加入方案时自动移除重复裸专家」会填。
     *
     * <p>为什么放在回显里而不是静默处理：去重会删掉一条用户刚看到的线路，
     * 页面必须能明确告诉运营「A 已被方案 X 覆盖、已自动移除」，否则会以为是被 bug 吃了。
     * 其它动作恒为 null，页面按可选处理。
     */
    private List<String> notices;
}
