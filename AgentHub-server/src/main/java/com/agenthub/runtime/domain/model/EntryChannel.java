package com.agenthub.runtime.domain.model;

/**
 * 会话入口渠道。所属域：runtime。
 *
 * <p>同一个 {@code agent_run_session} 表同时承载两类入口，靠本枚举隔离列表与权限：
 * <ul>
 *   <li>{@link #DEBUG}：控制台调试（Agent 抽屉 / 多智能体运行页），走 {@code runtime:trial:*}；</li>
 *   <li>{@link #WORKBENCH}：工作台正式对话，走 {@code runtime:chat:use}。</li>
 * </ul>
 *
 * <p>存量会话一律回落 DEBUG；空值不做猜测，避免历史脏值串到工作台列表。
 */
public enum EntryChannel {

    /** 控制台调试入口 */
    DEBUG,
    /** 工作台正式对话入口 */
    WORKBENCH;

    /**
     * 空值 / 未知值统一回落 DEBUG，保证老会话不因新列缺失而消失。
     *
     * @param raw 库内或请求传入的渠道
     * @return 合法渠道名；非法值返回 DEBUG
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEBUG.name();
        }
        String text = raw.trim();
        for (EntryChannel channel : values()) {
            if (channel.name().equalsIgnoreCase(text)) {
                return channel.name();
            }
        }
        return DEBUG.name();
    }

    /**
     * @param raw 渠道字符串，可空
     * @return true 表示工作台正式对话
     */
    public static boolean isWorkbench(String raw) {
        return WORKBENCH.name().equals(normalize(raw));
    }
}
