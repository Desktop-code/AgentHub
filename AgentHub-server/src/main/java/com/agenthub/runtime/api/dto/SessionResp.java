package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑会话。所属域：runtime。预览和错误摘要已脱敏。
 * sessionTitle 是左栏主标题（自动生成或手改），lastMsgPreview 是首问短预览，两者互不覆盖。
 *
 * <p>MA-P2 刀2 起支持虚拟根：{@code agentId} 可空，{@code maId / maCode / maName}
 * 用于左栏与试跑页标题；绑根会话也会带方案展示信息（执行读源以方案为准）。
 */
@Data
public class SessionResp {

    private Long id;
    /** 绑根试跑为 Agent 主键；虚拟根为空 */
    private Long agentId;
    /** 展示名：绑根用 Agent 名，虚拟根用方案名 */
    private String agentName;
    /** 归属方案；绑根会话建会话时可能为空（执行时按启用方案解析） */
    private Long maId;
    private String maCode;
    private String maName;
    private String runMode;
    private Long userId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer messageCount;
    /** 会话标题。空表示尚未生成，前端退回展示 lastMsgPreview */
    private String sessionTitle;
    /** AUTO 自动生成 / MANUAL 用户手改 */
    private String titleSource;
    private String lastMsgPreview;
    private String errorSummary;
    /** 最近变更时间。列表上的相对时间用它，没有则退回 startTime */
    private LocalDateTime updateTime;
    /** 入口渠道：WORKBENCH 工作台对话 / DEBUG 控制台调试 */
    private String entryChannel;
    /** 工作台会话落定的默认接待方案；调试会话为空 */
    private Long receptionMaId;
    /**
     * 沿用的线路 route_key（刀3）。
     *
     * <p>只给前端做「当前 chip 是否有效」的比较用；芯片本身只展示候选名，
     * 不把 route_key 直接暴露给用户。空表示本会话尚未定线，下一轮交给判路由。
     */
    private String stickyRouteKey;
}
