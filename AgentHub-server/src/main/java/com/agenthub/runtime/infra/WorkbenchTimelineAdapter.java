package com.agenthub.runtime.infra;

import com.agenthub.runtime.application.TrialEventWriter;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.workflow.domain.service.WorkbenchTimelinePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作台时间线追加端口的 runtime 实现。所属域：runtime 的 infra。
 *
 * <p>用途：工作流域在「人工待办处理完、run 到终态」这类<b>非本轮 SSE</b> 的时机，
 * 把结果主动追写进工作台会话，用户不必再发一句话才能看到。
 *
 * <p>实现纪律：
 * <ul>
 *   <li>复用现网事件写入（{@link TrialEventWriter}）：脱敏、截断、列宽口径完全一致；</li>
 *   <li>只追加事件，不改会话状态、不动记忆窗口、不写 obs；</li>
 *   <li>不建第二套总线：SSE 仍由前端刷新 / 轮询读到新事件，工作台无需额外通道。</li>
 * </ul>
 *
 * <p>并发说明：seq 取「同会话当前最大 + 1」。工作台同一会话的整轮写入由 {@code TrialFlight}
 * 串行化；本适配器只在人工待办结算等旁路触发，正常不会与在跑的一轮并发。
 * 极端并发下最坏结果只是两条事件同 seq（读侧按 seq,id 排序仍稳定），不影响内容。
 */
@Component
public class WorkbenchTimelineAdapter implements WorkbenchTimelinePort {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchTimelineAdapter.class);

    private final RunEventRepository events;
    private final TrialEventWriter writer;

    /**
     * @param events 事件仓储（取下一序号）
     * @param writer 事件落库入口（脱敏 / 截断）
     */
    public WorkbenchTimelineAdapter(RunEventRepository events, TrialEventWriter writer) {
        this.events = events;
        this.writer = writer;
    }

    /** {@inheritDoc} */
    @Override
    public void appendSystem(long sessionId, String text) {
        append(sessionId, EventType.SYSTEM, text);
    }

    /** {@inheritDoc} */
    @Override
    public void appendAssistant(long sessionId, String text) {
        append(sessionId, EventType.ASSISTANT, text);
    }

    /**
     * 统一落库：空文本不写，避免时间线出现空行。
     *
     * @param sessionId 会话 id
     * @param type      事件类型
     * @param text      正文（写入侧会脱敏并截断）
     */
    private void append(long sessionId, EventType type, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            writer.append(sessionId, events.nextSeq(sessionId), type, text, null, null);
        } catch (RuntimeException ex) {
            // 时间线是旁路：写失败只告警，不能把上游的人工审批 / 运行状态改坏
            log.warn("追写工作台时间线失败 sessionId={} type={} cause={}",
                    sessionId, type, ex.getClass().getSimpleName());
        }
    }
}
