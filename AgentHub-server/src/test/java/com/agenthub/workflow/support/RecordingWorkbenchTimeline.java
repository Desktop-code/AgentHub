package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.service.WorkbenchTimelinePort;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作台时间线追写的测试替身。所属域：workflow 测试支撑。
 *
 * <p>只记录「哪个会话、什么类型、写了什么」，不做任何截断或脱敏——
 * 用例要断言的是<b>文案口径</b>（成功摘要 / 失败原因 / 人工等待提示），
 * 与真实实现（TrialEventWriter）的落库细节无关。
 */
public class RecordingWorkbenchTimeline implements WorkbenchTimelinePort {

    /** 一条追写记录：类型（system / assistant）+ 正文 */
    public record Line(long sessionId, String type, String text) {
    }

    private final List<Line> lines = new ArrayList<>();

    @Override
    public void appendSystem(long sessionId, String text) {
        lines.add(new Line(sessionId, "system", text));
    }

    @Override
    public void appendAssistant(long sessionId, String text) {
        lines.add(new Line(sessionId, "assistant", text));
    }

    /**
     * @return 已追写的全部记录（按写入顺序）
     */
    public List<Line> lines() {
        return lines;
    }

    /**
     * @return 已追写的助手正文（按顺序）
     */
    public List<String> assistantTexts() {
        return lines.stream().filter(line -> "assistant".equals(line.type())).map(Line::text).toList();
    }

    /**
     * @return 已追写的系统提示（按顺序）
     */
    public List<String> systemTexts() {
        return lines.stream().filter(line -> "system".equals(line.type())).map(Line::text).toList();
    }
}
