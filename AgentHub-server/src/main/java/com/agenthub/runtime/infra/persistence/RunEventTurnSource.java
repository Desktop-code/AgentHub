package com.agenthub.runtime.infra.persistence;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.AttachmentTexts;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import com.agenthub.runtime.domain.service.UserMessageImages;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 agent_run_event 回补对话窗口。所属域：runtime。
 * 只取用户和助手轮，工具实参不进窗口。
 *
 * <p>{@link #replay} 只回用户原话，给长期记忆抽取用。
 * {@link #replayForModel} 会在原话后面附上当时写下的附件正文，刷新或续问仍能读到文件。
 */
@Component
public class RunEventTurnSource implements SessionTurnSource {

    private final RunEventRepository events;
    private final TrialUserFileStorage files;

    /**
     * @param events 运行事件
     * @param files  附件正文旁路；没有旁路文件时模型回放退回原话
     */
    public RunEventTurnSource(RunEventRepository events, TrialUserFileStorage files) {
        this.events = events;
        this.files = files;
    }

    /**
     * {@inheritDoc}
     * 优先正文，没有正文时用短摘要。不附带文件正文。
     */
    @Override
    public List<ContextTurn> replay(long sessionId) {
        return replay(sessionId, false);
    }

    /**
     * {@inheritDoc}
     * 用户轮带上已解析的附件正文。只发文件、当时没留下正文的旧消息仍跳过。
     */
    @Override
    public List<ContextTurn> replayForModel(long sessionId) {
        return replay(sessionId, true);
    }

    private List<ContextTurn> replay(long sessionId, boolean withFiles) {
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
            // 有图时摘要是 ahimg 清单，不能当成用户原话灌进记忆窗口
            String text = userText(event, withFiles && "user".equals(role));
            if (text.isBlank()) {
                continue;
            }
            turns.add(ContextTurn.of(role, text));
        }
        return turns;
    }

    /**
     * 用户原话。模型回放时，若摘要里有文件且旁路已写下正文，附在原话后面。
     *
     * @param event     事件
     * @param withFiles 是否拼附件正文
     * @return 窗口里的这一轮文本
     */
    private String userText(RunEvent event, boolean withFiles) {
        String spoken = UserMessageImages.questionText(event.getContentText(), event.getSummary());
        if (!withFiles || !UserMessageFiles.looksLikeManifest(event.getSummary())) {
            return spoken;
        }
        List<String> sections = new ArrayList<>();
        for (UserMessageFiles.Item item : UserMessageFiles.itemsOf(event.getSummary())) {
            if (item == null || !"file".equals(item.kind())) {
                continue;
            }
            String excerpt = files.readExcerpt(item.url());
            if (excerpt != null && !excerpt.isBlank()) {
                sections.add(excerpt);
            }
        }
        return AttachmentTexts.compose(spoken, sections).text();
    }
}
