package com.agenthub.admin.application;

import com.agenthub.common.text.LikeText;

/**
 * admin 域的 LIKE 片段入口。所属域：admin。
 *
 * <p>实现已收敛到 {@link LikeText}（common 域），本类只保留原方法签名供 admin 既有调用点使用；
 * 新代码请直接用 {@code LikeText.contains}。
 */
public final class LikeTexts {

    /** 工具类禁止实例化。 */
    private LikeTexts() {
    }

    /**
     * @param raw 用户输入，空白视为不筛选
     * @return 可放进 LIKE 的文本；不需要筛选时返回 null
     */
    public static String contains(String raw) {
        return LikeText.contains(raw);
    }
}
