package com.agenthub.runtime.application;

import com.agenthub.memory.application.ContextWindowFacade;
import com.agenthub.memory.application.ExplicitMemoryExtractor;
import com.agenthub.memory.application.LongExtractService;
import com.agenthub.memory.application.MemoryPolicyAppService;
import com.agenthub.memory.application.MemoryRecallFacade;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.MemoryFact;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.service.LongMemoryNote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 注入记忆与工具补参依据必须同源：只有本轮真正拼进【用户偏好】的 LONG 才能参与补参。
 * 所属域：runtime 的 application 单测，不连库。
 */
@ExtendWith(MockitoExtension.class)
class TrialMemoryCoordinatorFactsTest {

    @Mock
    private MemoryPolicyAppService policies;
    @Mock
    private ContextWindowFacade windows;
    @Mock
    private MemoryRecallFacade recall;
    @Mock
    private LongExtractService extracts;
    @Mock
    private ExplicitMemoryExtractor explicitMemory;
    @Mock
    private SessionTurnSource events;

    private TrialMemoryCoordinator memory;

    @BeforeEach
    void setUp() {
        memory = new TrialMemoryCoordinator(policies, windows, recall, extracts, explicitMemory, events);
    }

    /**
     * 已注入的 LONG 应转成事实，并且记忆段正文里同时出现该记忆正文。
     * <p>
     * 记忆段不再自己拼到系统提示末尾：位置由 {@code PromptInjectionComposer} 统一安放在
     * 「业务段收口之后、Skill 与工具目录之前」，本类只负责取数与成段。
     */
    @Test
    void injectedLongBecomesToolArgsFact() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        when(policies.effective(4L)).thenReturn(policy);
        when(recall.durable(policy, 4L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable(
                "", "- 居住城市：杭州\n", List.of(item("residence_city", "居住城市", "杭州"))));

        TrialMemoryCoordinator.Augmented augmented = memory.recallDurable(session(), null);

        assertTrue(augmented.memoryBlock().contains("【用户偏好】"));
        assertTrue(augmented.memoryBlock().contains("杭州"));
        assertEquals(1, augmented.injectedFacts().size());
        MemoryFact fact = augmented.injectedFacts().get(0);
        assertEquals("residence_city", fact.factKey());
        assertEquals("杭州", fact.content());
    }

    /**
     * 两段记忆同时在场时，工作记忆在前、用户偏好用空行分隔，交给下游直接当作一段正文使用。
     */
    @Test
    void agentMemoryPrecedesUserPreferencesInOneBlock() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        when(policies.effective(4L)).thenReturn(policy);
        when(recall.durable(policy, 4L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable(
                "- 上次结论：用西湖区口径\n", "- 居住城市：杭州\n", List.of()));

        String block = memory.recallDurable(session(), null).memoryBlock();

        int agent = block.indexOf("【工作记忆】");
        int pref = block.indexOf("【用户偏好】");
        assertTrue(agent >= 0 && pref > agent, "工作记忆在用户偏好之前");
        // 段之间必须有空行，否则会被渲染成一段连读文本
        assertTrue(block.contains("\n\n【用户偏好】"));
    }

    /**
     * 策略关闭长期记忆：不注入、也不产生任何补参依据，闸门只能追问。
     */
    @Test
    void noLongMemoryMeansNoEvidence() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(0);
        when(policies.effective(4L)).thenReturn(policy);
        when(recall.durable(policy, 4L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable("", "", List.of()));

        TrialMemoryCoordinator.Augmented augmented = memory.recallDurable(session(), null);

        assertEquals("", augmented.memoryBlock());
        assertFalse(augmented.memoryBlock().contains("【长期记忆说明】"));
        assertTrue(augmented.injectedFacts().isEmpty());
    }

    /**
     * 【长期记忆说明】的位置固定：工作记忆之后、本轮召回条目之前。
     * 说明本身来自平台 classpath 文案，不由 Agent 追加区覆盖。
     */
    @Test
    void longMemoryNoteSitsBetweenAgentMemoryAndRecalledEntries() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        when(policies.effective(4L)).thenReturn(policy);
        when(recall.durable(policy, 4L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable(
                "- 上次结论：用西湖区口径\n", "- 居住城市：杭州\n", List.of()));

        String block = memory.recallDurable(session(), null).memoryBlock();

        int agent = block.indexOf("【工作记忆】");
        int note = block.indexOf("【长期记忆说明】");
        int pref = block.indexOf("【用户偏好】");
        assertTrue(agent >= 0, "工作记忆在场");
        assertTrue(note > agent, "长期记忆说明在工作记忆之后");
        assertTrue(pref > note, "本轮召回条目在长期记忆说明之后");
        assertTrue(block.contains(LongMemoryNote.text()));
    }

    /**
     * 长期记忆开启但本轮没有召回条目：只出说明，仍然让模型知道自己有长期记忆。
     */
    @Test
    void longMemoryNoteAppearsEvenWithoutRecalledEntries() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        when(policies.effective(4L)).thenReturn(policy);
        when(recall.durable(policy, 4L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable("", "", List.of()));

        TrialMemoryCoordinator.Augmented augmented = memory.recallDurable(session(), null);

        assertEquals(LongMemoryNote.text(), augmented.memoryBlock());
        assertFalse(augmented.memoryBlock().contains("【用户偏好】"));
        assertTrue(augmented.injectedFacts().isEmpty());
    }

    /**
     * 虚拟根会话没有 agentId：长期记忆按 0 号归属读取，不能因为拆箱空值把整轮打挂。
     * 开启长期记忆时即使没有召回条目，也保留平台说明这一段。
     */
    @Test
    void virtualRootSessionDoesNotUnboxNullAgentId() {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setEnableLong(1);
        when(policies.effective(null)).thenReturn(policy);
        when(recall.durable(policy, 0L, 7L, null)).thenReturn(new MemoryRecallFacade.Durable("", "", List.of()));

        RunSession root = new RunSession();
        root.setId(3L);
        root.setUserId(7L);

        assertEquals(LongMemoryNote.text(), memory.recallDurable(root, null).memoryBlock());
    }

    private static RunSession session() {
        RunSession session = new RunSession();
        session.setId(3L);
        session.setAgentId(4L);
        session.setUserId(7L);
        return session;
    }

    private static MemoryItem item(String factKey, String title, String content) {
        MemoryItem item = new MemoryItem();
        item.setFactKey(factKey);
        item.setTitle(title);
        item.setContent(content);
        return item;
    }
}
