package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.GeneratedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流节点文件范围：署名、沙箱会话、关闭后不再残留。
 */
class WorkflowAgentFileScopeTest {

    @AfterEach
    void close() {
        WorkflowAgentFileScope.close();
    }

    @Test
    void closedScopeKeepsRunIdAndDropsFiles() {
        assertEquals(88L, WorkflowAgentFileScope.sandboxSessionId(88L));
        assertNull(WorkflowAgentFileScope.fileProgress());
        WorkflowAgentFileScope.collect(List.of(new GeneratedFile("2048.py", 4)));
        assertTrue(WorkflowAgentFileScope.snapshot().isEmpty());
    }

    @Test
    void openScopeStampsOwnerAndUsesWorkbenchSession() {
        AtomicReference<GeneratedFile> live = new AtomicReference<>();
        TrialProgress upstream = new TrialProgress() {
            @Override
            public void phase(String phase) {
            }

            @Override
            public void route(String summary) {
            }

            @Override
            public void reasoning(String delta) {
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
            }

            @Override
            public void assistant(String delta) {
                throw new AssertionError("子专家正文不应转发");
            }

            @Override
            public void file(GeneratedFile file) {
                live.set(file);
            }
        };
        WorkflowAgentFileScope.open(upstream, "p-0-root-mini_game_dev", "小游戏研发", 12L);
        assertEquals(12L, WorkflowAgentFileScope.sandboxSessionId(99L));
        WorkflowAgentFileScope.fileProgress().file(new GeneratedFile("2048.py", 20));
        WorkflowAgentFileScope.fileProgress().assistant("不应出现");
        WorkflowAgentFileScope.collect(List.of(new GeneratedFile("2048.py", 20)));
        List<GeneratedFile> saved = new ArrayList<>(WorkflowAgentFileScope.snapshot());
        WorkflowAgentFileScope.close();

        assertEquals("p-0-root-mini_game_dev", live.get().participantId());
        assertEquals("小游戏研发", live.get().participantName());
        assertEquals(1, saved.size());
        assertEquals("2048.py", saved.get(0).fileName());
        assertEquals("p-0-root-mini_game_dev", saved.get(0).participantId());
        assertEquals(99L, WorkflowAgentFileScope.sandboxSessionId(99L));
    }
}
