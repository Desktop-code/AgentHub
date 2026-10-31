package com.agenthub.runtime.model.acl.impl;

/**
 * 上游返回非 2xx。所属域：runtime（模型出站 ACL 的实现细节）。
 * 只保留状态码，响应体原文不进日志也不进前端；构造时仅判断响应体是否为空。
 */
final class UpstreamHttpException extends RuntimeException {

    /** HTTP 状态码，供脱敏文案使用 */
    private final int status;

    /**
     * @param status HTTP 状态码
     * @param body   响应体，仅判断是否为空，不透出内容
     */
    UpstreamHttpException(int status, String body) {
        super("上游返回 HTTP " + status + (body == null || body.isBlank() ? "" : "（含响应体）"));
        this.status = status;
    }

    /**
     * @return HTTP 状态码
     */
    int status() {
        return status;
    }
}
