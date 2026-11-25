package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 时间线在路由 / 思考之前写入 participant，正文仍是纯文本。所属域：runtime。
 */
@ExtendWith(MockitoExtension.class)
class TrialTimelineWriterParticipantTest {

    @Mock
    private RunEventRepository events;

    private final List<RunEvent> saved = new ArrayList<>();
    private TrialTimelineWriter timeline;

    @BeforeEach
    void setUp() {
        when(events.insert(any(RunEvent.class))).thenAnswer(invocation -> {
            RunEvent event = invocation.getArgument(0);
            saved.add(event);
            return (long) saved.size();
        });
        timeline = new TrialTimelineWriter(new TrialEventWriter(events, new TrialProperties()));
    }

    @Test
    void participantRowPrecedesRouteAndReasoningStaysPlainText() {
        ParticipantView gateway = ParticipantView.gateway("gw-1", "默认接待");
        ParticipantView expert = ParticipantView.agent("p-draft", "文稿起草", "draft", 1);
        TrialTurn child = new TrialTurn();
        TrialTrace reasoning = new TrialTrace();
        reasoning.setType(EventType.REASONING);
        reasoning.setContent("先列提纲");
        child.getTraces().add(reasoning);
        RouterTrialExecutor.RouteSegment segment = new RouterTrialExecutor.RouteSegment(
                "路由到 文稿起草（draft），依据：纪要", child, null).withParticipant(expert);
        TrialTurn answer = new TrialTurn();
        answer.setAssistantText("### 文稿起草\n正文");
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "路由到 文稿起草", null, List.of(segment), List.of(), answer, null)
                .withGateway(gateway)
                .withNotes("判定一下", "默认接待", "汇总思考");

        timeline.appendRouterSegments(1L, 1, routed, new ArrayList<>());

        assertEquals("participant", saved.get(0).getEventType());
        assertTrue(saved.get(0).getContentText().contains("\"id\":\"gw-1\""));
        assertTrue(saved.get(0).getContentText().contains("\"begin\":true"));
        assertEquals("reasoning", saved.get(1).getEventType());
        assertEquals("判定一下", saved.get(1).getContentText());

        int expertAt = indexOfTypeAfter(0, "participant", "p-draft");
        assertTrue(expertAt > 0);
        assertEquals("route", saved.get(expertAt + 1).getEventType());
        assertEquals("reasoning", saved.get(expertAt + 2).getEventType());
        assertEquals("先列提纲", saved.get(expertAt + 2).getContentText());

        int conclusionAt = indexOfTypeAfter(expertAt, "participant", "c-默认接待");
        assertEquals("route", saved.get(conclusionAt + 1).getEventType());
        assertTrue(saved.get(conclusionAt + 1).getContentText().startsWith("方案结论："));
        assertEquals(gateway, ParticipantJson.parse(saved.get(0).getContentText()));
    }

    /**
     * 两位专家各有终稿：写在各自 begin 之后、下一位之前。
     * 整轮拼接稿不落库，避免贴到最后一位的结果框。有文件时终稿与文件同段，文字在前。
     */
    @Test
    void eachExpertFinalStaysOnThatExpert() {
        ParticipantView policy = ParticipantView.agent("p-policy", "制度知识专家", "policy", 1);
        ParticipantView writer = ParticipantView.agent("p-writer", "报告撰写专家", "writer", 1);
        TrialTurn policyTurn = new TrialTurn();
        policyTurn.setAssistantText("撰写处理原则：不虚构、不补全");
        TrialTrace tool = new TrialTrace();
        tool.setType(EventType.TOOL);
        tool.setName("searchKnowledgeBase");
        tool.setSuccess(true);
        tool.setContent("\"[{\\\"content\\\":\\\"制度原文\\\"}]\"");
        policyTurn.getTraces().add(tool);
        TrialTurn reportTurn = new TrialTurn();
        reportTurn.setAssistantText("报告已起草完成并保存到工作区");
        reportTurn.getFiles().add(new GeneratedFile("制度知识要点结构化报告.md", 9500, "p-writer", "报告撰写专家"));
        RouterTrialExecutor.RouteSegment first = new RouterTrialExecutor.RouteSegment(
                "路由到 制度知识专家（policy）", policyTurn, null).withParticipant(policy);
        RouterTrialExecutor.RouteSegment second = new RouterTrialExecutor.RouteSegment(
                "路由到 报告撰写专家（writer）", reportTurn, null).withParticipant(writer);
        TrialTurn concat = new TrialTurn();
        concat.setAssistantText("### 制度知识专家\n撰写处理原则\n\n### 报告撰写专家\n报告已起草");
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "已分发", null, java.util.List.of(first, second), java.util.List.of(), concat, null);

        timeline.appendRouterSegments(9L, 1, routed, new ArrayList<>());

        int policyAt = indexOfTypeAfter(0, "participant", "p-policy");
        int writerAt = indexOfTypeAfter(policyAt + 1, "participant", "p-writer");
        assertTrue(policyAt >= 0 && writerAt > policyAt);
        int policyAnswer = indexOfAssistant(policyAt + 1, writerAt, "撰写处理原则：不虚构、不补全");
        assertTrue(policyAnswer > policyAt && policyAnswer < writerAt);
        assertEquals("tool", saved.get(policyAnswer - 1).getEventType());
        int reportAnswer = indexOfAssistant(writerAt + 1, saved.size(), "报告已起草完成并保存到工作区");
        assertTrue(reportAnswer > writerAt);
        assertEquals("file", saved.get(reportAnswer + 1).getEventType());
        assertTrue(saved.get(reportAnswer + 1).getContentText().contains("制度知识要点结构化报告.md"));
        assertTrue(saved.stream().noneMatch(event ->
                "assistant".equals(event.getEventType())
                        && event.getContentText() != null
                        && event.getContentText().contains("### 报告撰写专家")));
        assertTrue(!TrialTimelineWriter.owesRoundAssistant(routed));
    }

    private int indexOfAssistant(int from, int until, String text) {
        for (int i = from; i < until && i < saved.size(); i++) {
            RunEvent event = saved.get(i);
            if ("assistant".equals(event.getEventType()) && text.equals(event.getContentText())) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfTypeAfter(int from, String type, String idFragment) {
        for (int i = from; i < saved.size(); i++) {
            RunEvent event = saved.get(i);
            if (type.equals(event.getEventType()) && event.getContentText() != null
                    && event.getContentText().contains(idFragment)) {
                return i;
            }
        }
        return -1;
    }
}
