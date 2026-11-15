package com.agenthub.runtime.domain.model;

/**
 * 本轮判路由的可选用线路提示。所属域：runtime。
 *
 * <p>只有工作台正式对话会传；调试 / 试跑路径传 {@code null}，行为与刀3 之前完全一致。
 *
 * <p>三个字段的分工：
 * <ul>
 *   <li>{@code stickyRouteKey}：会话上次命中的线路，仅用于往判路由提示词里注入
 *       「优先沿用」说明，判路由仍可改派；</li>
 *   <li>{@code forceRouteKey}：用户手动换线后的一次性强制标记，只作用于下一轮；
 *       命中时跳过判路由直接执行，之后清除，避免永久锁死；</li>
 *   <li>{@code judgeInput}：判路由模型实际读到的「用户侧输入」。工作台会带上会话前文
 *       （摘要 / 最近对话 / 当前用户），与专家作答共用同一份文本；
 *       提示词模板里的 {@code {{user_query}}} 仍是用户原文，不在这里替换。</li>
 * </ul>
 *
 * @param stickyRouteKey 上次沿用线路的 route_key；空表示本会话尚未定线
 * @param forceRouteKey  本轮强制线路的 route_key；空表示不强制
 * @param judgeInput     判路由模型的用户侧输入；空表示沿用本轮用户原文
 */
public record RouteHint(String stickyRouteKey, String forceRouteKey, String judgeInput) {

    /** 空白键统一收敛成 null，判路由侧只看「有没有值」，不再判空串。 */
    public RouteHint {
        if (stickyRouteKey != null && stickyRouteKey.isBlank()) {
            stickyRouteKey = null;
        }
        if (forceRouteKey != null && forceRouteKey.isBlank()) {
            forceRouteKey = null;
        }
        if (judgeInput != null && judgeInput.isBlank()) {
            judgeInput = null;
        }
    }

    /**
     * 只用沿用线路构造（普通一轮，不指定判定输入）。
     *
     * @param stickyRouteKey 上次沿用的 route_key；可空
     * @return 提示；两个键都空时仍返回对象，调用方按 {@link #empty()} 判断
     */
    public static RouteHint sticky(String stickyRouteKey) {
        return new RouteHint(stickyRouteKey, null, null);
    }

    /**
     * 沿用线路 + 判路由输入。
     *
     * @param stickyRouteKey 上次沿用的 route_key；可空
     * @param judgeInput     判路由模型读的用户侧文本（含会话前文）；可空
     * @return 提示
     */
    public static RouteHint sticky(String stickyRouteKey, String judgeInput) {
        return new RouteHint(stickyRouteKey, null, judgeInput);
    }

    /**
     * 手动换线后的强制一轮。
     *
     * @param stickyRouteKey 刚写入的沿用线路（与 force 同值，供改派比较）
     * @param forceRouteKey  本轮强制命中的 route_key
     * @return 提示
     */
    public static RouteHint forced(String stickyRouteKey, String forceRouteKey) {
        return new RouteHint(stickyRouteKey, forceRouteKey, null);
    }

    /**
     * 手动换线后的强制一轮 + 判路由输入。
     *
     * @param stickyRouteKey 刚写入的沿用线路
     * @param forceRouteKey  本轮强制命中的 route_key
     * @param judgeInput     判路由模型读的用户侧文本（含会话前文）；可空
     * @return 提示
     */
    public static RouteHint forced(String stickyRouteKey, String forceRouteKey, String judgeInput) {
        return new RouteHint(stickyRouteKey, forceRouteKey, judgeInput);
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
