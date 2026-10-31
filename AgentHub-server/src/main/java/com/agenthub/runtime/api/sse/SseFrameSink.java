package com.agenthub.runtime.api.sse;

/**
 * SSE 帧写出目标。所属域：runtime（出站层）。
 * 为什么抽成接口：done 兜底里「客户端已断开」这类路径必须能在单测里精确复现，
 * 而真实实现依赖 HttpServletResponse，无法在不起服务的前提下模拟断连。
 * 实现方约定：{@link #write} 每次调用都必须真实尝试一次写出（失败也不要短路），
 * 这样上层才能通过返回值判断连接是否还活着，也便于统计「尝试发 done」这一行为。
 */
public interface SseFrameSink {

    /**
     * 写一帧 SSE。
     *
     * @param event SSE 的 event 名，例如 assistant / message.delta / done
     * @param json  已序列化的 JSON 文本，作为 data 行内容
     * @return true 表示写出成功；false 表示连接已不可用（客户端断开、IO 异常等）
     */
    boolean write(String event, String json);

    /**
     * 结束本次响应。多次调用应幂等。
     */
    void complete();

    /**
     * @return true 表示此前已有写出失败，连接已判定不可用
     */
    boolean isBroken();
}
