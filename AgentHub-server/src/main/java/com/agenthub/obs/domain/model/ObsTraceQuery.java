package com.agenthub.obs.domain.model;

import lombok.Data;

/**
 * 某个会话下的 trace 分页。所属域：obs。
 * userId 为空表示调用方已经确认是管理员，不再按人过滤。
 */
@Data
public class ObsTraceQuery {

    private long current = 1;
    private long size = 20;
    private Long sessionId;
    /** 非空时只看这个平台用户 */
    private Long userId;
    /** 模糊匹配 title，可空 */
    private String keyword;
}
