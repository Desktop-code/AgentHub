package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流结果 → 工作台文案的口径单测（刀5B）。所属域：workflow 的 domain。
 *
 * <p>这些文案会直接进工作台对话框，因此几条底线必须锁住：
 * 成功优先取 answer / result / output 文本；失败给原因；人工暂停只指路不做审批；
 * 未结束 run 明确「本次没有重复发起」。
 */
class WorkflowOutcomeTextsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("成功：优先取 END 节点里的 answer 文本")
    void successPrefersAnswerText() {
        WorkflowRun run = run("SUCCESS", 3, null,
                "{\"end\":{\"answer\":\"已为你生成还款提醒\"}}");

        String text = WorkflowOutcomeTexts.assistantText(def(), run, JSON);

        assertThat(text).isEqualTo("已为你生成还款提醒");
    }

    @Test
    @DisplayName("成功：answer 缺失时按 result / output 兜底")
    void successFallsBackToResultKey() {
        WorkflowRun run = run("SUCCESS", 1, null,
                "{\"end\":{\"result\":{\"text\":\"工单已创建 #1024\"}}}");

        assertThat(WorkflowOutcomeTexts.assistantText(def(), run, JSON)).isEqualTo("工单已创建 #1024");
    }

    @Test
    @DisplayName("成功但没有可读文本：给简洁摘要，不把整图 node 输出倒进对话框")
    void successWithoutTextGivesBriefSummary() {
        WorkflowRun run = run("SUCCESS", 2, null, "{\"end\":{\"nodeCode\":\"end\",\"input\":{\"a\":1}}}");

        String text = WorkflowOutcomeTexts.assistantText(def(), run, JSON);

        assertThat(text).contains("已运行完成").contains("v2");
        assertThat(text).doesNotContain("nodeCode");
    }

    @Test
    @DisplayName("失败：直接给 error_summary 的原因")
    void failedUsesErrorSummary() {
        WorkflowRun run = run("FAILED", 1, "订单服务超时", "{}");

        assertThat(WorkflowOutcomeTexts.assistantText(def(), run, JSON))
                .contains("运行失败").contains("订单服务超时");
    }

    @Test
    @DisplayName("取消：给取消原因")
    void cancelledUsesErrorSummary() {
        WorkflowRun run = run("CANCELLED", 1, null, null);

        assertThat(WorkflowOutcomeTexts.assistantText(def(), run, JSON))
                .contains("已取消").contains("运行已被取消");
    }

    @Test
    @DisplayName("人工暂停：只指路「编排 → 人工待办」，不在对话框里做审批")
    void pausedHumanPointsToTodoPage() {
        WorkflowRun run = run("PAUSED_HUMAN", 1, null, null);

        String text = WorkflowOutcomeTexts.assistantText(def(), run, JSON);

        assertThat(text).contains("等待人工处理").contains("编排 → 人工待办");
        assertThat(text).doesNotContain("通过").doesNotContain("驳回");
    }

    @Test
    @DisplayName("未结束 run：提示没有重复发起，区分运行中与等人工")
    void inFlightExplainsNoDuplicateRun() {
        String running = WorkflowOutcomeTexts.inFlightText("「报销审批」",
                run("RUNNING", 1, null, null));
        assertThat(running).contains("没有重复发起").contains("仍在运行");

        String paused = WorkflowOutcomeTexts.inFlightText("「报销审批」",
                run("PAUSED_HUMAN", 1, null, null));
        assertThat(paused).contains("没有重复发起").contains("等待人工处理").contains("人工待办");
    }

    @Test
    @DisplayName("输出解析失败只当作没有文本答案，不抛异常")
    void brokenOutputJsonIsTreatedAsNoAnswer() {
        assertThat(WorkflowOutcomeTexts.extractAnswer("{not-json", JSON)).isNull();
        assertThat(WorkflowOutcomeTexts.extractAnswer(null, JSON)).isNull();
    }

    @Test
    @DisplayName("续跑种子：只删平台等待提示段，用户看过的问句保留")
    void withoutWaitingHintKeepsQuestion() {
        String waiting = "你想用哪种语言实现？\n\n"
                + "工作流「小游戏研发」正在等待你的回复；请直接在本会话下方输入框接着说，"
                + "这句话会送回同一条运行继续往下走。";

        assertThat(WorkflowOutcomeTexts.withoutWaitingHint(waiting))
                .isEqualTo("你想用哪种语言实现？");
    }

    @Test
    @DisplayName("续跑种子：人工等待提示也不跟到下一段")
    void withoutWaitingHintDropsHumanHint() {
        String waiting = "已生成报销草稿。\n\n"
                + "工作流「报销审批」已运行到人工处理节点，正在等待人工处理。"
                + "请前往「编排 → 人工待办」处理；处理完成后结果会自动追加到本会话。";

        assertThat(WorkflowOutcomeTexts.withoutWaitingHint(waiting))
                .isEqualTo("已生成报销草稿。");
        // 只有提示时种子为空，续跑从新内容开始
        assertThat(WorkflowOutcomeTexts.withoutWaitingHint(
                "工作流「报销审批」正在等待你的回复；请直接在本会话下方输入框接着说。")).isEmpty();
        assertThat(WorkflowOutcomeTexts.withoutWaitingHint(null)).isEmpty();
    }

    private static WorkflowDef def() {
        WorkflowDef def = new WorkflowDef();
        def.setId(88L);
        def.setCode("reimburse");
        def.setName("报销审批");
        return def;
    }

    private static WorkflowRun run(String status, Integer version, String errorSummary, String outputJson) {
        WorkflowRun run = new WorkflowRun();
        run.setId(1L);
        run.setWorkflowId(88L);
        run.setStatus(status);
        run.setReleaseVersion(version);
        run.setErrorSummary(errorSummary);
        run.setOutputJson(outputJson);
        return run;
    }
}
