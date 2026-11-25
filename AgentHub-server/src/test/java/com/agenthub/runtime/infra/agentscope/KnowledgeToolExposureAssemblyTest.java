package com.agenthub.runtime.infra.agentscope;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.application.DirectAgentDictionary;
import com.agenthub.runtime.application.SystemPromptAssembler;
import com.agenthub.runtime.application.TrialPromptFactory;
import com.agenthub.runtime.domain.model.BuiltinToolSpec;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 检索工具的出场判定与说明覆盖，走真实装配链。所属域：runtime 的 infra。
 * <p>
 * 锁死两条验收：
 * <ol>
 *   <li>当前 Agent 没有可检索知识库时，工具既不在可注册集，也不在模型可见 schema 里
 *       （模型看不见、也调不到，不会先调一次再收到「未绑定」空跑一轮）；</li>
 *   <li>有可用库时照常交出，且模型看到的工具描述是装配期生成的那份（带库名与描述），
 *       而参数 schema 仍是注解里的 query / topK，没有新增选库参数。</li>
 * </ol>
 * 只 mock 仓储与出场判定，不连库、不出站。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeToolExposureAssemblyTest {

    private static final String TOOL_NAME = "searchKnowledgeBase";

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
        lenient().when(skillBinds.listByAgentId(1L)).thenReturn(List.of());
        givenKnowledgeToolBind();
    }

    /**
     * 没有可用库：可注册集为空，目录与 schema 里都没有这个工具。
     */
    @Test
    void toolIsNotExposedWithoutUsableKnowledgeBase() {
        when(kbExposure.descriptionIfExposed(1L)).thenReturn(Optional.empty());

        TrialPromptFactory.Built built = factory.build(agent());

        assertTrue(built.tools().stream().noneMatch(spec -> TOOL_NAME.equals(spec.getName())),
                "无可用库时不得注册检索工具");
        assertFalse(built.prompt().contains(TOOL_NAME), "无可用库时目录里也不应出现检索工具");
        // 装配说明同样不写：这是配置事实，不该每轮往对话时间线刷一条
        assertTrue(built.notices().stream().noneMatch(notice -> notice.contains(TOOL_NAME)));
    }

    /**
     * 有可用库：照常交出，说明覆盖带上库清单，schema 参数仍只有 query / topK。
     */
    @Test
    void toolIsExposedWithKnowledgeListInDescription() {
        String description = "检索当前 Agent 已绑定的知识库。\n\n当前 Agent 绑定了以下知识库：\n"
                + "1. 《商城规则库》：退换货与运费规则\n2. 《企业制度库》：（尚无描述）";
        when(kbExposure.descriptionIfExposed(1L)).thenReturn(Optional.of(description));

        TrialPromptFactory.Built built = factory.build(agent());

        BuiltinToolSpec spec = built.tools().stream()
                .filter(one -> TOOL_NAME.equals(one.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("有可用库时应注册检索工具"));
        assertEquals(description, spec.getDescriptionOverride(), "说明必须是装配期生成的那份");
        assertTrue(built.prompt().contains(TOOL_NAME), "有可用库时目录里应有检索工具");
        assertFalse(built.stats().isToolSchemaBudgetHit());
    }

    /**
     * 说明覆盖必须真的进模型可见 schema：注册后 Toolkit 取出的 description 已是新文案，
     * 而 parameters 与执行都还是原工具（没有新增选库入参）。
     */
    @Test
    void descriptionOverrideReachesModelVisibleSchema() {
        String description = "检索当前 Agent 已绑定的知识库。\n1. 《商城规则库》：退换货规则";

        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName(TOOL_NAME);
        spec.setDescriptionOverride(description);
        Toolkit toolkit = assembler().assemble(List.of(spec));

        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(one -> TOOL_NAME.equals(one.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具应出现在模型可见 schema 里"));
        assertEquals(description, schema.getDescription(), "模型看到的必须是装配期说明");
        assertTrue(toolkit.getTool(TOOL_NAME) instanceof DescriptionOverrideAgentTool);
        // 参数仍来自 Java 注解：只有 query / topK，没有 kbId
        Object properties = schema.getParameters().get("properties");
        assertTrue(String.valueOf(properties).contains("query"));
        assertFalse(String.valueOf(properties).contains("kbId"));
    }

    /**
     * 不覆盖时注册结果与改造前一致：仍使用 Bean 注解上的描述。
     */
    @Test
    void specWithoutOverrideKeepsAnnotatedDescription() {
        BuiltinToolSpec spec = new BuiltinToolSpec();
        spec.setName(TOOL_NAME);

        Toolkit toolkit = assembler().assemble(List.of(spec));

        ToolSchema schema = toolkit.getToolSchemas().stream()
                .filter(one -> TOOL_NAME.equals(one.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具应出现在模型可见 schema 里"));
        assertEquals("测试用检索工具", schema.getDescription());
    }

    private BuiltinToolkitAssembler assembler() {
        return new BuiltinToolkitAssembler(new BuiltinToolBeans(
                BuiltinToolBeans.index(List.of(new StubKnowledgeTool()))));
    }

    private void givenKnowledgeToolBind() {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(10L);
        bind.setAgentId(1L);
        bind.setToolItemId(100L);
        bind.setStatus(AgentStatus.ENABLED);
        lenient().when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind));

        ToolItem item = new ToolItem();
        item.setId(100L);
        item.setSourceId(20L);
        item.setName(TOOL_NAME);
        item.setTitle("知识库检索");
        item.setStatus(1);
        lenient().when(items.findById(100L)).thenReturn(Optional.of(item));

        ToolSource source = new ToolSource();
        source.setId(20L);
        source.setName("BUILTIN");
        source.setType("BUILTIN");
        source.setStatus(1);
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

    /** 与现网检索工具同调用名的桩：只验注册与说明覆盖，不执行检索。 */
    public static class StubKnowledgeTool {

        @Tool(name = "searchKnowledgeBase", description = "测试用检索工具")
        public String searchKnowledgeBase(@ToolParam(name = "query", description = "检索问题") String query,
                                         @ToolParam(name = "topK", description = "无需填写",
                                                 required = false) Integer topK) {
            return "{}";
        }
    }
}
