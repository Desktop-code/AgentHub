package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工具成功判定的源端用例。所属域：runtime 的 infra。
 *
 * <p>钉住线上真实踩到的坑：{@code searchKnowledgeBase} 正常命中、{@code workspaceListFiles}
 * 返回「还没有任何文件」，结果块正文是健康内容，但 state 不是 SUCCESS。
 * 旧判定把它们记成连续失败，三次后 {@code consecutive_fail_abort} 把该专家后续同类工具全部短路。
 *
 * <p>全部 mock：不连库、不起服务、不打模型。
 */
class BudgetingAgentToolTest {

    /**
     * 非终态但正文健康：连调多次也不得计入连续失败，更不得触发熔断。
     */
    @Test
    void healthyBodyWithNonSuccessStateCountsAsSuccess() {
        RunBudget budget = new RunBudget(limits(2));
        AtomicReference<ToolCallParam> lastParam = new AtomicReference<>();
        BudgetingAgentTool tool = new BudgetingAgentTool(
                stub(lastParam, result(ToolResultState.RUNNING,
                        "[{\"chunkId\":\"1\",\"text\":\"制度要点\"}]")), budget, "制度知识专家");

        // 连续三次健康结果：阈值是 2，按旧判定第二次就会抛 consecutive_fail_abort
        assertDoesNotThrow(() -> tool.callAsync(null).block());
        assertDoesNotThrow(() -> tool.callAsync(null).block());
        assertDoesNotThrow(() -> tool.callAsync(null).block());
    }

    /**
     * 空正文 + 缺省 state：没有任何失败证据，同样按成功计（空目录提示走的是正文，不是异常）。
     */
    @Test
    void emptyBodyWithoutErrorStateStillCountsAsSuccess() {
        RunBudget budget = new RunBudget(limits(2));
        BudgetingAgentTool tool = new BudgetingAgentTool(
                stub(new AtomicReference<>(), result(null, "")), budget, "报告撰写专家");

        assertDoesNotThrow(() -> tool.callAsync(null).block());
        assertDoesNotThrow(() -> tool.callAsync(null).block());
        assertDoesNotThrow(() -> tool.callAsync(null).block());
    }

    /**
     * ERROR 仍是失败：连续达到阈值时照样熔断，且熔断后后续路直接短路。
     */
    @Test
    void errorStateStillTripsConsecutiveFailAbort() {
        RunBudget budget = new RunBudget(limits(1));
        BudgetingAgentTool tool = new BudgetingAgentTool(
                stub(new AtomicReference<>(), result(ToolResultState.ERROR, "知识库连接失败")), budget, "制度知识专家");

        assertThrows(TrialFailure.class, () -> tool.callAsync(null).block());
        // 已熔断：后续同类路不再发起调用
        assertThrows(TrialFailure.class, () -> tool.callAsync(null).block());
    }

    /**
     * 抛异常仍按失败计：这是唯一「有异常」的形态，不能被本刀的放宽规则吞掉。
     */
    @Test
    void thrownErrorStillCountsAsFailure() {
        RunBudget budget = new RunBudget(limits(1));
        AgentTool boom = new StubTool(null) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.error(new IllegalStateException("上游 500"));
            }
        };
        BudgetingAgentTool tool = new BudgetingAgentTool(boom, budget, "制度知识专家");

        assertThrows(TrialFailure.class, () -> tool.callAsync(null).block());
    }

    /**
     * 造一个固定返回给定结果块的委托工具。
     *
     * @param holder 记录最后一次入参（本用例只用它占位，不做断言）
     * @param result 固定结果
     * @return 桩工具
     */
    private static AgentTool stub(AtomicReference<ToolCallParam> holder, ToolResultBlock result) {
        return new StubTool(holder) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                holder.set(param);
                return Mono.just(result);
            }
        };
    }

    /**
     * 造一个结果块。
     *
     * @param state 引擎状态；null 表示旧工具不设 state
     * @param text  正文
     * @return 结果块
     */
    private static ToolResultBlock result(ToolResultState state, String text) {
        List<ContentBlock> output = text == null || text.isEmpty()
                ? List.of()
                : List.of(TextBlock.builder().text(text).build());
        return new ToolResultBlock("call-1", "searchKnowledgeBase", output, null, state);
    }

    /**
     * 生效限制：连续失败阈值可调，其余给足，避免次数 / 墙钟干扰断言。
     *
     * @param consecutiveFailAbort 连续失败阈值
     * @return 限制
     */
    private static EffectiveLimits limits(int consecutiveFailAbort) {
        return new EffectiveLimits(60_000L, 8, 30_000L, 1, 50,
                30_000L, 50, consecutiveFailAbort, 20_000);
    }

    /** 最小委托工具：只提供名字与参数，行为由子类覆写。 */
    private static class StubTool implements AgentTool {

        private final AtomicReference<ToolCallParam> holder;

        StubTool(AtomicReference<ToolCallParam> holder) {
            this.holder = holder;
        }

        @Override
        public String getName() {
            return "searchKnowledgeBase";
        }

        @Override
        public String getDescription() {
            return "桩工具";
        }

        @Override
        public java.util.Map<String, Object> getParameters() {
            return java.util.Map.of();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            holder.set(param);
            return Mono.just(result(ToolResultState.SUCCESS, "ok"));
        }
    }
}
