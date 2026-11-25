package com.agenthub.runtime.infra.agentscope;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenthub.agent.domain.service.AgentPromptProperties;
import com.agenthub.agent.domain.service.PlatformPromptPrefix;
import com.agenthub.prompt.PromptTestFixtures;
import com.agenthub.prompt.domain.model.PromptLayout;
import com.agenthub.runtime.domain.service.TrialFailure;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创建 Agent 时没带前缀要失败，日志里不能出现提示词正文。所属域：runtime 的 infra。
 * <p>
 * 前缀比对对象是**当前生效 L1**（来自库表，经 PromptAssembler），不再是 classpath 文件；
 * 因此 L1 换版后旧校验不会误放行。夹具用内存源，不连库。
 */
class AssembledPromptGuardTest {

    private final PlatformPromptPrefix prefixes = PromptTestFixtures.prefix();
    private final AssembledPromptGuard guard = new AssembledPromptGuard(prefixes);

    @Test
    void rejectsPromptThatSkipsPrefix() {
        TrialFailure ex = assertThrows(TrialFailure.class, () -> guard.require("只有用户段"));
        assertEquals("系统提示词未带平台前缀", ex.getMessage());
    }

    /**
     * 无生效 L1 时前缀为空串，任何提示词都无法通过校验——这正是「没模板不许继续聊」的落点。
     */
    @Test
    void rejectsEverythingWhenConstraintNotPublished() {
        AssembledPromptGuard strict = new AssembledPromptGuard(new PlatformPromptPrefix(
                new AgentPromptProperties(), PromptTestFixtures.emptySource(), ""));
        assertEquals("", strict.owner().baseText());
        assertThrows(TrialFailure.class, () -> strict.require("【平台约束】冒充的前缀"));
    }

    @Test
    void acceptsAssembledPromptWithoutLoggingBody() {
        String marker = "UNIQUE_USER_SEGMENT_ZX9";
        String prompt = prefixes.textFor("REACT") + "\n\n【业务提示词】\n" + marker;
        Logger logger = (Logger) LoggerFactory.getLogger(AssembledPromptGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(prompt, guard.require(prompt));
            assertFalse(appender.list.isEmpty());
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(marker));
                assertFalse(event.getFormattedMessage().contains("【平台约束"));
                assertFalse(event.getFormattedMessage().contains("Bearer"));
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void disabledPrefixAllowsUserSegment() {
        AssembledPromptGuard loose = new AssembledPromptGuard(PromptTestFixtures.disabledPrefix());
        assertEquals("只有用户段", loose.require("只有用户段"));
        assertTrue(loose.owner() != null);
    }

    /**
     * 结构校验：锁定段必须在场。被截断或被某条路径漏拼都要拦住，
     * 否则「锁定契约不在上下文里」仍会照常出站。
     */
    @Test
    void rejectsWhenLockedSegmentMissing() {
        String prompt = prefixes.textFor("REACT") + "\n\nSOFT 开放段";
        PromptLayout layout = new PromptLayout(prefixes.textFor("REACT").length(),
                List.of("CORE 输出契约：先直接回答。"), List.of("SOFT 开放段"));

        TrialFailure ex = assertThrows(TrialFailure.class, () -> guard.require(prompt, layout));
        assertEquals("系统提示词缺少锁定段", ex.getMessage());
    }

    /**
     * 结构校验：锁定段必须排在开放段之前。顺序颠倒时前缀校验完全看不出来，只有结构校验能拦。
     */
    @Test
    void rejectsWhenLockedSegmentComesAfterOpenSegment() {
        String prompt = prefixes.textFor("REACT") + "\n\nSOFT 开放段\n\nCORE 输出契约：先直接回答。";
        PromptLayout layout = new PromptLayout(prefixes.textFor("REACT").length(),
                List.of("CORE 输出契约：先直接回答。"), List.of("SOFT 开放段"));

        TrialFailure ex = assertThrows(TrialFailure.class, () -> guard.require(prompt, layout));
        assertTrue(ex.getMessage().contains("锁定段必须位于开放段之前"));
    }

    /**
     * 结构正常时原样放行，且不因结构校验而改写正文。
     */
    @Test
    void acceptsWellFormedLayoutUnchanged() {
        String head = prefixes.textFor("REACT") + "\n\nCORE 输出契约：先直接回答。";
        String prompt = head + "\n\nSOFT 开放段\n\n## 工具目录\n- queryWeather：查天气";
        PromptLayout layout = new PromptLayout(head.length(),
                List.of("CORE 输出契约：先直接回答。"), List.of("SOFT 开放段"));

        assertEquals(prompt, guard.require(prompt, layout));
    }

    /**
     * 无结构信息（老链路）只做前缀校验，不因缺少 layout 而拦下正常提示。
     */
    @Test
    void layoutAbsentFallsBackToPrefixOnly() {
        String prompt = prefixes.textFor("REACT") + "\n\n只有开放段";
        assertEquals(prompt, guard.require(prompt, PromptLayout.EMPTY));
        assertEquals(prompt, guard.require(prompt, null));
    }
}
