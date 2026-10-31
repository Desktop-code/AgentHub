package com.agenthub.runtime.model.acl.impl;

import java.time.Duration;

/**
 * 流式空闲超时。所属域：runtime（模型出站 ACL 的实现细节）。
 * 单独成类型，便于与真实错误区分并映射为 TIMEOUT。
 */
final class UpstreamIdleTimeoutException extends RuntimeException {

    /**
     * @param timeout 超时阈值
     */
    UpstreamIdleTimeoutException(Duration timeout) {
        super("上游 " + timeout.toMillis() + "ms 无数据");
    }
}
