package com.agenthub.runtime.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import org.springframework.stereotype.Component;

/**
 * 试跑会话是否属于当前平台用户。所属域：runtime。
 * 只认 user_id，管理员也不能凭 sessionId 看别人的试跑。一期不做租户隔离。
 */
@Component
public class TrialAccess {

    private final RunSessionRepository sessions;
    private final OperatorContext operatorContext;

    /**
     * @param sessions        会话
     * @param operatorContext 当前登录人
     */
    public TrialAccess(RunSessionRepository sessions, OperatorContext operatorContext) {
        this.sessions = sessions;
        this.operatorContext = operatorContext;
    }

    /**
     * @param id 会话主键
     * @return 当前用户自己的会话
     * @throws BizException 不存在，或 user_id 不是当前用户
     */
    public RunSession require(Long id) {
        RunSession session = sessions.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "试跑会话不存在"));
        long userId = operatorContext.requireUserId();
        if (session.getUserId() == null || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能查看别人的试跑会话");
        }
        return session;
    }
}
