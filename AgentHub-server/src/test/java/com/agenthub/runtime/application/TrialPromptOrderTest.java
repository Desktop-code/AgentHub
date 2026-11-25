package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.runtime.domain.service.LongMemoryNote;
import com.agenthub.skill.domain.model.SkillDef;
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
import static org.mockito.Mockito.when;

/**
 * 系统提示的完整段落顺序。所属域：runtime 的装配单测。
 * <p>
 * 锁死权威顺序（对齐 {@code docs/提示词工程.md} 的期望表）：
 * L1 → L2 锁定段 → L2 开放段 / L3 → 记忆 → Skill → 工具目录。
 * <p>
 * 三处最容易回退的点在这里断言：
 * <ol>
 *   <li>锁定段整体前置于开放段（与 sort_no 无关）；</li>
 *   <li>记忆段在工具目录之前（曾在尾部追加，模型先读工具才读偏好）；</li>
 *   <li>记忆段在 Skill 之后（能力说明与规则先于偏好出场）。</li>
 * </ol>
 * 全部用内存快照 + mock 仓储，不连库、不连 Redis、不出站模型。
 */
@ExtendWith(MockitoExtension.class)
class TrialPromptOrderTest {

    private static final String MEMORY_BLOCK = "【用户偏好】\n- 常住城市：杭州";

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
        lenient().when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        lenient().when(skills.findById(500L)).thenReturn(Optional.of(skill()));
    }

    /**
     * 全段落在场时的相对顺序：L1 → 锁定段 → 开放段 → L3 → 业务段 → 记忆 → Skill → 工具目录。
     */
    @Test
    void fullSegmentOrderMatchesExpectedTable() {
        givenTool("queryWeather", "查询城市天气");

        TrialPromptFactory.Built built = factory.build(agent(), "北京天气怎么样", MEMORY_BLOCK);
        String prompt = built.prompt();

        int l1 = prompt.indexOf("【平台约束");
        int locked = prompt.indexOf("CORE 输出契约");
        int soft = prompt.indexOf("SOFT 默认语气");
        int business = prompt.indexOf("【业务提示词】");
        int memory = prompt.indexOf("【用户偏好】");
        int skill = prompt.indexOf("## Skill 演示技能");
        int catalog = prompt.indexOf("## 工具目录");

        assertTrue(l1 >= 0 && locked > l1, "L1 之后紧跟锁定段");
        assertTrue(soft > locked, "开放段在锁定段之后");
        assertTrue(business > soft, "历史业务段收口在 L2 之后");
        assertTrue(memory > business, "记忆段在业务段收口之后");
        assertTrue(skill > memory, "Skill 在记忆之后");
        assertTrue(catalog > skill, "工具目录在最后");
    }

    /**
     * 记忆段必须在工具目录之前：这是本次排查的核心顺序修正，单独断言一次防回退。
     */
    @Test
    void memoryComesBeforeToolCatalog() {
        givenTool("queryWeather", "查询城市天气");

        String prompt = factory.build(agent(), null, MEMORY_BLOCK).prompt();

        int memory = prompt.indexOf("【用户偏好】");
        int catalog = prompt.indexOf("## 工具目录");
        assertTrue(memory >= 0, "记忆段必须在场");
        assertTrue(catalog > memory, "工具目录必须排在记忆之后");
    }

    /**
     * 长期记忆说明的最终出站位置：工作记忆之后、本轮召回条目（【用户偏好】）之前。
     */
    @Test
    void longMemoryNoteSitsBetweenWorkMemoryAndRecalledEntries() {
        String memory = "【工作记忆】\n- 上次结论：用西湖区口径\n\n"
                + LongMemoryNote.text()
                + "\n\n【用户偏好】\n- 居住城市：杭州";

        String prompt = factory.build(agent(), null, memory).prompt();

        int work = prompt.indexOf("【工作记忆】");
        int note = prompt.indexOf("【长期记忆说明】");
        int pref = prompt.indexOf("【用户偏好】");
        assertTrue(work >= 0, "工作记忆必须在场");
        assertTrue(note > work, "长期记忆说明在工作记忆之后");
        assertTrue(pref > note, "召回条目在长期记忆说明之后");
    }

    /**
     * 不传记忆段时不得出现记忆标记（策略关闭长期记忆的路径）。
     */
    @Test
    void noMemoryBlockMeansNoMemorySection() {
        givenTool("queryWeather", "查询城市天气");

        String prompt = factory.build(agent(), null).prompt();

        assertFalse(prompt.contains("【工作记忆】"));
        assertFalse(prompt.contains("【用户偏好】"));
        assertTrue(prompt.contains("## 工具目录"));
    }

    /**
     * 硬触发 Skill 仍在记忆与工具目录之间，且不被目录挤掉。
     */
    @Test
    void hardTriggeredSkillSitsBetweenMemoryAndCatalog() {
        givenTool("queryWeather", "查询城市天气");

        String prompt = factory.build(agent(), "帮我把这堆数据脱敏", MEMORY_BLOCK).prompt();

        int memory = prompt.indexOf("【用户偏好】");
        int skill = prompt.indexOf("用户触发了【演示技能】");
        int catalog = prompt.indexOf("## 工具目录");
        assertTrue(skill > memory, "硬触发 Skill 在记忆之后");
        assertTrue(catalog > skill, "工具目录在硬触发 Skill 之后");
        // 硬触发块正文必在场，且带场景
        assertTrue(prompt.contains("SKILL_BODY"));
        assertTrue(prompt.contains("脱敏与摘要场景"));
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

    private static AgentSkillBind skillBind(int status, Long skillId) {
        AgentSkillBind bind = new AgentSkillBind();
        bind.setId(20L);
        bind.setAgentId(1L);
        bind.setSkillId(skillId);
        bind.setStatus(status);
        return bind;
    }

    private static SkillDef skill() {
        SkillDef skill = new SkillDef();
        skill.setId(500L);
        skill.setName("demo");
        skill.setTitle("演示技能");
        skill.setDescription("脱敏与摘要场景");
        skill.setTriggerKeywords("脱敏,摘要");
        skill.setContentMd("SKILL_BODY");
        skill.setStatus(1);
        return skill;
    }

    private static AgentDef agent() {
        AgentDef agent = new AgentDef();
        agent.setId(1L);
        agent.setRunMode("REACT");
        agent.setSystemPrompt("助手业务段");
        agent.setStatus(AgentStatus.ENABLED);
        return agent;
    }
}
