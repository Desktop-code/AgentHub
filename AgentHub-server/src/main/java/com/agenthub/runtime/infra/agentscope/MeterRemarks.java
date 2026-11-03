package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.TrialFailure;

/**
 * 计量备注与 span 失败摘要。所属域：runtime 的 infra。
 *
 * <p>优先级：
 * <ol>
 *   <li>链上有 {@link TrialFailure} 时用它的说明（已是给用户看的人话）；</li>
 *   <li>其次是「上游返回 HTTP &lt;status&gt;：&lt;脱敏 body 摘要&gt;」——这是 400 类问题唯一能落库的线索，
 *       只写类名等于把「为什么被拒」丢掉了；</li>
 *   <li>都没有时退回最内层类名，仍不写异常原文，避免带上地址或密钥。</li>
 * </ol>
 * 该文本只进流水与 obs，不进用户可见文案；上层正文仍由各域的固定话术决定。
 */
public final class MeterRemarks {

    /** 上游非 2xx 的固定前缀，与 ACL 层 {@code UpstreamHttpException} 保持一致 */
    private static final String UPSTREAM_PREFIX = "上游返回 HTTP ";

    private MeterRemarks() {
    }

    /**
     * @param error 模型调用失败，可空
     * @return 最长 500 的备注
     */
    public static String from(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TrialFailure failure && failure.getMessage() != null && !failure.getMessage().isBlank()) {
                return clip(failure.getMessage());
            }
            // 上游拒绝的具体原因（图片过大、mime 不支持…）比类名有用得多，且已在 ACL 侧脱敏
            String message = cursor.getMessage();
            if (message != null && message.trim().startsWith(UPSTREAM_PREFIX)) {
                return clip(message);
            }
            if (cursor.getCause() == null || cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        String name = cursor.getClass().getSimpleName();
        return name == null || name.isBlank() ? "模型调用失败" : clip(name);
    }

    private static String clip(String text) {
        String value = text.trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
