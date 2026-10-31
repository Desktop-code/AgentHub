package com.agenthub.runtime.api.sse;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 基于 Servlet {@link HttpServletResponse} 的 SSE 帧写出实现。所属域：runtime（出站层）。
 * 为什么仍用 Servlet 同步写：现网试跑在请求线程里边跑边推，登录态依赖当前请求上下文，
 * 换成响应式栈要重做鉴权与线程模型，本期明确不做。
 * 断连判定：{@link PrintWriter#checkError()} 能发现客户端断开（写缓冲失败），
 * 一旦置位就不再尝试写业务帧——但结束帧仍会尝试一次，那是定稿要求的兜底。
 */
public final class ServletSseFrameSink implements SseFrameSink {

    private final HttpServletResponse response;
    private final boolean flushEachFrame;
    /** 连接是否已判定不可用 */
    private boolean broken;
    /** complete 是否已调用，保证幂等 */
    private boolean completed;

    /**
     * @param response       已设置为 text/event-stream 的响应
     * @param flushEachFrame true 表示每帧都 flush（真流式必须为 true）
     */
    public ServletSseFrameSink(HttpServletResponse response, boolean flushEachFrame) {
        this.response = response;
        this.flushEachFrame = flushEachFrame;
    }

    /**
     * {@inheritDoc}
     * 注意：即使 broken 为 true 也**仍然尝试写出**。结束帧是兜底手段，
     * 万一先前的失败只是瞬时抖动，这一次尝试仍可能送达；失败也只是一次空写，代价可接受。
     */
    @Override
    public synchronized boolean write(String event, String json) {
        if (completed) {
            // 响应已收口，再写会抛 IllegalStateException
            return false;
        }
        try {
            PrintWriter writer = response.getWriter();
            writer.write("event: ");
            writer.write(event == null ? "message" : event);
            writer.write("\n");
            writer.write("data: ");
            writer.write(json == null ? "" : json);
            writer.write("\n\n");
            if (flushEachFrame) {
                writer.flush();
                response.flushBuffer();
            }
            // checkError 会吞掉异常只返回状态，必须显式判断，否则断连后一直空写
            if (writer.checkError()) {
                broken = true;
                return false;
            }
            return true;
        } catch (IOException ex) {
            broken = true;
            return false;
        } catch (IllegalStateException ex) {
            // 响应已被容器提交/关闭
            broken = true;
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * Servlet 同步 SSE 没有独立的 emitter 概念，这里只做状态收口。
     * 不主动调 flushBuffer：若最后一次写出已 flush，再刷会抛异常且没有收益。
     */
    @Override
    public synchronized void complete() {
        completed = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean isBroken() {
        return broken;
    }

    /**
     * 统一设置 SSE 响应头。抽出静态方法，避免各 Controller 各写一套漏掉关键头。
     *
     * @param response 待设置的响应
     */
    public static void prepare(HttpServletResponse response) {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/event-stream");
        // no-transform 阻止 nginx 等中间层缓冲，否则前端收不到增量
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
    }
}
