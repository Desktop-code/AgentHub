package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.runtime.domain.service.PromptInjectionComposer;
import com.agenthub.runtime.infra.agentscope.AssembledPromptGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试跑路径系统提示组装：与 {@link TrialPromptFactory} + {@link AssembledPromptGuard} 一致。
 * 覆盖纯 ReAct、Router 父判定、Router 子三条入模路径，证明语言强制条进入 sysPrompt。
 * <p>
 * L1 来自内存快照（正文与 L1 种子对齐），不连库、不出站模型。
 */
class TrialSystemPromptInjectionTest {

    private final com.agenthub.agent.domain.service.PlatformPromptPrefix prefixes = PromptTestFixtures.prefix();
    private final SystemPromptAssembler assembler = new SystemPromptAssembler(
            PromptTestFixtures.assembler(PromptTestFixtures.snapshots()), PromptTestFixtures.adapters(), prefixes);
    private final AssembledPromptGuard guard = new AssembledPromptGuard(prefixes);
    private final PromptBudgetProperties budgets = new PromptBudgetProperties();

    /**
     * 纯 ReAct 试跑：Assembler → Composer（Skill/工具目录）→ Guard.require。
     */
    @Test
    void trialPathKeepsLanguageConstraintInSysPrompt() {
        AgentDef agent = reactAgent("你是天气助手，优先调用查询工具。");
        String sysPrompt = guard.require(composeTrialPrompt(agent));
        assertLanguageHardConstraint(sysPrompt);
        assertTrue(sysPrompt.contains("queryWeather"));
        assertTrue(sysPrompt.contains("## Skill 天气"));
    }

    /**
     * Router 父判定：与 {@link RouterTrialExecutor} 的 judgePrompt 同构——
     * Assembler.build(ROUTER) + 路由候选说明 → Guard.require，须含语言强制条。
     */
    @Test
    void routerParentJudgePathKeepsLanguageConstraint() {
        AgentDef parent = new AgentDef();
        parent.setRunMode("ROUTER");
        parent.setSystemPrompt("根据意图分发到子 Agent。");

        String assembled = assembler.build(parent, "北京天气怎么样");
        StringBuilder judge = new StringBuilder();
        if (!assembled.isBlank()) {
            judge.append(assembled).append("\n\n");
        }
        judge.append("本次做意图路由：用户可能含多个意图。输出 JSON 数组，每项 ")
                .append("{\"route_key\":\"...\",\"sub_query\":\"切给该子的子问题\"}。")
                .append("按重要性排序；同 route_key 只出现一次；最多 3 项。")
                .append("单意图则数组长度为 1。不要解释。都不合适时回复 []。\n");
        judge.append("候选：\n- weather：查天气\n- mcp：MCP 测试\n");

        String sysPrompt = guard.require(judge.toString());
        assertLanguageHardConstraint(sysPrompt);
        assertTrue(sysPrompt.contains("你是路由型助手"));
        // ROUTER 用途模板也进来了，说明没有拿 CHAT 顶替
        assertTrue(sysPrompt.contains("CORE 路由契约"));
        assertTrue(sysPrompt.contains("route_key"));
        assertTrue(sysPrompt.contains("sub_query"));
        assertTrue(sysPrompt.indexOf("【平台约束") < sysPrompt.indexOf("你是路由型助手"));
    }

    /**
     * Router 子 Agent：子节点按 REACT 走同一套 TrialPromptFactory 组装，Guard 放行且含语言强制条。
     */
    @Test
    void routerChildReactPathKeepsLanguageConstraint() {
        AgentDef child = reactAgent("你是「MCP测试Agent」智能助手。请用简洁、准确的中文回答用户问题。");
        String sysPrompt = guard.require(composeTrialPrompt(child));
        assertLanguageHardConstraint(sysPrompt);
        assertTrue(sysPrompt.contains("MCP测试Agent") || sysPrompt.contains("【业务提示词】"));
    }

    /**
     * 与 TrialPromptFactory 一致：Assembler 结果再经 Composer。
     */
    private String composeTrialPrompt(AgentDef agent) {
        String assembled = assembler.build(agent, "北京天气怎么样");
        PromptInjectionComposer.Outcome outcome = PromptInjectionComposer.compose(
                assembled,
                List.of(new PromptInjectionComposer.SkillInput("天气", "查天气", "按城市返回气温")),
                List.of(new PromptInjectionComposer.ToolInput(
                        "queryWeather", "查当前天气", "{\"type\":\"object\"}", true)),
                new PromptInjectionComposer.Budgets(
                        budgets.systemBudget(), budgets.skillsBudget(), budgets.toolsSchemaBudget()));
        assertEquals(outcome.prompt(), guard.require(outcome.prompt()));
        return outcome.prompt();
    }

    private static AgentDef reactAgent(String userSegment) {
        AgentDef agent = new AgentDef();
        agent.setRunMode("REACT");
        agent.setSystemPrompt(userSegment);
        return agent;
    }

    /**
     * 语言强制条语义关键词；须置顶于密钥约束之前。
     */
    private void assertLanguageHardConstraint(String sysPrompt) {
        assertTrue(sysPrompt.startsWith("【平台约束"));
        assertTrue(prefixes.startsWithBase(sysPrompt));
        assertTrue(sysPrompt.contains("语言强制"));
        assertTrue(sysPrompt.contains("思考过程"));
        assertTrue(sysPrompt.contains("最终答复"));
        assertTrue(sysPrompt.contains("同一种语言"));
        assertTrue(sysPrompt.contains("首句"));
        assertTrue(sysPrompt.contains("禁止"));
        assertTrue(sysPrompt.contains("The user asks"));
        assertTrue(sysPrompt.contains("工具名"));
        assertTrue(sysPrompt.contains("参数名"));
        assertTrue(sysPrompt.contains("英文"));
        assertTrue(sysPrompt.indexOf("思考过程") < sysPrompt.indexOf("密钥"));
    }
}
