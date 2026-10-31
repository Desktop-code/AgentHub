package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;

/**
 * 试跑发送门禁。所属域：runtime。
 * 仅 CANCELLED 不可再发；RUNNING / 历史 SUCCESS / FAILED 均可续聊。
 */
public final class TrialSendGate {

    private TrialSendGate() {
    }

    /**
     * @param session 当前会话
     * @throws BizException 会话已取消
     */
    public static void assertSendable(RunSession session) {
        if (session != null && RunStatus.CANCELLED.name().equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话已取消");
        }
    }
}
