package com.agenthub.runtime.domain.service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 工具失败的类型判定：这个异常「值不值得再试一次」。所属域：runtime。
 *
 * <p>为什么需要它：原先所有工具失败一视同仁地计入连续失败计数，
 * 模型看到失败就换个说法再搜一遍，几轮下来把 {@code consecutive_fail_abort}
 * 打满，而真正的首错被熔断文案盖掉。把「可重试」与「不可重试」分开之后：
 * <ul>
 *   <li><b>可重试</b>：超时、连接失败、短暂网络 / 服务不可用——这类再试一次有机会成功；</li>
 *   <li><b>不可重试</b>：参数非法、资源不存在、权限拒绝、明确的 4xx / 业务错误。
 *       再试也不会变好，只会在页面上堆红条。</li>
 * </ul>
 *
 * <p>判定只用异常本身已有的信息（类型 + 类名 + 消息关键字 + 可选的错误码），
 * <b>不猜测</b>具体组件（不因为消息里出现某个词就断言是 Milvus / embedding 的问题）。
 *
 * <p>线程安全：无状态，全部静态纯函数。
 */
public final class ToolFailureKind {

    /** 判定结果 */
    public enum Kind {
        /**
         * 可重试：连接 / 超时 / 短暂不可用。
         * <p>允许有限次重试（次数由调用方沿用现网配额，本类不放大阈值）。
         */
        RETRYABLE,
        /**
         * 不可重试：业务 / 代码异常，或已经熔断。
         * <p>调用方应停止这一路，不要为了「凑满 3 次」再发起重试或并行多路。
         */
        FATAL,
        /** 认不出类型：按不可重试处理，避免把未知异常当成超时反复重打。 */
        UNKNOWN
    }

    /**
     * 消息里代表「连接 / 超时 / 暂时不可用」的关键字。
     *
     * <p>只放明确指向瞬时故障的词；业务错误（参数、不存在、无权限）不在此列。
     */
    private static final String[] RETRYABLE_MARKERS = {
            "timeout", "timed out", "超时",
            "connection refused", "connection reset", "connect timed out", "连接被拒绝", "连接重置",
            "unknown host", "no route to host", "dns",
            "temporarily unavailable", "service unavailable", "502", "503", "504",
            "too many requests", "429", "rate limit", "限流",
            "broken pipe", "unexpected end of stream", "过早关闭", "connection closed"
    };

    /**
     * 消息里代表「业务 / 参数 / 权限」的关键字：命中即判为不可重试。
     *
     * <p>优先于 {@link #RETRYABLE_MARKERS}：某些业务错误文本里也会出现 503 之类的数字，
     * 业务原因必须赢。
     */
    private static final String[] FATAL_MARKERS = {
            "invalid", "illegal", "missing", "required", "不能为空", "参数", "非法", "缺少", "格式错误",
            "not found", "not exist", "不存在", "未找到",
            "permission", "denied", "forbidden", "unauthorized", "无权限", "权限不足", "未授权",
            "400", "401", "403", "404", "422",
            "撞限", "consecutive_fail_abort", "max_tool_calls", "max_llm_calls"
    };

    private ToolFailureKind() {
    }

    /**
     * 判定一个工具失败是否可以重试。
     *
     * <p>顺序：先看异常类型（最可靠），再看错误码，最后看消息关键字。
     * 业务关键字优先于瞬时关键字，避免「参数非法（503）」这类文本被判成可重试。
     *
     * @param error   失败异常；可空
     * @param status  可选状态码（HTTP / 业务码）；没有则传 null
     * @return 判定结果；认不出为 {@link Kind#UNKNOWN}
     */
    public static Kind of(Throwable error, Integer status) {
        if (status != null) {
            // 5xx 里只有 502/503/504 属瞬时；4xx 一律业务侧
            if (status == 502 || status == 503 || status == 504 || status == 429) {
                return Kind.RETRYABLE;
            }
            if (status >= 400) {
                return Kind.FATAL;
            }
        }
        if (error == null) {
            return Kind.UNKNOWN;
        }
        // 类型优先：这些 JDK 异常语义明确，不必再看消息
        if (error instanceof SocketTimeoutException || error instanceof TimeoutException) {
            return Kind.RETRYABLE;
        }
        if (error instanceof ConnectException || error instanceof UnknownHostException) {
            return Kind.RETRYABLE;
        }
        String text = describe(error);
        if (hits(text, FATAL_MARKERS)) {
            return Kind.FATAL;
        }
        if (hits(text, RETRYABLE_MARKERS)) {
            return Kind.RETRYABLE;
        }
        return Kind.UNKNOWN;
    }

    /**
     * @param error 失败异常
     * @return 工具失败是否可以重试（UNKNOWN 视为不可重试）
     */
    public static boolean retryable(Throwable error, Integer status) {
        return of(error, status) == Kind.RETRYABLE;
    }

    /**
     * 只有错误正文、没有异常对象时的判定。
     *
     * <p>引擎在工具失败时常常只给「失败状态 + 一段错误正文」，不抛异常。
     * 这段正文就是判断「值不值得再试」的唯一依据，必须与有异常时同一套关键字表。
     * 没有任何正文时返回 {@link Kind#UNKNOWN}（按不可重试处理，不静默放行）。
     *
     * @param text 工具返回的错误正文，可空
     * @return 判定结果
     */
    public static Kind ofText(String text) {
        if (text == null || text.isBlank()) {
            return Kind.UNKNOWN;
        }
        String normalized = text.toLowerCase();
        // 业务关键字优先：错误正文里带 503 不代表可以重试
        if (hits(normalized, FATAL_MARKERS)) {
            return Kind.FATAL;
        }
        if (hits(normalized, RETRYABLE_MARKERS)) {
            return Kind.RETRYABLE;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 异常链上的类名与消息拼成一段小写文本，供关键字匹配。
     *
     * @param error 失败异常
     * @return 小写文本；无内容时为空串
     */
    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 4) {
            text.append(cursor.getClass().getSimpleName()).append(' ');
            if (cursor.getMessage() != null) {
                text.append(cursor.getMessage()).append(' ');
            }
            Throwable next = cursor.getCause();
            if (next == null || next == cursor) {
                break;
            }
            cursor = next;
            depth++;
        }
        return text.toString().toLowerCase();
    }

    /**
     * @param text    小写文本
     * @param markers 关键字表
     * @return 是否命中任一关键字
     */
    private static boolean hits(String text, String[] markers) {
        if (text.isEmpty()) {
            return false;
        }
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
