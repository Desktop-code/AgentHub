package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 写入试跑事件。所属域：runtime。
 * summary 是短摘要；content_text 是展示正文。入库前都会脱敏，正文另按配置截断。
 */
@Component
public class TrialEventWriter {

    private static final Logger log = LoggerFactory.getLogger(TrialEventWriter.class);

    private final RunEventRepository events;
    private final TrialProperties properties;

    /**
     * @param events     事件仓储
     * @param properties content_text 上限
     */
    public TrialEventWriter(RunEventRepository events, TrialProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    /**
     * 摘要和正文来自同一段原文。route、system、error、用户输入和助手回复走这里。
     *
     * @param sessionId  会话
     * @param seq        会话内序号
     * @param type       步骤类型
     * @param text       原文，会被脱敏；摘要再截到 500，正文保留换行
     * @param prompt     可空的 prompt token
     * @param completion 可空的 completion token
     * @return 已入库的事件
     */
    public RunEvent append(Long sessionId, int seq, EventType type, String text, Integer prompt, Integer completion) {
        return append(sessionId, seq, type, text, text, prompt, completion);
    }

    /**
     * 摘要和正文不同时使用。工具摘要只放名称和成败，结果放正文。
     *
     * @param sessionId  会话
     * @param seq        会话内序号
     * @param type       步骤类型
     * @param summary    短摘要原文
     * @param content    展示正文，可空
     * @param prompt     可空的 prompt token
     * @param completion 可空的 completion token
     * @return 已入库的事件
     */
    public RunEvent append(Long sessionId, int seq, EventType type, String summary, String content,
                           Integer prompt, Integer completion) {
        RunEvent event = new RunEvent();
        event.setSessionId(sessionId);
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setSummary(TrialTexts.summary(summary));
        /*
         * 工作流卡片走单独的落库口径：正文是 JSON，一旦按普通文本截断就会变成半截字符串，
         * 刷新回放解析失败、整张卡消失（历史上正是「跑完刷新又变回询问」的根因）。
         * 这里先收成骨架（剥掉节点产出、压到限内），再整份落库，永不返回半截 JSON。
         */
        String body;
        if (type == EventType.WORKFLOW) {
            body = WorkflowRunJson.forStorage(content, contentLimit());
            if (body.isEmpty() && content != null && !content.isBlank()) {
                // 只记长度与位置，不记正文：卡片正文可能含业务内容
                log.warn("工作流卡片不可安全落库，已跳过 sessionId={} seq={} 原长度={}",
                        sessionId, seq, content.length());
            }
        } else {
            body = TrialTexts.content(content, contentLimit());
        }
        // 空正文不落空串，旧数据和「没有结果」都表现为 null
        event.setContentText(body.isEmpty() ? null : body);
        event.setTokenPrompt(prompt);
        event.setTokenCompletion(completion);
        event.setCreateTime(LocalDateTime.now());
        event.setId(events.insert(event));
        return event;
    }

    private int contentLimit() {
        int configured = properties.getContentLimitChars();
        return configured > 0 ? configured : TrialTexts.CONTENT_LIMIT;
    }
}
