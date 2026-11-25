package com.agenthub.runtime.infra.agentscope;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.runtime.application.DirectAgentDictionary;
import com.agenthub.runtime.application.SystemPromptAssembler;
import com.agenthub.runtime.application.TrialPromptFactory;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 端到端装配断言：从绑定表走到注入模型的 tools/schema。
 * 所属域：runtime。覆盖验收三条——源停+绑定开 / 源开+绑定关 → schema 无该工具；皆开 → 可调。
 * 用真 {@link TrialPromptFactory} + 真 Toolkit 装配器，只 mock 仓储与出站客户端。
 */
@ExtendWith(MockitoExtension.class)
class TrialToolSchemaAssemblyTest {

    private static final String TOOL_NAME = "system_user_list";
    private static final String TOOL_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

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
    @Mock
    private McpClientFactory clients;
    @Mock
    private SecretStore secretStore;
    @Mock
    private McpClient mcpClient;

    private TrialPromptFactory factory;
    private McpToolkitAssembler mcpAssembler;

    @BeforeEach
    void setUp() {
        SystemPromptAssembler prompts = new SystemPromptAssembler(
                PromptTestFixtures.assembler(PromptTestFixtures.snapshots()), PromptTestFixtures.adapters(),
                PromptTestFixtures.prefix());
        factory = new TrialPromptFactory(toolBinds, skillBinds, new DirectAgentDictionary(items, sources, skills),
                prompts, new PromptBudgetProperties(), builtinSchemas, kbExposure);
        mcpAssembler = new McpToolkitAssembler(sources, clients, secretStore, new ObjectMapper());
        lenient().when(skillBinds.listByAgentId(1L)).thenReturn(List.of());
    }

    /**
     * 源停 + 绑定开：模型侧 tools/schema 不得出现该工具，也就不会产生指向它的 tool_call。
     */
    @Test
    void disabledSourceLeavesNoToolSchema() {
        givenToolChain(AgentStatus.ENABLED, RecordStatus.ENABLED.getCode(), RecordStatus.DISABLED.getCode());

        assertSchemaHasTool(buildToolkit(), false);
    }

    /**
     * 源开 + 绑定关：同样不得出现在模型 tools/schema 里。
     */
    @Test
    void disabledBindLeavesNoToolSchema() {
        givenToolChain(AgentStatus.DISABLED, RecordStatus.ENABLED.getCode(), RecordStatus.ENABLED.getCode());

        assertSchemaHasTool(buildToolkit(), false);
    }

    /**
     * 皆开：schema 里应有该工具，且句柄可执行。
     */
    @Test
    void enabledSourceAndBindExposesCallableSchema() {
        givenToolChain(AgentStatus.ENABLED, RecordStatus.ENABLED.getCode(), RecordStatus.ENABLED.getCode());
        when(clients.require("SSE")).thenReturn(mcpClient);

        Toolkit toolkit = buildToolkit();

        assertSchemaHasTool(toolkit, true);
        assertNotNull(toolkit.getTool(TOOL_NAME), "皆开时应有可执行句柄");
    }

    /**
     * 模拟 {@code ReActTrialExecutor} 的装配顺序：内置先行，再挂 MCP，最后取模型可见 schema。
     */
    private Toolkit buildToolkit() {
        TrialPlan plan = new TrialPlan();
        TrialPromptFactory.Built built = factory.build(agent());
        plan.setTools(built.tools());
        plan.setMcpTools(built.mcpTools());
        Toolkit toolkit = new BuiltinToolkitAssembler(new BuiltinToolBeans(BuiltinToolBeans.index(List.of())))
                .assemble(plan.getTools());
        mcpAssembler.register(toolkit, plan.getMcpTools());
        return toolkit;
    }

    private static void assertSchemaHasTool(Toolkit toolkit, boolean expected) {
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        boolean found = schemas.stream().anyMatch(schema -> TOOL_NAME.equals(schema.getName()));
        if (expected) {
            assertTrue(found, "皆开时 schema 应包含该工具");
        } else {
            assertFalse(found, "停用时 schema 不得包含该工具");
        }
    }

    private void givenToolChain(int bindStatus, int itemStatus, int sourceStatus) {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(10L);
        bind.setAgentId(1L);
        bind.setToolItemId(100L);
        bind.setStatus(bindStatus);
        lenient().when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind));

        ToolItem item = new ToolItem();
        item.setId(100L);
        item.setSourceId(20L);
        item.setName(TOOL_NAME);
        item.setTitle("用户列表");
        item.setInputSchemaJson(TOOL_SCHEMA);
        item.setStatus(itemStatus);
        lenient().when(items.findById(100L)).thenReturn(Optional.of(item));

        ToolSource source = new ToolSource();
        source.setId(20L);
        source.setName("RUOYI_MCP");
        source.setType("MCP");
        source.setTransport("SSE");
        source.setBaseUrl("http://127.0.0.1/sse");
        source.setStatus(sourceStatus);
        lenient().when(sources.findById(20L)).thenReturn(Optional.of(source));
    }

    private static AgentDef agent() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        agent.setSystemPrompt("助手");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }
}
