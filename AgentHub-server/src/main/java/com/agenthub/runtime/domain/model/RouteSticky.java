package com.agenthub.runtime.domain.model;

/**
 * 一轮编排结束后要回写的线路粘性。所属域：runtime。
 *
 * <p>语义只有三种，调用方不再猜：
 * <ul>
 *   <li>{@link #keep(String, Long)}：单意图成功轮，写入「本轮命中的线路」；</li>
 *   <li>{@link #clear()}：多意图协作轮，清空（下一轮重新判，避免把协作误降级成单人）；</li>
 *   <li>{@code null}：固定模板 / 评审环 / 竞争选优等不判路由的模板，或失败轮，保持原值不动。</li>
 * </ul>
 *
 * <p>{@code routeKey} 为空表示清空：清空与「写一个空键」是同一件事，
 * 数据库侧只看该列是否为 NULL。
 *
 * @param routeKey 要沿用的 route_key；null 表示清空
 * @param agentId  该线路的子 Agent 主键；清空时同样为 null，仅用于列表展示回填
 */
public record RouteSticky(String routeKey, Long agentId) {

    /**
     * 单意图轮：沿用本轮命中的线路。
     *
     * @param routeKey 命中的 route_key；空表示该线路没有键，等价于清空
     * @param agentId  子 Agent 主键
     * @return 粘性回写指令
     */
    public static RouteSticky keep(String routeKey, Long agentId) {
        if (routeKey == null || routeKey.isBlank()) {
            return clear();
        }
        return new RouteSticky(routeKey.trim(), agentId);
    }

    /**
     * 多意图轮：清空线路，下一轮重新判。
     *
     * @return 清空指令
     */
    public static RouteSticky clear() {
        return new RouteSticky(null, null);
    }
}
