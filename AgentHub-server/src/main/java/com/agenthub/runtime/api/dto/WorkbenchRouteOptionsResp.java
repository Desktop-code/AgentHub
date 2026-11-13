package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 工作台当前会话的可选线路。所属域：runtime（api）。
 *
 * <p>候选来自<b>本会话快照方案</b>的启用候选，不是全平台 Agent 列表；
 * 已停用 / 已删除的候选不会出现在这里，因此前端可以直接用
 * {@code currentRouteKey} 是否命中候选来判断「线路已失效」。
 */
@Data
public class WorkbenchRouteOptionsResp {

    /** 会话当前沿用的 route_key；空表示尚未定线，下一轮交给判路由 */
    private String currentRouteKey;
    /**
     * 当前线路的候选展示名。
     *
     * <p>当前线路已停用 / 已删除时为空：前端据此显示「线路已失效」，
     * 并提示重选或让 Router 重新判定。
     */
    private String currentRouteName;
    /** 可选候选（已按 sort_no 排序，已过滤停用 Agent） */
    private List<WorkbenchRouteOptionResp> candidates = List.of();
}
