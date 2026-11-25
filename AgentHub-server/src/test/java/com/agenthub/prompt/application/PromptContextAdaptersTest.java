package com.agenthub.prompt.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.prompt.domain.model.PromptTemplateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHAT / 非 CHAT 填同一个 Context。所属域：prompt。
 * <p>
 * 架构定稿 §5.5 的验收口径：两条挂载路径只差 Adapter，Assembler 只有一套；
 * 因此这里的断言重点是「同配置 → 同 Context → 同正文」，以及 REWRITE 等用途
 * 不会因为读的是绑定表就丢掉用途。
 */
class PromptContextAdaptersTest {

    private final PromptContextAdapters adapters = PromptTestFixtures.adapters();

    /**
     * CHAT：模板挂载读 agent_def，历史业务段进 businessSegment，不混入 promptExtra。
     */
    @Test
    void chatAdapterReadsAgentDefColumns() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setName("售后助手");
        agent.setRunMode("REACT");
        agent.setSystemPrompt("历史业务段");
        agent.setPromptTemplateId(1L);
        agent.setTemplateVersion(1);
        agent.setPromptOverrideJson("{\"role_tone\":\"覆盖\"}");
        agent.setPromptExtra("追加区");

        PromptContext context = adapters.fromChat(agent, "用户原问", Map.of());

        assertEquals("CHAT", context.getPurpose());
        assertEquals(1L, context.getTemplateId());
        assertEquals(1, context.getTemplateVersion());
        assertEquals("{\"role_tone\":\"覆盖\"}", context.getOverrideJson());
        assertEquals("追加区", context.getPromptExtra());
        assertEquals("历史业务段", context.getBusinessSegment());
        assertEquals("售后助手", context.getAgentName());
        assertEquals("用户原问", context.getUserQuery());
    }

    /**
     * ROUTER 走 ROUTER 用途，不是 CHAT。
     */
    @Test
    void routerRunModeMapsToRouterPurpose() {
        AgentDef agent = new AgentDef();
        agent.setRunMode("ROUTER");
        agent.setSystemPrompt("分发说明");

        assertEquals("ROUTER", adapters.fromChat(agent, null, Map.of()).getPurpose());
    }

    /**
     * 计划执行与 ReAct 共用 CHAT 用途，不另建 PLAN 模板。
     */
    @Test
    void planModeSharesChatPurpose() {
        AgentDef agent = new AgentDef();
        agent.setRunMode("PLAN_AND_EXECUTE");
        agent.setSystemPrompt("执行助手");

        assertEquals("CHAT", adapters.fromChat(agent, null, Map.of()).getPurpose());
    }

    /**
     * 非 CHAT：模板挂载读 agent_model_bind，用途取自绑定的 purpose。
     */
    @Test
    void modelBindAdapterReadsBindColumnsAndKeepsPurpose() {
        AgentModelBind bind = new AgentModelBind();
        bind.setAgentId(9L);
        bind.setPurpose("REWRITE");
        bind.setPromptTemplateId(3L);
        bind.setTemplateVersion(2);
        bind.setPromptOverrideJson("{\"rewrite_style\":\"精简\"}");
        bind.setPromptExtra(null);

        PromptContext context = adapters.fromModelBind(bind, "助手名", "原问", null);

        assertEquals("REWRITE", context.getPurpose());
        assertEquals(3L, context.getTemplateId());
        assertEquals(2, context.getTemplateVersion());
        assertEquals("{\"rewrite_style\":\"精简\"}", context.getOverrideJson());
        assertEquals("助手名", context.getAgentName());
        // 绑定表没有历史业务段列，必须为空，不能借用别的字段
        assertEquals(null, context.getBusinessSegment());
    }

    /**
     * 绑定行 purpose 为空时按 CHAT（与「空则跟实例能力」的既有口径一致），不得抛错。
     */
    @Test
    void blankBindPurposeFallsBackToChat() {
        AgentModelBind bind = new AgentModelBind();
        bind.setPurpose("  ");
        assertEquals("CHAT", adapters.fromModelBind(bind, null, null, null).getPurpose());
    }

    /**
     * 两条挂载路径填出相同配置时，组装结果正文必须一致——证明没有两套拼接。
     */
    @Test
    void chatAndBindWithSameConfigAssembleIdentically() {
        PromptTestFixtures.InMemorySnapshots snapshots = PromptTestFixtures.snapshots();
        PromptAssembler assembler = PromptTestFixtures.assembler(snapshots);

        AgentDef agent = new AgentDef();
        agent.setRunMode("REACT");
        agent.setPromptTemplateId(1L);
        agent.setTemplateVersion(1);
        agent.setPromptOverrideJson("{\"role_tone\":\"同一份覆盖\"}");
        agent.setPromptExtra("同一份追加");
        agent.setSystemPrompt(null);

        AgentModelBind bind = new AgentModelBind();
        bind.setPurpose("CHAT");
        bind.setPromptTemplateId(1L);
        bind.setTemplateVersion(1);
        bind.setPromptOverrideJson("{\"role_tone\":\"同一份覆盖\"}");
        bind.setPromptExtra("同一份追加");

        String viaChat = assembler.assemble(adapters.fromChat(agent, null, null)).getText();
        String viaBind = assembler.assemble(adapters.fromModelBind(bind, null, null, null)).getText();

        assertEquals(viaChat, viaBind);
        assertTrue(viaChat.contains("同一份覆盖"));
        assertFalse(viaChat.contains("SOFT 默认语气"));
    }

    /**
     * 非 CHAT 用途取的是自己那一版模板，不会被 CHAT 模板顶替。
     */
    @Test
    void nonChatPurposeUsesItsOwnTemplate() {
        PromptTestFixtures.InMemorySnapshots snapshots = PromptTestFixtures.snapshots();
        PromptAssembler assembler = PromptTestFixtures.assembler(snapshots);

        AgentModelBind bind = new AgentModelBind();
        bind.setPurpose("SUMMARY");

        String text = assembler.assemble(adapters.fromModelBind(bind, null, null, null)).getText();

        // 用的是 SUMMARY 自己的 core 段，不是 CHAT 的
        assertTrue(text.contains("CORE 摘要契约"));
        assertFalse(text.contains("CORE 输出契约"));
        PromptTemplateSnapshot template = snapshots.templateByKey("SUMMARY", "zh-CN", 1).orElseThrow();
        assertEquals("SUMMARY", template.getPurpose());
    }
}
