package com.agenthub.runtime.model.acl.impl;

/**
 * 上游返回非 2xx。所属域：runtime（模型出站 ACL 的实现细节）。
 *
 * <p>message 里带「状态码 + 脱敏响应体摘要」：
 * 只看状态码时，400 可能是图片过大、mime 不支持、字段不合法或模型不支持图片，
 * 处置方式完全不同；而这层异常是唯一还能看到 body 的位置，出了本类就只剩一句人话。
 *
 * <p>安全约束：摘要由 {@link UpstreamBodyBrief} 生成——只取 error.message 之类的短字段，
 * 抹掉 Bearer / Key，并对连续 base64 做整体替换，禁止把请求回显里的图片原文带出去。
 */
final class UpstreamHttpException extends RuntimeException {

    /** HTTP 状态码，供脱敏文案使用 */
    private final int status;

    /** 脱敏后的响应体摘要；空体时为 null */
    private final String bodyBrief;

    /**
     * @param status HTTP 状态码
     * @param body   响应体原文；只取摘要，不整段落库或日志
     */
    UpstreamHttpException(int status, String body) {
        super(buildMessage(status, UpstreamBodyBrief.of(body)));
        this.status = status;
        // 摘要只算一次：正文可能有几十 KB，重复正则替换没必要
        String brief = UpstreamBodyBrief.of(body);
        this.bodyBrief = brief.isBlank() ? null : brief;
    }

    /**
     * 拼异常文案。摘要为空时保持旧形态，避免出现「HTTP 400：」这种半截句子。
     *
     * @param status    状态码
     * @param bodyBrief 脱敏摘要，可空
     * @return 带原因的可读文案
     */
    private static String buildMessage(int status, String bodyBrief) {
        return bodyBrief == null || bodyBrief.isBlank()
                ? "上游返回 HTTP " + status
                : "上游返回 HTTP " + status + "：" + bodyBrief;
    }

    /**
     * @return HTTP 状态码
     */
    int status() {
        return status;
    }

    /**
     * @return 脱敏响应体摘要；无响应体时为 null
     */
    String bodyBrief() {
        return bodyBrief;
    }
}
