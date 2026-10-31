package com.agenthub.runtime.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按空闲时长收口陈旧 RUNNING 并触发会话级 LONG 批抽。所属域：runtime。
 * 实际逻辑在 {@link TrialSessionCloser}：先 SUCCESS 终态，再幂等批抽；单轮有 N 上限。
 * 首次推迟可配，默认 1h，避免启动即打库。
 */
@Component
public class TrialSessionCloseJob {

    private final TrialSessionCloser closer;

    /**
     * @param closer 空闲收口 + 批抽（及取消结案）
     */
    public TrialSessionCloseJob(TrialSessionCloser closer) {
        this.closer = closer;
    }

    /**
     * 用户长时间不聊时，靠这里收口空闲会话并（在开关允许时）抽长期偏好。
     * initialDelay 独立可配；未配时仍默认 1h（与 cleanup-delay 历史行为一致）。
     */
    @Scheduled(fixedDelayString = "${agenthub.memory.session-close-delay-ms:60000}",
            initialDelayString = "${agenthub.memory.session-close-initial-delay-ms:3600000}")
    public void sweep() {
        closer.sweep();
    }
}
