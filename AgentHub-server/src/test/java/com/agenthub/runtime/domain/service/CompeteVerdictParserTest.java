package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竞争选优选优结论解析测试。所属域：runtime。
 *
 * <p>只测纯解析契约：JSON / 代码块 / 单 key 兜底 / 非法赢家丢弃 / 理由截断。
 */
class CompeteVerdictParserTest {

    private static final List<String> KEYS = List.of("a", "b");

    @Test
    void parsesPlainJsonVerdict() {
        Optional<CompeteVerdictParser.Verdict> verdict =
                CompeteVerdictParser.parse("{\"winner\":\"b\",\"reason\":\"覆盖更完整\"}", KEYS);

        assertTrue(verdict.isPresent());
        assertEquals("b", verdict.get().winnerKey());
        assertEquals("覆盖更完整", verdict.get().reason());
    }

    @Test
    void parsesFencedJsonAndAliasFields() {
        String reply = "```json\n{\"winner_key\":\"A\",\"why\":\"更准确\"}\n```";

        Optional<CompeteVerdictParser.Verdict> verdict = CompeteVerdictParser.parse(reply, KEYS);

        assertTrue(verdict.isPresent());
        // 大小写不敏感匹配，但回写候选原值
        assertEquals("a", verdict.get().winnerKey());
        assertEquals("更准确", verdict.get().reason());
    }

    @Test
    void fallsBackToSingleKeyWhenModelIgnoresJson() {
        Optional<CompeteVerdictParser.Verdict> verdict = CompeteVerdictParser.parse("我选 b", KEYS);

        assertTrue(verdict.isPresent());
        assertEquals("b", verdict.get().winnerKey());
        assertTrue(verdict.get().reason().isEmpty());
    }

    @Test
    void rejectsWinnerOutsideCandidates() {
        assertTrue(CompeteVerdictParser.parse("{\"winner\":\"c\",\"reason\":\"x\"}", KEYS).isEmpty());
    }

    @Test
    void rejectsBlankReply() {
        assertTrue(CompeteVerdictParser.parse("   ", KEYS).isEmpty());
        assertTrue(CompeteVerdictParser.parse(null, KEYS).isEmpty());
    }

    @Test
    void reasonIsNormalizedAndTruncated() {
        String longReason = "理由".repeat(100);
        Optional<CompeteVerdictParser.Verdict> verdict =
                CompeteVerdictParser.parse("{\"winner\":\"a\",\"reason\":\"  " + longReason + "\"}", KEYS);

        assertTrue(verdict.isPresent());
        assertTrue(verdict.get().reason().length() <= 121, String.valueOf(verdict.get().reason().length()));
        assertTrue(verdict.get().reason().endsWith("…"));
    }

    @Test
    void toJsonKeepsWinnerAndReason() {
        String json = CompeteVerdictParser.toJson(new CompeteVerdictParser.Verdict("b", "更完整"));

        assertTrue(json.contains("\"winner\":\"b\""), json);
        assertTrue(json.contains("\"reason\":\"更完整\""), json);
    }
}
