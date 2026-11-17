package com.agenthub.runtime.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.domain.model.RunSession;

/**
 * 试跑发送门禁。所属域：runtime。
 * <p>
 * 用户取消，以及系统打断了没写完的一轮，不可再发。
 * RUNNING、历史 SUCCESS、FAILED，以及「已经写完却被启动收口标成 CANCELLED」的行都可以续聊。
 * 误标行不在这里改库，只放行。
 */
public final class TrialSendGate {

    private TrialSendGate() {
    }

    /**
     * @param session       当前会话
     * @param turnStillOpen 库状态为 CANCELLED 时，最后一轮是否还没写完
     * @throws BizException 用户取消，或系统打断了未写完的一轮
     */
    public static void assertSendable(RunSession session, boolean turnStillOpen) {
        if (SessionCancelFace.blocksAsCancelled(session, turnStillOpen)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "会话已取消");
        }
    }
}
