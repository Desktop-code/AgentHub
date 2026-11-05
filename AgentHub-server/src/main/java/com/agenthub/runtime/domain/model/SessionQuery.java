package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 试跑会话分页条件。所属域：runtime。
 * 试跑列表必须带上当前用户的 userId，避免查到别人的会话。
 */
@Data
public class SessionQuery {

    private long current = 1;
    private long size = 20;
    /** 非空时只看该平台用户的会话 */
    private Long userId;
    /** 非空时只看该 Agent */
    private Long agentId;
    /** 非空时只看该编排方案（虚拟根试跑）；与 agentId 互斥 */
    private Long maId;
    /**
     * 可空。非空时匹配预览，纯数字再或上会话 id。
     * 试跑列表不填，避免调用追踪的模糊条件进到对话抽屉。
     */
    private String keyword;
}
