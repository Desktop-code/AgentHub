package com.agenthub.runtime.model.acl.impl;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 出站失败类型的统一构造与脱敏。所属域：runtime（模型出站 ACL 的实现细节）。
 * 为什么集中：三家 Adapter 的错误收尾规则必须完全一致（先 error 再 done、文案不得含 Key 与响应体），
 * 各写一份迟早漂移；这里统一给出异常分类与固定文案。
 * 安全约束：本类产出的任何文案都不拼接 apiKey、请求头或上游响应体原文。
 */
final class UpstreamFailures {

    /** 上游连接失败类异常的简单名关键字，用于把异常归到「连接失败」文案 */
    private static final String[] CONNECT_HINTS = {"Connect", "Unresolved", "UnknownHost", "Connection"};

    private UpstreamFailures() {
    }

    /**
     * 构造「上游返回非 2xx」异常。
     *
     * @param status HTTP 状态码
     * @param body   响应体，仅用于判断是否为空，不透出内容
     * @return 异常实例
     */
    static UpstreamHttpException http(int status, String body) {
        return new UpstreamHttpException(status, body);
    }

    /**
     * 构造「两帧之间长时间无数据」异常。
     *
     * @param timeout 超时阈值
     * @return 异常实例
     */
    static UpstreamIdleTimeoutException idleTimeout(Duration timeout) {
        return new UpstreamIdleTimeoutException(timeout);
    }

    /**
     * 异常转事件：先补发未发出的 completed，再发一条 error，最后补 done。
     * done 用 CAS 抢占，避免与正常收尾路径重复发。
     *
     * @param error    原始异常
     * @param decoder  当前流解码器，用于补发 completed 与带上已知 usage
     * @param doneSent done 标记，本方法会 CAS 置位
     * @return 至多若干 completed + 一个 error + 一个 done
     */
    static List<UnifiedStreamEvent> errorEvents(Throwable error, VendorStreamDecoder decoder, AtomicBoolean doneSent) {
        List<UnifiedStreamEvent> events = new ArrayList<>(decoder.drainCompleted());
        UnifiedFinishReason reason = isTimeout(error) ? UnifiedFinishReason.TIMEOUT : UnifiedFinishReason.ERROR;
        events.add(UnifiedStreamEvent.error(describe(error), reason));
        // 错误路径同样必须有 done，否则前端会一直等 Loading
        if (doneSent.compareAndSet(false, true)) {
            events.add(UnifiedStreamEvent.done(reason, decoder.usage()));
        }
        return events;
    }

    /**
     * @param error 原始异常
     * @return 是否属于超时类异常（含 Reactor 的 TimeoutException）
     */
    static boolean isTimeout(Throwable error) {
        if (error instanceof UpstreamIdleTimeoutException) {
            return true;
        }
        return error != null && error.getClass().getSimpleName().contains("Timeout");
    }

    /**
     * 脱敏错误摘要。只按异常类型给固定文案，不拼异常原文与响应体。
     *
     * @param error 原始异常，可空
     * @return 可展示给前端的摘要
     */
    static String describe(Throwable error) {
        if (error == null) {
            return "模型调用失败";
        }
        if (error instanceof UpstreamHttpException http) {
            return "模型服务返回 HTTP " + http.status();
        }
        if (error instanceof UpstreamIdleTimeoutException) {
            return "模型流式响应超时";
        }
        String name = error.getClass().getSimpleName();
        if (name.contains("Timeout")) {
            return "模型调用超时";
        }
        for (String hint : CONNECT_HINTS) {
            if (name.contains(hint)) {
                return "模型服务连接失败";
            }
        }
        return "模型调用失败";
    }
}
