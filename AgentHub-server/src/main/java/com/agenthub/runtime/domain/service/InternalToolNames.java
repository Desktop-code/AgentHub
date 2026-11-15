package com.agenthub.runtime.domain.service;

/**
 * 平台内部工具名的统一口径。所属域：runtime，纯常量。
 *
 * <p>为什么集中在这里：这些工具是「平台自己用的控制面」，不是用户配置的业务工具。
 * 它们的调用不得出现在用户可见的时间线里（参数就是选项 JSON / 内部状态），
 * 也不该占用「工具调用次数」这种给用户看的展示。采集器、时间线、进度推送都用同一份判断。
 */
public final class InternalToolNames {

    /**
     * 需要用户拍板时的结构化选项工具。
     *
     * <p>字段与 {@link com.agenthub.runtime.domain.model.AgentChoice} 一致：
     * question + options[{id,label}]，2~6 项。模型调用它即视为「已给出可点选项」，
     * 前端只渲染落库的 CHOICE 事件，不再解析正文。
     */
    public static final String CHOICE = "agenthub_choice";

    private InternalToolNames() {
    }

    /**
     * 是否是「不展示、不落 TOOL 事件」的内部工具。
     *
     * @param name 工具调用名，可空
     * @return true 表示应静音
     */
    public static boolean isQuiet(String name) {
        return name != null && CHOICE.equals(name.trim());
    }
}
