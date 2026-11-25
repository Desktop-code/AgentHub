package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行模式、用途和提示词长度。用途必须能对上模型域的枚举名。
 */
class AgentChecksTest {

    @Test
    void runModeAndPurpose() {
        assertEquals("REACT", AgentChecks.runMode(" REACT "));
        assertNull(AgentChecks.optionalRunMode(" "));
        assertNull(AgentChecks.purpose(" "));
        assertEquals("CHAT", AgentChecks.purpose("CHAT"));
        // 旧码入参映射为新码后落库
        assertEquals("CHAT", AgentChecks.purpose("LLM"));
        assertEquals("ROUTER", AgentChecks.purpose("INTENT"));
        assertEquals("EMBEDDING", AgentChecks.purpose("EMBED"));
        assertEquals("VISION", AgentChecks.purpose("OCR"));
        assertEquals("VISION", AgentChecks.purpose("PDF"));
        assertEquals("SUMMARY", AgentChecks.purpose("SUMMARY"));
        assertEquals("MEMORY", AgentChecks.purpose("MEMORY"));
        assertThrows(BizException.class, () -> AgentChecks.runMode("GRAPH"));
        assertThrows(BizException.class, () -> AgentChecks.purpose("chat"));
    }

    @Test
    void priorityDefaultsAndPromptHasACeiling() {        assertEquals(100, AgentChecks.priority(null));
        assertEquals(0, AgentChecks.sortOrder(null));
        assertEquals(1, AgentChecks.initialStatus(null));
        assertThrows(BizException.class, () -> AgentChecks.priority(-1));
        String tooLong = "a".repeat(AgentChecks.PROMPT_MAX + 1);
        BizException ex = assertThrows(BizException.class, () -> AgentChecks.prompt(tooLong));
        assertTrue(ex.getMessage().contains("超过长度"));
    }

    /**
     * allow_orchestrated 收口：未传按模式默认；ROUTER 无论传什么强制 0。
     */
    @Test
    void allowOrchestratedDefaultsByRunMode() {
        // 非 ROUTER：未传默认允许；显式 false 才关
        assertEquals(1, AgentChecks.allowOrchestrated(null, AgentRunMode.REACT.name()));
        assertEquals(1, AgentChecks.allowOrchestrated(null, AgentRunMode.PLAN_AND_EXECUTE.name()));
        assertEquals(1, AgentChecks.allowOrchestrated(true, AgentRunMode.REACT.name()));
        assertEquals(0, AgentChecks.allowOrchestrated(false, AgentRunMode.REACT.name()));
        // ROUTER：编排根不可作子，请求体传 true 也必须落 0
        assertEquals(0, AgentChecks.allowOrchestrated(true, AgentRunMode.ROUTER.name()));
        assertEquals(0, AgentChecks.allowOrchestrated(null, AgentRunMode.ROUTER.name()));
    }

    /**
     * 列表口径：只有明确传 SINGLE 才收窄成「单兵」。
     *
     * <p>其它值必须按全量处理——评测、计费等 Agent 下拉还要能选到方案主控，
     * 不能因为某处误传就把它们从选项里抹掉。
     */
    @Test
    void singleScopeOnlyWhenExplicitlySingle() {
        assertTrue(AgentChecks.singleScope("SINGLE"));
        assertTrue(AgentChecks.singleScope(" single "));
        assertFalse(AgentChecks.singleScope("ALL"));
        assertFalse(AgentChecks.singleScope(""));
        assertFalse(AgentChecks.singleScope(null));
    }
}
