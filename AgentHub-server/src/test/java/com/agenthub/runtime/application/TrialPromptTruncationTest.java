package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * 超长提示词的截断保护。所属域：runtime 的装配单测。
 * <p>
 * 硬约束：二次截断只允许削可变段，<b>L1 平台约束与 L2 锁定段必须完整留在正文里</b>。
 * 这里把预算压到极小值，让「按字数硬切」的实现必然失败，从而锁死优先级。
 */
@ExtendWith(MockitoExtension.class)
class TrialPromptTruncationTest {

    private static final String MEMORY_BLOCK = "【工作记忆】\n- 上次结论：偏好西湖区";

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

    private PromptBudgetProperties budgets;
    private TrialPromptFactory factory;
    private String l1Text;

    @BeforeEach
    void setUp() {
        budgets = new PromptBudgetProperties();
        // 预算压到接近「只有 L1 + 锁定段」的量级：任何按字数硬切的实现都会切进红线
        budgets.setSystemPromptMaxChars(700);
        budgets.setSystemPromptMaxCharsCap(700);
        budgets.setSkillsInjectMaxChars(200);
        budgets.setSkillsInjectMaxCharsCap(200);
        budgets.setToolsSchemaMaxChars(8000);
        budgets.setToolsSchemaMaxCharsCap(8000);
        // 强制 Skill 上限放到足够大：本测验的是总量让出，不是单条硬顶
        budgets.setForcedSkillMaxChars(8000);
        budgets.setForcedSkillMaxCharsCap(8000);
        l1Text = PromptTestFixtures.seedConstraintText();
        SystemPromptAssembler prompts = new SystemPromptAssembler(
                PromptTestFixtures.assembler(PromptTestFixtures.snapshots()), PromptTestFixtures.adapters(),
                PromptTestFixtures.prefix());
        factory = new TrialPromptFactory(toolBinds, skillBinds, new DirectAgentDictionary(items, sources, skills),
                prompts, budgets, builtinSchemas, kbExposure);
        lenient().when(skillBinds.listByAgentId(1L)).thenReturn(List.of());
    }

    /**
     * 超长时 L1 与锁定段仍在，且锁定段在开放段之前；记忆追加区先被让出。
     */
    @Test
    void oversizedPromptKeepsL1AndLockedSegment() {
        givenTool("queryWeather", "查询城市天气");

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK);
        String prompt = built.prompt();

        // 红线：L1 完整（按前缀比较，不是「包含关键词」）
        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()),
                "超长时 L1 必须仍是最前且完整");
        // 锁定段必须在场，且排在开放段之前
        assertTrue(prompt.contains("CORE 输出契约"), "锁定段不得被截断");
        int locked = prompt.indexOf("CORE 输出契约");
        int soft = prompt.indexOf("SOFT 默认语气");
        if (soft >= 0) {
            assertTrue(locked < soft, "锁定段必须仍在开放段之前");
        }
        // 截断必须在统计上可见，不能静默丢内容
        assertTrue(built.stats().isPromptSegmentTruncated());
        // 结构描述必须报告受保护前缀长度，供出站校验与下游使用
        assertNotNull(built.layout());
        assertTrue(built.layout().headChars() > 0);
    }

    /**
     * 极端预算下（systemMax 小于 head），head 依然原样保留：宁可超预算也不砍红线。
     */
    @Test
    void tinyBudgetStillKeepsWholeProtectedHead() {
        budgets.setSystemPromptMaxChars(10);
        budgets.setSystemPromptMaxCharsCap(10);

        String prompt = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK).prompt();

        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()),
                "预算再小也不得截断 L1");
        assertTrue(prompt.contains("CORE 输出契约"), "预算再小也不得截断锁定段");
    }

    /**
     * 记忆段是追加区：超预算时优先让出，不与红线争夺额度。
     */
    @Test
    void memoryBlockYieldsFirstWhenOverBudget() {
        budgets.setSystemPromptMaxChars(620);
        budgets.setSystemPromptMaxCharsCap(620);

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK);

        assertTrue(built.prompt().startsWith(PromptTestFixtures.seedConstraintText()));
        assertTrue(built.prompt().contains("CORE 输出契约"));
        assertTrue(built.stats().isPromptSegmentTruncated(), "让出记忆也必须留痕");
    }

    private void givenTool(String name, String title) {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(10L);
        bind.setAgentId(1L);
        bind.setToolItemId(100L);
        bind.setStatus(AgentStatus.ENABLED);
        lenient().when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind));

        ToolItem item = new ToolItem();
        item.setId(100L);
        item.setSourceId(20L);
        item.setName(name);
        item.setTitle(title);
        item.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
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
        agent.setSystemPrompt("你是天气助手，优先调用查询工具。");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }
}
