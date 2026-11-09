package com.agenthub.runtime.api.sse;

import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * 正文增量合批缓冲。所属域：runtime（出站层）。
 *
 * <p>只合批「连续同类型」的正文片段（助手正文 / 思考正文）：一旦类型切换就先把上一段发出去，
 * 不会把思考和正文混进同一帧。控制类事件（phase / route / tool / plan / step / done / error 等）
 * 由调用方在写出前调用 {@link #flush()}，从而保持「正文在前、事件在后」的相对顺序。
 *
 * <p>时间窗是「到达驱动」的：新片段到达时若上一段已攒够 {@code windowMillis}，先发上一段再攒新的。
 * 因此不需要额外的定时线程（不引入调度器、不跨线程写响应），
 * 最长保留时间就是时间窗；而一段文字后面长时间没有新内容时，
 * 本来也没有新东西要看，剩余字节会由紧随的控制帧或流收尾的 flush 立刻带出去。
 *
 * <p>线程：本类<b>不加锁</b>，所有方法都必须由 {@link UnifiedSseWriter} 的监听器内调用，
 * 借用写入器已有的互斥，避免多子 Agent 并行时两次 flush 交叉写出半帧。
 */
final class SseTextBatcher {

    /**
     * 可合批的正文类型。只有「纯文本增量」才进缓冲，其它事件一律走控制帧路径。
     */
    enum Kind {
        /** 助手终答正文 */
        ASSISTANT,
        /** 模型思考过程 */
        REASONING
    }

    /**
     * 真正写出帧的回调。由写入器实现，负责把一段合并后的文本写成新旧两帧。
     */
    interface Sink {
        /**
         * @param kind 该段文本的类型
         * @param text 合并后的完整文本，非空
         */
        void write(Kind kind, String text);
    }

    private final SseBatchOptions options;
    private final LongSupplier nanoClock;
    private final Sink sink;

    /** 待发文本。用 StringBuilder 续写，避免每来一个 chunk 就多一次字符串拼接 */
    private final StringBuilder buffer = new StringBuilder(256);
    /** 当前缓冲的类型；缓冲区为空时无意义 */
    private Kind kind;
    /** 已攒的 UTF-8 字节数，用于字节上限判定 */
    private int pendingBytes;
    /** 当前这段开始攒的时刻（纳秒），用于时间窗判定 */
    private long windowStartNanos;

    /**
     * @param options   时间窗与字节上限
     * @param nanoClock 纳秒时钟，生产传 {@link System#nanoTime}；单测注入可控时钟
     * @param sink      合并后的写出回调
     */
    SseTextBatcher(SseBatchOptions options, LongSupplier nanoClock, Sink sink) {
        this.options = options;
        this.nanoClock = nanoClock;
        this.sink = sink;
    }

    /**
     * 收下一段正文。可能触发「先发上一段」，也可能把本段攒着。
     *
     * @param kind 文本类型，不可为 null
     * @param text 本段文本，空串直接忽略
     */
    void accept(Kind kind, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        long now = nanoClock.getAsLong();
        // 类型切换：思考与正文不能混进同一帧，先把上一段落地
        if (pendingBytes > 0 && this.kind != kind) {
            flush();
        } else if (pendingBytes > 0 && pendingBytes >= options.maxBytes()) {
            // 上一次追加已触顶却没发出去（正常不会发生），这里兜一层，保证字节上限是硬约束
            flush();
        } else if (pendingBytes > 0 && now - windowStartNanos >= options.windowNanos()) {
            // 时间窗到：上一段先发，本段落进新的一段，延迟就被压在时间窗量级
            flush();
        }
        // 缓冲区空出来才是新一段的起点，时间窗从这一刻开始算
        if (pendingBytes == 0) {
            this.kind = kind;
            windowStartNanos = now;
        }
        buffer.append(text);
        pendingBytes += utf8Length(text);
        // 字节上限到了就立刻发，不等到下一次到达
        if (pendingBytes >= options.maxBytes()) {
            flush();
        }
    }

    /**
     * 把缓冲里的文本发出去。空缓冲是安全的空操作，因此可以在每个控制帧前无条件调用。
     */
    void flush() {
        if (pendingBytes == 0) {
            return;
        }
        Kind pendingKind = kind;
        String text = buffer.toString();
        // 先清空再写：万一写出路径异常，也不会把同一段文本重复发第二次
        buffer.setLength(0);
        pendingBytes = 0;
        kind = null;
        sink.write(pendingKind, text);
    }

    /**
     * @return 是否还有没发出去的正文
     */
    boolean hasPending() {
        return pendingBytes > 0;
    }

    /**
     * 按 UTF-8 计字节，与「maxBytes 是字节不是字符」的口径一致。
     * 单段增量只有几个到几十个字符，这点分配可以忽略。
     *
     * @param text 待计数的文本
     * @return UTF-8 字节数
     */
    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
