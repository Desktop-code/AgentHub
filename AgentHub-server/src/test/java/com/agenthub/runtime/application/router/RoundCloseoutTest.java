package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多问并行的整轮收口。所属域：runtime。
 *
 * <p>不连库、不打模型。钉住：拼接稿不能整段冒充汇总，真汇总要点要留下来，
 * 直播标记用入口名。
 */
class RoundCloseoutTest {

    @Test
    void concatDraftBecomesOnePointAndRealSummaryStays() {
        ChildOutcome file = outcome("文件车间",
                "### 清单专家\n目录清单表已列出全部文件。\n\n### 比对专家\n差异已经标出。", null);
        ChildOutcome data = outcome("数据车间", "数据汇总表已按部门写完。", "数据车间");
        String text = RoundCloseout.textOf(List.of(file, data));
        assertTrue(text.startsWith("文件车间\n目录清单表已列出全部文件。"), text);
        assertTrue(text.contains("数据车间\n数据汇总表已按部门写完。"), text);
        assertFalse(text.contains("###"), text);
        assertFalse(text.contains("比对专家"), text);
        assertFalse(text.contains("差异已经标出"), text);
    }

    @Test
    void separateAnswersPushesEntryConclusionInsteadOfConcat() {
        ChildOutcome file = outcome("文件车间",
                "### 清单专家\n目录清单表已列出全部文件。", null);
        List<String> routes = new ArrayList<>();
        List<String> assistants = new ArrayList<>();
        TrialProgress progress = new TrialProgress() {
            @Override
            public void phase(String phase) {
            }

            @Override
            public void route(String summary) {
                routes.add(summary);
            }

            @Override
            public void reasoning(String delta) {
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
            }

            @Override
            public void assistant(String delta) {
                assistants.add(delta);
            }
        };
        AggregateOutcome aggregate = new AnswerAggregator(null, null, null)
                .separateAnswers("企业综合台", List.of(file), progress);
        assertEquals(List.of("方案结论：企业综合台"), routes);
        assertEquals("企业综合台", aggregate.conclusionName());
        assertEquals(1, assistants.size());
        assertFalse(assistants.get(0).contains("###"), assistants.get(0));
        assertEquals(assistants.get(0), aggregate.turn().getAssistantText());
        assertTrue(aggregate.turn().getAssistantText().contains("目录清单表已列出全部文件。"));
    }

    /** 一个车间结果。conclusionName 为空表示这一问没有真汇总。 */
    private static ChildOutcome outcome(String name, String text, String conclusionName) {
        AgentDef agent = new AgentDef();
        agent.setName(name);
        AgentRoute route = new AgentRoute();
        route.setRouteKey(name);
        Candidate candidate = new Candidate(route, agent, null, null);
        RoutedIntent intent = new RoutedIntent(candidate, name, null);
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text);
        if (conclusionName == null) {
            return ChildOutcome.ok(intent, List.of(), turn);
        }
        return ChildOutcome.okScheme(intent, List.of(), turn, List.of(), null, conclusionName, null);
    }
}
