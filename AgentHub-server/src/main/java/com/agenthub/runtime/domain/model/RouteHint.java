package com.agenthub.runtime.domain.model;

/**
 * 本轮判路由的可选用线路提示。所属域：runtime。
 *
 * <p>只有工作台正式对话会传；调试 / 试跑路径传 {@code null}，行为与刀3 之前完全一致。
 *
 * <p>两个键的分工：
 * <ul>
 *   <li>{@code stickyRouteKey}：会话上次命中的线路，仅用于往判路由提示词里注入
 *       「优先沿用」说明，判路由仍可改派；</li>
 *   <li>{@code forceRouteKey}：用户手动换线后的一次性强制标记，只作用于下一轮；
 *       命中时跳过判路由直接执行，之后清除，避免永久锁死。</li>
 * </ul>
 *
 * @param stickyRouteKey 上次沿用线路的 route_key；空表示本会话尚未定线
 * @param forceRouteKey  本轮强制线路的 route_key；空表示不强制
 */
public record RouteHint(String stickyRouteKey, String forceRouteKey) {

    /** 空白键统一收敛成 null，判路由侧只看「有没有值」，不再判空串。 */
    public RouteHint {
        if (stickyRouteKey != null && stickyRouteKey.isBlank()) {
            stickyRouteKey = null;
        }
        if (forceRouteKey != null && forceRouteKey.isBlank()) {
            forceRouteKey = null;
        }
    }

    /**
     * 只用沿用线路构造（普通一轮）。
     *
     * @param stickyRouteKey 上次沿用的 route_key；可空
     * @return 提示；两个键都空时仍返回对象，调用方按 {@link #empty()} 判断
     */
    public static RouteHint sticky(String stickyRouteKey) {
        return new RouteHint(stickyRouteKey, null);
    }

    /**
     * 手动换线后的强制一轮。
     *
     * @param stickyRouteKey 刚写入的沿用线路（与 force 同值，供改派比较）
     * @param forceRouteKey  本轮强制命中的 route_key
     * @return 提示
     */
    public static RouteHint forced(String stickyRouteKey, String forceRouteKey) {
        return new RouteHint(stickyRouteKey, forceRouteKey);
    }

    /**
     * @return true 表示两个键都为空，判路由按无提示处理
     */
    public boolean empty() {
        return stickyRouteKey == null && forceRouteKey == null;
    }

    /**
     * @return true 表示本轮要跳过判路由直接命中
     */
    public boolean forced() {
        return forceRouteKey != null;
    }
}
