package com.agenthub.runtime.infra.persistence;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 agent_run_event 回补对话窗口。所属域：runtime。
 * 只取用户和助手轮，工具实参不进窗口。
 */
@Component
public class RunEventTurnSource implements SessionTurnSource {

    private final RunEventRepository events;

    /**
     * @param events 试跑事件
     */
    public RunEventTurnSource(RunEventRepository events) {
        this.events = events;
    }

    /**
     * {@inheritDoc}
     * 优先正文，没有正文时用短摘要。
     */
    @Override
    public List<ContextTurn> replay(long sessionId) {
        List<ContextTurn> turns = new ArrayList<>();
        for (RunEvent event : events.listBySessionId(sessionId)) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            String role;
            if (EventType.USER.code().equals(event.getEventType())) {
                role = "user";
            } else if (EventType.ASSISTANT.code().equals(event.getEventType())) {
                role = "assistant";
            } else {
                continue;
            }
            String text = event.getContentText();
            if (text == null || text.isBlank()) {
                text = event.getSummary();
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            turns.add(ContextTurn.of(role, text));
        }
        return turns;
    }
}
