package com.agenthub.runtime.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物作者与同名去重口径。所属域：runtime。
 *
 * <p>锁定两件事：作者只在产生处补一次（外层方案不得改写内层专家），
 * 同名文件只有「同一参与者」才算同一个槽位。
 */
class GeneratedFileOwnerTest {

    @Test
    void withOwnerFillsMissingIdentity() {
        GeneratedFile file = new GeneratedFile("report.md", 12).withOwner("p-1-writer", "报告撰写专家");

        assertEquals("p-1-writer", file.participantId());
        assertEquals("报告撰写专家", file.participantName());
        assertTrue(file.hasOwner());
    }

    @Test
    void withOwnerKeepsInnerAuthor() {
        GeneratedFile inner = new GeneratedFile("report.md", 12, "p-1-writer", "报告撰写专家");

        // 外层方案统一补身份时必须原样返回，不能覆盖真正的作者
        assertSame(inner, inner.withOwner("p-0-scheme", "企业综合台"));
        assertEquals("p-1-writer", inner.participantId());
    }

    @Test
    void blankOwnerDoesNotChangeAnything() {
        GeneratedFile file = new GeneratedFile("report.md", 12);

        assertSame(file, file.withOwner("", "企业综合台"));
        assertFalse(file.hasOwner());
    }

    @Test
    void mergingSameNameKeepsBothParticipants() {
        TrialTurn turn = new TrialTurn();
        turn.setFiles(List.of(new GeneratedFile("报告.md", 10, "p-1-a", "甲")));

        // 同名不同人：两份产物都要留，否则后者会改挂到前一位作者名下
        turn.mergeFiles(List.of(new GeneratedFile("报告.md", 20, "p-1-b", "乙")));
        assertEquals(2, turn.getFiles().size());

        // 同一人同名：原位覆盖，避免刷出一排重复卡片
        turn.mergeFiles(List.of(new GeneratedFile("报告.md", 30, "p-1-a", "甲")));
        assertEquals(2, turn.getFiles().size());
        GeneratedFile kept = turn.getFiles().stream()
                .filter((file) -> "p-1-a".equals(file.participantId()))
                .findFirst().orElseThrow();
        assertEquals(30L, kept.size());
    }
}
