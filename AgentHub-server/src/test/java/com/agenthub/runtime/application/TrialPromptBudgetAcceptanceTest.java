package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.runtime.infra.agentscope.AssembledPromptGuard;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * P0 验收：同 Agent 在极端预算下「红线仍在 + 分段有数字 + 工具 schema 不进 system」。
 * <p>
 * 三条硬断言：
 * <ol>
 *   <li>超预算时 L1 与锁定段完整保留，且锁定段仍排在开放段之前，Guard 放行；</li>
 *   <li>分段计量每一段都有数字（保护区 / 可压区 / Skill / 目录 / schema）；</li>
 *   <li>system 里没有工具的参数 schema 原文，只有「名称 + 一句」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TrialPromptBudgetAcceptanceTest {

    private static final String MEMORY_BLOCK = "【用户偏好】\n- 常住城市：杭州\n- 语气：简洁";

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

    @BeforeEach
    void setUp() {
        budgets = new PromptBudgetProperties();
        SystemPromptAssembler prompts = new SystemPromptAssembler(
                PromptTestFixtures.assembler(PromptTestFixtures.snapshots()), PromptTestFixtures.adapters(),
                PromptTestFixtures.prefix());
        factory = new TrialPromptFactory(toolBinds, skillBinds, new DirectAgentDictionary(items, sources, skills),
                prompts, budgets, builtinSchemas, kbExposure);
        lenient().when(skillBinds.listByAgentId(1L)).thenReturn(List.of());
    }

    /**
     * 超预算（预算小于保护区分量）：红线完整、Guard 放行、截断可见。
     */
    @Test
    void oversizedBudgetStillKeepsRedLinesAndPassesGuard() {
        budgets.setSystemPromptMaxChars(300);
        budgets.setSystemPromptMaxCharsCap(300);
        givenTool("queryWeather", "查询城市天气");

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK);
        String prompt = built.prompt();

        assertTrue(prompt.startsWith(PromptTestFixtures.seedConstraintText()), "L1 必须完整在最前");
        assertTrue(prompt.contains("CORE 输出契约"), "锁定段不得被裁");
        int locked = prompt.indexOf("CORE 输出契约");
        int soft = prompt.indexOf("SOFT 默认语气");
        if (soft >= 0) {
            assertTrue(locked < soft, "锁定段仍必须在开放段之前");
        }
        assertTrue(built.stats().isPromptSegmentTruncated(), "截断必须在统计上可见");
        assertTrue(built.stats().getProtectedChars() > 0, "保护区必须有字符数");

        // 最关键：出站前结构校验必须放行（否则等于红线缺失后照常出站）
        AssembledPromptGuard guard = new AssembledPromptGuard(PromptTestFixtures.prefix());
        assertTrue(guard.require(prompt, built.layout()).equals(prompt));
    }

    /**
     * 分段计量：每段都给出字符与估算 token，合计可相加。
     */
    @Test
    void everySectionReportsNumbers() {
        givenTool("queryWeather", "查询城市天气");

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK);
        PromptInjectionStats stats = built.stats();

        assertTrue(stats.getProtectedChars() > 0, "保护区");
        assertTrue(stats.getProtectedTokens() > 0, "保护区 token");
        assertTrue(stats.getSystemSoftChars() > 0, "可压区");
        assertTrue(stats.getToolCatalogChars() > 0, "目录");
        assertTrue(stats.getToolSchemaChars() > 0, "schema");
        assertTrue(stats.totalChars() > 0);
        assertTrue(stats.totalTokens() > 0);
        // 合计 = 各段相加，面板才能据此算占比
        assertTrue(stats.totalChars() >= stats.getProtectedChars() + stats.getToolCatalogChars());
        String breakdown = stats.breakdown();
        assertTrue(breakdown.contains("保护区"), breakdown);
        assertTrue(breakdown.contains("Skill"), breakdown);
        assertTrue(breakdown.contains("schema"), breakdown);
    }

    /**
     * system 只放「名称 + 一句」，完整 schema 原文留在 tools 字段（不进 system）。
     */
    @Test
    void toolSchemaIsNotDuplicatedIntoSystem() {
        givenTool("queryWeather", "查询城市天气");

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", null);
        String prompt = built.prompt();

        assertTrue(prompt.contains("queryWeather"), "名称必须在目录里");
        assertTrue(prompt.contains("查询城市天气"), "一句话必须在目录里");
        // schema 片段不得出现在 system 正文中（它只应走 tools 字段）
        assertFalse(prompt.contains("\"properties\""), "禁止把 schema 抄进 system");
        assertFalse(prompt.contains("\"type\":\"object\""), "禁止把 schema 抄进 system");
        // 但 schema 确实被记账了，说明它走了专款而不是被丢弃
        assertTrue(built.stats().getToolSchemaChars() > 0);
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
        item.setInputSchemaJson("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
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
