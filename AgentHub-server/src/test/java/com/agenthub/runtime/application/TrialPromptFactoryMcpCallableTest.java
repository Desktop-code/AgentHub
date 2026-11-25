package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * MCP 绑定项须进本轮可调用集：不再静默「本期没有 tools/call」。
 * 覆盖「仅绑 1 个小 schema MCP」与「MCP+内置按绑定序注入」。
 */
@ExtendWith(MockitoExtension.class)
class TrialPromptFactoryMcpCallableTest {

    @Mock
    private AgentToolBindRepository toolBinds;
    @Mock
    private AgentSkillBindRepository skillBinds;
    @Mock
    private ToolItemRepository items;
    @Mock
    private ToolSourceRepository sources;
    @Mock
    private SkillDefRepository skills;
    @Mock
    private BuiltinSchemaSource builtinSchemas;
    @Mock
    private KbSearchToolExposure kbExposure;

    private TrialPromptFactory factory;

    @BeforeEach
    void setUp() {
        SystemPromptAssembler prompts = new SystemPromptAssembler(
                PromptTestFixtures.assembler(PromptTestFixtures.snapshots()), PromptTestFixtures.adapters(),
                PromptTestFixtures.prefix());
        factory = new TrialPromptFactory(toolBinds, skillBinds, new DirectAgentDictionary(items, sources, skills),
                prompts, new PromptBudgetProperties(), builtinSchemas, kbExposure);
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of());
    }

    /**
     * 只绑一个小 schema 的 MCP：应进 schemaNames / mcpTools，目录无「本轮不可用」，无旧缺口文案。
     */
    @Test
    void singleSmallMcpIsCallableAndRegisteredForToolkit() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind(10L, 100L)));
        when(items.findById(100L)).thenReturn(Optional.of(item(100L, 20L, "system_user_list", "用户列表",
                "{\"type\":\"object\",\"properties\":{\"page\":{\"type\":\"integer\"}}}")));
        when(sources.findById(20L)).thenReturn(Optional.of(mcpSource(20L)));

        TrialPromptFactory.Built built = factory.build(agent());

        assertEquals(1, built.mcpTools().size());
        assertEquals("system_user_list", built.mcpTools().get(0).getName());
        assertEquals(20L, built.mcpTools().get(0).getSourceId());
        assertTrue(built.tools().isEmpty());
        assertTrue(built.prompt().contains("system_user_list"));
        assertFalse(built.prompt().contains("本轮不可用"));
        assertTrue(built.notices().stream().noneMatch(n -> n.contains("本期没有 tools/call")));
        assertFalse(built.stats().isToolSchemaBudgetHit());
    }

    /**
     * 绑定序：先 MCP 再内置；同预算下两者都进可调用集，且 MCP 规格排在内置之前被筛出。
     */
    @Test
    void mcpThenBuiltinInjectInBindOrder() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind(1L, 100L), bind(2L, 200L)));
        when(items.findById(100L)).thenReturn(Optional.of(item(100L, 20L, "system_user_list", "用户列表",
                "{\"type\":\"object\",\"properties\":{}}")));
        when(items.findById(200L)).thenReturn(Optional.of(item(200L, 30L, "queryWeather", "天气", null)));
        when(sources.findById(20L)).thenReturn(Optional.of(mcpSource(20L)));
        when(sources.findById(30L)).thenReturn(Optional.of(builtinSource(30L)));
        when(builtinSchemas.jsonFor("queryWeather")).thenReturn(Optional.of(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"));

        TrialPromptFactory.Built built = factory.build(agent());

        assertEquals(List.of("system_user_list"), built.mcpTools().stream().map(s -> s.getName()).toList());
        assertEquals(List.of("queryWeather"), built.tools().stream().map(s -> s.getName()).toList());
        int mcpAt = built.prompt().indexOf("system_user_list");
        int builtinAt = built.prompt().indexOf("queryWeather");
        assertTrue(mcpAt >= 0 && builtinAt > mcpAt);
        assertFalse(built.prompt().contains("本轮不可用"));
        assertTrue(built.notices().stream().noneMatch(n -> n.contains("本期没有 tools/call")));
    }

    private static AgentDef agent() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        agent.setSystemPrompt("助手");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }

    private static AgentToolBind bind(Long id, Long toolItemId) {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(id);
        bind.setAgentId(1L);
        bind.setToolItemId(toolItemId);
        bind.setStatus(AgentStatus.ENABLED);
        return bind;
    }

    private static ToolItem item(Long id, Long sourceId, String name, String title, String schema) {
        ToolItem item = new ToolItem();
        item.setId(id);
        item.setSourceId(sourceId);
        item.setName(name);
        item.setTitle(title);
        item.setInputSchemaJson(schema);
        item.setStatus(1);
        return item;
    }

    private static ToolSource mcpSource(Long id) {
        ToolSource source = new ToolSource();
        source.setId(id);
        source.setName("RUOYI_MCP");
        source.setType("MCP");
        source.setTransport("SSE");
        source.setBaseUrl("http://127.0.0.1/sse");
        source.setStatus(1);
        return source;
    }

    private static ToolSource builtinSource(Long id) {
        ToolSource source = new ToolSource();
        source.setId(id);
        source.setName("BUILTIN");
        source.setType("BUILTIN");
        source.setStatus(1);
        return source;
    }
}
