package com.agenthub.obs.domain;

/**
 * 当前工具调用（TOOL 行）的线程上下文。所属域：obs。
 * <p>
 * 为什么需要：AgentScope 的工具执行在 {@code boundedElastic} 线程，
 * 而 TOOL 行是在事件收集线程打开的；工具内部的中间态（知识库检索子步）
 * 只有在执行线程知道「本工具 span id + trace scope」时才能稳定挂到父 span 下。
 * <p>
 * 约定：只能由工具调用入口 {@code bind}、退出时 {@code clear}，禁止业务代码长期持有；
 * 线程切换后需重新 bind（ThreadLocal 不跨线程传递）。
 */
public final class ObsToolSpanContext {

    /** 一次工具调用的埋点锚点；scope 为 null 表示无 trace */
    public record Anchor(ObsTraceScope scope, Long toolSpanId, Long agentId) {
    }

    private static final ThreadLocal<Anchor> CURRENT = new ThreadLocal<>();

    private ObsToolSpanContext() {
    }

    /**
     * 绑定本次工具调用的 Trace 锚点。
     * <p>scope 非空、toolSpanId 为空时仍保留锚点：让下游能区分「本来没有 trace」
     * 与「有 trace 但没匹配到 TOOL 行」，后者必须能报出来，不能静默。
     *
     * @param scope      当前 trace；空则等价清理
     * @param toolSpanId 本次 TOOL 行 id；可空（未匹配到）
     * @param agentId    本步 Agent；可空
     */
    public static void bind(ObsTraceScope scope, Long toolSpanId, Long agentId) {
        if (scope == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Anchor(scope, toolSpanId, agentId));
    }

    /**
     * @return 当前线程的工具锚点；未绑定或已清理时为 null
     */
    public static Anchor current() {
        return CURRENT.get();
    }

    /**
     * 清理执行线程上的锚点，避免线程池复用串到下个工具调用。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
