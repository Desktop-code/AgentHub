package com.agenthub.runtime.api.sse;

/**
 * 出站正文增量的合批参数。所属域：runtime（出站层）。
 *
 * <p>为什么需要它：模型侧一秒钟可能吐 20~60 个正文 chunk，如果每个 chunk 都写一帧 SSE，
 * 前端就要按 chunk 率重渲整棵对话树，网络帧数也翻几倍。合批把「连续同类型正文」攒成
 * 一帧再发，最终文本与顺序都不变，只是帧数降到「时间窗 / 字节上限」决定的量级。
 *
 * <p>双上限，先到先发：
 * <ul>
 *   <li>{@code windowMillis}：时间窗。攒够这么久（在有后续增量的前提下）就先发出去，控制延迟；</li>
 *   <li>{@code maxBytes}：单帧正文上限（UTF-8 字节）。防止极快输出时攒出一大坨，控制单帧体积。</li>
 * </ul>
 * 非法值（≤0）回落到默认，过大值夹紧到硬顶，配置写错不会把实时流变成「不流」。
 *
 * @param windowMillis 时间窗（毫秒），默认 {@value #DEFAULT_WINDOW_MILLIS}
 * @param maxBytes     单帧正文上限（UTF-8 字节），默认 {@value #DEFAULT_MAX_BYTES}
 */
public record SseBatchOptions(long windowMillis, int maxBytes) {

    /** 默认时间窗（毫秒）。50ms 约等于 3 帧渲染周期，人眼已看不出「攒」的痕迹 */
    public static final long DEFAULT_WINDOW_MILLIS = 50L;

    /** 默认单帧正文上限（UTF-8 字节）。中文约 680 字，够一屏段落 */
    public static final int DEFAULT_MAX_BYTES = 2048;

    /** 时间窗硬顶（毫秒）：再大就不该叫流式了，配置误写也不会拖成批量返回 */
    private static final long MAX_WINDOW_MILLIS = 1000L;

    /** 单帧硬顶（字节）：挡住「配置成几 MB」这类误写 */
    private static final int MAX_BYTES_LIMIT = 64 * 1024;

    /**
     * 归一化：越界或非正数一律回落默认，避免 0 窗/0 字节导致「永不下发」或「一帧一 chunk」。
     */
    public SseBatchOptions {
        windowMillis = windowMillis <= 0 ? DEFAULT_WINDOW_MILLIS : Math.min(windowMillis, MAX_WINDOW_MILLIS);
        maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : Math.min(maxBytes, MAX_BYTES_LIMIT);
    }

    /**
     * @return 默认参数（50ms / 2KB），供不读配置的调用方与单测使用
     */
    public static SseBatchOptions defaults() {
        return new SseBatchOptions(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_BYTES);
    }

    /**
     * @return 时间窗对应的纳秒数，便于与 {@link System#nanoTime()} 直接比较
     */
    public long windowNanos() {
        return windowMillis * 1_000_000L;
    }
}
