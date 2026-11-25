package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.AgentSkillBind;
import com.agenthub.agent.domain.model.AgentToolBind;
import com.agenthub.agent.domain.repository.AgentSkillBindRepository;
import com.agenthub.agent.domain.repository.AgentToolBindRepository;
import com.agenthub.agent.infra.PromptBudgetProperties;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.runtime.domain.service.AnswerChannelNote;
import com.agenthub.rag.application.KbSearchToolExposure;
import com.agenthub.runtime.domain.service.BuiltinSchemaSource;
import com.agenthub.runtime.domain.service.ChoiceNote;
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
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 停用不进 schema：源停、工具项停、绑定关，任一成立都不得让工具出现在本轮可调用集。
 * 所属域：runtime 的装配单测。只验装配过滤，不碰库、MCP 同步和真实模型。
 */
@ExtendWith(MockitoExtension.class)
class TrialPromptFactoryToolStatusTest {

    private static final String TOOL_NAME = "system_user_list";
    private static final String SMALL_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

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
    }

    /**
     * 源停 + 绑定开：进不了目录，也拿不到可注册句柄。
     */
    @Test
    void disabledSourceNeverReachesSchema() {
        givenBind(AgentStatus.ENABLED);
        givenItem(RecordStatus.ENABLED.getCode());
        givenSource(RecordStatus.DISABLED.getCode());

        assertNotExposed(factory.build(agent()));
    }

    /**
     * 源开 + 绑定关：同样既不进目录也不进可注册集。
     */
    @Test
    void disabledBindNeverReachesSchema() {
        givenBind(AgentStatus.DISABLED);
        givenItem(RecordStatus.ENABLED.getCode());
        givenSource(RecordStatus.ENABLED.getCode());

        assertNotExposed(factory.build(agent()));
    }

    /**
     * 工具项自身停用（源与绑定都开）：MCP 新同步默认就是 0，不得进 schema。
     */
    @Test
    void disabledItemNeverReachesSchema() {
        givenBind(AgentStatus.ENABLED);
        givenItem(RecordStatus.DISABLED.getCode());
        givenSource(RecordStatus.ENABLED.getCode());

        assertNotExposed(factory.build(agent()));
    }

    /**
     * 三者皆开：正常进目录，并作为可注册 MCP 句柄交给执行侧。
     */
    @Test
    void enabledSourceItemAndBindReachesSchema() {
        givenBind(AgentStatus.ENABLED);
        givenItem(RecordStatus.ENABLED.getCode());
        givenSource(RecordStatus.ENABLED.getCode());

        TrialPromptFactory.Built built = factory.build(agent());

        assertTrue(built.prompt().contains(TOOL_NAME), "启用工具应进提示词工具目录");
        assertTrue(built.mcpTools().stream().anyMatch(spec -> TOOL_NAME.equals(spec.getName())),
                "启用工具应进可注册的 MCP 句柄集");
        assertTrue(built.notices().stream().noneMatch(notice -> notice.contains(TOOL_NAME)),
                "启用工具不应出现跳过说明");
    }

    /**
     * 三条排除路径的统一断言：目录、内置集、MCP 句柄集都不能留名字。
     */
    private void assertNotExposed(TrialPromptFactory.Built built) {
        assertFalse(built.prompt().contains(TOOL_NAME), "停用工具不得进提示词目录");
        assertTrue(built.mcpTools().stream().noneMatch(spec -> TOOL_NAME.equals(spec.getName())),
                "停用工具不得进可注册句柄集");
        assertTrue(built.tools().stream().noneMatch(spec -> TOOL_NAME.equals(spec.getName())),
                "停用工具不得进内置工具集");
    }

    /**
     * Skill 同检：绑定关或正文目标停用，都不得把正文灌进提示词。
     * 与工具同一判据，避免「工具已修、Skill 仍漏」。
     */
    @Test
    void disabledSkillBindOrSkillNeverInjectsBody() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.DISABLED, 500L)));
        TrialPromptFactory.Built bindOff = factory.build(agent());
        assertFalse(bindOff.prompt().contains("SKILL_BODY"), "绑定关的 Skill 不得注入正文");

        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        when(skills.findById(500L)).thenReturn(Optional.of(skill(RecordStatus.DISABLED.getCode())));
        TrialPromptFactory.Built skillOff = factory.build(agent());
        assertFalse(skillOff.prompt().contains("SKILL_BODY"), "停用的 Skill 不得注入正文");

        when(skills.findById(500L)).thenReturn(Optional.of(skill(RecordStatus.ENABLED.getCode())));
        TrialPromptFactory.Built bothOn = factory.build(agent());
        // 默认 HEADER_ONLY：启用且绑定的 Skill 进目录（标题 + 场景），正文不进 system
        assertTrue(bothOn.prompt().contains("## Skill 演示技能"), "启用且绑定的 Skill 应进目录");
        assertTrue(bothOn.prompt().contains("场景：脱敏与摘要场景"), "目录必须带场景");
        assertFalse(bothOn.prompt().contains("SKILL_BODY"), "默认不灌按需 Skill 正文");
    }

    /**
     * 命中关键词：直接强制注入正文并带固定前缀，不需要模型判断。
     */
    @Test
    void matchedTriggerKeywordForcesSkillBodyIntoPrompt() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        when(skills.findById(500L)).thenReturn(Optional.of(skill(RecordStatus.ENABLED.getCode())));

        TrialPromptFactory.Built built = factory.build(agent(), "帮我把这堆数据脱敏后发出");

        assertTrue(built.prompt().contains("用户触发了【演示技能】，请严格按照以下规则执行："));
        assertTrue(built.prompt().contains("SKILL_BODY"));
        assertEquals(1, built.stats().getSkillsHardTriggered());
    }

    /**
     * 未命中关键词：不加固定前缀，只进目录，正文留给模型按需判断。
     */
    @Test
    void unmatchedQueryKeepsSkillOnDemandOnly() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        when(skills.findById(500L)).thenReturn(Optional.of(skill(RecordStatus.ENABLED.getCode())));

        TrialPromptFactory.Built built = factory.build(agent(), "帮我查一下明天的天气");

        assertFalse(built.prompt().contains("用户触发了【"));
        assertTrue(built.prompt().contains("## Skill 演示技能"));
        // 目录必须带 description，供模型判断何时加载
        assertTrue(built.prompt().contains("场景：脱敏与摘要场景"));
        assertEquals(0, built.stats().getSkillsHardTriggered());
    }

    /**
     * 停用或绑定关闭的 Skill：既不进目录，也不因关键词命中而硬触发。
     */
    @Test
    void disabledSkillIsNeitherCataloguedNorHardTriggered() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        when(skills.findById(500L)).thenReturn(Optional.of(skill(RecordStatus.DISABLED.getCode())));

        TrialPromptFactory.Built built = factory.build(agent(), "帮我把这堆数据脱敏");

        assertFalse(built.prompt().contains("用户触发了【"));
        assertFalse(built.prompt().contains("## Skill 演示技能"));
        assertEquals(0, built.stats().getSkillsHardTriggered());
    }

    /**
     * remark 是内部备注，任何路径都不得进提示词（含硬触发）。
     */
    @Test
    void remarkNeverReachesPrompt() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        SkillDef withRemark = skill(RecordStatus.ENABLED.getCode());
        withRemark.setRemark("内部备注：仅运营可见");
        when(skills.findById(500L)).thenReturn(Optional.of(withRemark));

        TrialPromptFactory.Built built = factory.build(agent(), "帮我把这堆数据脱敏");

        assertFalse(built.prompt().contains("仅运营可见"));
    }

    /**
     * 从未发布（无生效已发布正文）：命中触发词也不得进强制块，目录仍可只显示标题。
     */
    @Test
    void skillWithoutPublishedBodyIsNotHardTriggered() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of(skillBind(AgentStatus.ENABLED, 500L)));
        SkillDef draftOnly = skill(RecordStatus.ENABLED.getCode());
        // 仓储只把「生效已发布版」填进 contentMd；没有发布版时为空
        draftOnly.setContentMd(null);
        when(skills.findById(500L)).thenReturn(Optional.of(draftOnly));

        TrialPromptFactory.Built built = factory.build(agent(), "帮我把这堆数据脱敏");

        assertFalse(built.prompt().contains("用户触发了【"), "无生效正文不得硬触发");
        assertEquals(0, built.stats().getSkillsHardTriggered());
        assertTrue(built.prompt().contains("## Skill 演示技能"), "目录仍应显示标题");
    }

    /**
     * 工作台叶子专家（可出选项 / 可续作）：系统提示必须同时带「交付通道口径」与「选项块说明」，
     * 且通道口径在前——顺序反了，选项规则里的「一句话收尾」会被模型套到续作轮，
     * 把整份方案挤进思考通道（用户手点过的坑）。
     */
    @Test
    void workbenchLeafPromptCarriesChannelNoteBeforeChoiceNote() {
        when(toolBinds.listByAgentId(1L)).thenReturn(List.of());
        when(skillBinds.listByAgentId(1L)).thenReturn(List.of());

        TrialPromptFactory.Built leaf = factory.build(agent(), "给我一套技术栈方案", null, true);
        int channel = leaf.prompt().indexOf(AnswerChannelNote.text());
        int choice = leaf.prompt().indexOf(ChoiceNote.text());
        assertTrue(channel >= 0, "叶子专家必须带交付通道口径");
        assertTrue(choice >= 0, "叶子专家必须带选项块说明");
        assertTrue(channel < choice, "通道口径必须排在选项规则之前");

        // 非工作台链路（不可拍板）不得注入任何一段平台锁定文案
        TrialPromptFactory.Built plain = factory.build(agent(), "给我一套技术栈方案");
        assertFalse(plain.prompt().contains(AnswerChannelNote.text()), "普通链路不注入通道口径");
        assertFalse(plain.prompt().contains(ChoiceNote.text()), "普通链路不注入选项说明");
    }

    private static AgentSkillBind skillBind(int status, Long skillId) {
        AgentSkillBind bind = new AgentSkillBind();
        bind.setId(20L);
        bind.setAgentId(1L);
        bind.setSkillId(skillId);
        bind.setStatus(status);
        return bind;
    }

    private static SkillDef skill(int status) {
        SkillDef skill = new SkillDef();
        skill.setId(500L);
        skill.setName("demo");
        skill.setTitle("演示技能");
        skill.setDescription("脱敏与摘要场景");
        skill.setTriggerKeywords("脱敏,摘要");
        skill.setContentMd("SKILL_BODY");
        skill.setStatus(status);
        return skill;
    }

    private void givenBind(int bindStatus) {
        AgentToolBind bind = new AgentToolBind();
        bind.setId(10L);
        bind.setAgentId(1L);
        bind.setToolItemId(100L);
        bind.setStatus(bindStatus);
        lenient().when(toolBinds.listByAgentId(1L)).thenReturn(List.of(bind));
    }

    private void givenItem(int itemStatus) {
        ToolItem item = new ToolItem();
        item.setId(100L);
        item.setSourceId(20L);
        item.setName(TOOL_NAME);
        item.setTitle("用户列表");
        item.setInputSchemaJson(SMALL_SCHEMA);
        item.setStatus(itemStatus);
        lenient().when(items.findById(100L)).thenReturn(Optional.of(item));
    }

    private void givenSource(int sourceStatus) {
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
