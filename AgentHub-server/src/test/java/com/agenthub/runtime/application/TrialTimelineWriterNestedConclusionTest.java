package com.agenthub.runtime.application;

import com.agenthub.runtime.application.router.NestedRoute;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 目标方案「汇总结论」落库测试。所属域：runtime 的 application 层。
 *
 * <p>钉住一条硬口径：目标方案（另一套编排方案）自己的真汇总，必须紧跟在它分下去的
 * 各子专家之后写成「方案结论：X」+ 汇总推理。漏了这条，回放时前端画不出汇总那条，
 * 用户看到的就是「综合台结论出过又消失」。
 *
 * <p>全部 mock、不连库、不出站模型。
 */
class TrialTimelineWriterNestedConclusionTest {

    private TrialTimelineWriter timeline;

    @BeforeEach
    void setUp() {
        RunEventRepository repo = mock(RunEventRepository.class);
        when(repo.insert(any(RunEvent.class))).thenReturn(1L);
        timeline = new TrialTimelineWriter(new TrialEventWriter(repo, new TrialProperties()));
    }

    /**
     * 方案段带着真汇总结论：标记行必须排在它的各子专家之后，推理紧随标记。
     */
    @Test
    void nestedConclusionIsWrittenAfterSchemeChildren() {
        RouterTrialExecutor.RouteSegment scheme = new RouterTrialExecutor.RouteSegment(
                "路由到 企业综合台（enterprise），依据：用户点名了该方案", turn("方案总答"), null,
                List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度", turn("制度要点"), null)),
                "先分给制度知识专家", null, "企业综合台", "把两位子专家的结论合成一段总述");
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "多意图路由×2", "已拆成 2 个问题", List.of(scheme), List.of(), turn("拼接终答"), null);

        List<RunEvent> written = new ArrayList<>();
        timeline.appendRouterSegments(6L, 1, routed, written);

