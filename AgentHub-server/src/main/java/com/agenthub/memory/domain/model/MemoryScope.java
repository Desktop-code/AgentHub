package com.agenthub.memory.domain.model;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 记忆范围。所属域：memory。
 * SHORT 跟会话，LONG 是用户偏好，AGENT 是该 Agent 的工作记忆，三者不混写。
 */
public enum MemoryScope {

    /** 本会话临时事实，Redis 与 MySQL 双写 */
    SHORT,
    /** 跨会话仍成立的偏好和稳定事实 */
    LONG,
    /** 跟 Agent 走的工作记忆，控制台维护为主 */
    AGENT;

    /**
     * @param raw 请求里的范围
     * @return 合法枚举
     * @throws BizException 空值或不在枚举内
     */
    public static MemoryScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆范围不能为空");
        }
        try {
            return MemoryScope.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆范围只能是 SHORT、LONG 或 AGENT");
        }
    }
}