        assertTrue(textsOf(written).contains("方案结论：企业综合台"), textsOf(written).toString());
        // 回放要按参与者行认人，故序列里会多出 participant 行；顺序断言只看可见文案
        List<RunEvent> shown = visibleEventsOf(written);
        // 顺序：方案路由 → 方案分流推理 → 子专家路由 → 方案结论 → 汇总推理
        assertEquals("路由到 企业综合台（enterprise），依据：用户点名了该方案", shown.get(1).getSummary());
        assertEquals("先分给制度知识专家", shown.get(2).getSummary());
        assertEquals("路由到 制度知识专家（policy），依据：查制度", shown.get(3).getSummary());
        // 子专家自己的终稿紧跟在他的路由之后，不并进方案汇总
        assertEquals(EventType.ASSISTANT.code(), shown.get(4).getEventType());
        assertEquals("制度要点", shown.get(4).getContentText());
        assertEquals("方案结论：企业综合台", shown.get(5).getSummary());
        assertEquals("把两位子专家的结论合成一段总述", shown.get(6).getSummary());
        assertEquals(EventType.REASONING.code(), shown.get(6).getEventType());
        // 汇总正文紧跟标记：回放与直播终态读的是同一段字，不再只剩推理
        assertEquals(EventType.ASSISTANT.code(), shown.get(7).getEventType());
        assertEquals("方案总答", shown.get(7).getContentText());
    }

    /**
     * 多问时外层终答是拼接稿，且后一问的路由在汇总之后。
     * 汇总正文必须先于后一问落库，回放才不会只看到拼接稿。
     */
    @Test
    void summaryBodyPrecedesTheNextQuestion() {
        RouterTrialExecutor.RouteSegment scheme = new RouterTrialExecutor.RouteSegment(
                "路由到 综合台（scheme），依据：整理制度", turn("汇总正文：年假规则与报告已就绪"), null,
                List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度", turn("制度要点"), null)),
                null, null, "综合台", "收成一段");
        RouterTrialExecutor.RouteSegment flow = new RouterTrialExecutor.RouteSegment(
                "路由到 制度要点与报告（wf）", turn("工作流已跑完"), null);
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "已拆成 2 个问题", "已拆成 2 个问题", List.of(scheme, flow), List.of(),
                turn("### 综合台\n汇总正文：年假规则与报告已就绪"), null);

        List<RunEvent> written = new ArrayList<>();
        timeline.appendRouterSegments(6L, 1, routed, written);

        List<String> shown = textsOf(visibleEventsOf(written));
        int mark = shown.indexOf("方案结论：综合台");
        assertTrue(mark >= 0, shown.toString());
        assertTrue(shown.contains("制度要点") && shown.indexOf("制度要点") < mark, shown.toString());
        assertEquals("汇总正文：年假规则与报告已就绪", shown.get(mark + 2));
        assertTrue(shown.indexOf("路由到 制度要点与报告（wf）") > mark + 2, shown.toString());
    }

    /**
     * 外层自己已有同名真汇总（单意图直跑同一个方案）：内层不再写一遍，避免两条同名汇总。
     */
    @Test
    void nestedConclusionGivesWayToOuterConclusion() {
        RouterTrialExecutor.RouteSegment scheme = new RouterTrialExecutor.RouteSegment(
                "路由到 企业综合台（enterprise），依据：用户点名了该方案", turn("方案总答"), null,
                List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度", turn("制度要点"), null)),
                null, null, "企业综合台", "内层汇总推理");
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "路由到 企业综合台", null, List.of(scheme), List.of(), turn("终答"), null)
                .withNotes(null, "企业综合台", "外层汇总推理");

        List<RunEvent> written = new ArrayList<>();
        timeline.appendRouterSegments(6L, 1, routed, written);

        long marks = textsOf(written).stream().filter("方案结论：企业综合台"::equals).count();
        assertEquals(1L, marks, "只允许一条汇总结论标记");
    }

    /**
     * 外层结论是另一个名字（入口把问题分给了方案 B）：两条都要落，方案 B 那条才不会被收尾丢掉。
     */
    @Test
    void nestedConclusionStaysWhenOuterHasAnotherName() {
        RouterTrialExecutor.RouteSegment scheme = new RouterTrialExecutor.RouteSegment(
                "路由到 企业综合台（enterprise），依据：用户点名了该方案", turn("方案总答"), null,
                List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度", turn("制度要点"), null)),
                null, null, "企业综合台", "内层汇总推理");
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "已判定 2 个意图", null, List.of(scheme), List.of(), turn("入口汇总"), null)
                .withNotes(null, "默认接待", "入口汇总推理");

        List<RunEvent> written = new ArrayList<>();
        timeline.appendRouterSegments(6L, 1, routed, written);

        assertEquals(List.of("方案结论：企业综合台", "方案结论：默认接待"),
                textsOf(written).stream().filter((text) -> text.startsWith("方案结论：")).toList());
    }

    /**
     * 没有真汇总（CONCAT / 降级）：一条「方案结论」都不写，绝不假造汇总块。
     */
    @Test
    void noConclusionMarkWhenSchemeDidNotReallySummarize() {
        RouterTrialExecutor.RouteSegment scheme = new RouterTrialExecutor.RouteSegment(
                "路由到 企业综合台（enterprise），依据：用户点名了该方案", turn("拼接终答"), null,
                List.of(new NestedRoute("路由到 制度知识专家（policy），依据：查制度", turn("制度要点"), null)),
                null, null, null, null);
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "路由到 企业综合台", null, List.of(scheme), List.of(), turn("拼接终答"), null);

        List<RunEvent> written = new ArrayList<>();
        timeline.appendRouterSegments(6L, 1, routed, written);

        assertTrue(textsOf(written).stream().noneMatch((text) -> text.startsWith("方案结论：")),
                textsOf(written).toString());
    }

    /**
     * 多问收口走同一条结论通道：标记在全部车间路由之后，正文是要点而不是拼接稿。
     */
    @Test
    void roundCloseoutFollowsSchemeCardsAndIsNotTheConcatDraft() {
        String closeout = "文件车间\n目录清单已列出。\n\n数据车间\n汇总表已写出。";
        RouterTrialExecutor.RouteSegment file = new RouterTrialExecutor.RouteSegment(
                "路由到 文件车间（ws_file）", turn("### 清单专家\n目录清单表已列出全部文件。"), null);
        RouterTrialExecutor.RouteSegment data = new RouterTrialExecutor.RouteSegment(
                "路由到 数据车间（ws_data）", turn("### 汇总专家\n数据汇总很长。"), null);
        RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                "已拆成 2 个问题", "已拆成 2 个问题", List.of(file, data), List.of(), turn(closeout), null)
                .withNotes(null, "企业综合台", null);

        List<RunEvent> written = new ArrayList<>();
        int seq = timeline.appendRouterSegments(6L, 1, routed, written);
        assertTrue(TrialTimelineWriter.owesRoundAssistant(routed));
        written.add(timeline.assistant(6L, seq, routed.turn(), false));

        List<RunEvent> shown = visibleEventsOf(written);
        int mark = -1;
        for (int i = 0; i < shown.size(); i++) {
            if ("方案结论：企业综合台".equals(shown.get(i).getSummary())) {
                mark = i;
            }
        }
        assertTrue(mark > shownIndex(shown, "路由到 数据车间（ws_data）"), textsOf(shown).toString());
        assertEquals(EventType.ASSISTANT.code(), shown.get(mark + 1).getEventType());
        assertEquals(closeout, shown.get(mark + 1).getContentText());
        assertTrue(shown.stream().anyMatch((event) -> event.getContentText() != null
                && event.getContentText().contains("### 清单专家")));
    }

    /** 可见事件里某句摘要的下标；没有时为 -1。 */
    private static int shownIndex(List<RunEvent> events, String summary) {
        for (int i = 0; i < events.size(); i++) {
            if (summary.equals(events.get(i).getSummary())) {
                return i;
            }
        }
        return -1;
    }

    /** 事件明文序列（摘要是短句，足够断言顺序）。 */
    private static List<String> textsOf(List<RunEvent> events) {
        List<String> out = new ArrayList<>(events.size());
        for (RunEvent event : events) {
            out.add(event.getSummary());
        }
        return out;
    }

    /**
     * 去掉 participant 行后的事件序列：身份行是回放认人的依据，
     * 但不属于用户可见文案，顺序断言不应被它挤位。
     *
     * @param events 全部落库事件
     * @return 只看可见文案的事件
     */
    private static List<RunEvent> visibleEventsOf(List<RunEvent> events) {
        List<RunEvent> out = new ArrayList<>(events.size());
        for (RunEvent event : events) {
            if (!EventType.PARTICIPANT.code().equals(event.getEventType())) {
                out.add(event);
            }
        }
        return out;
    }

    /** 造一条终答：只需要正文与 traces 为空即可。 */
    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        return turn;
    }
}
