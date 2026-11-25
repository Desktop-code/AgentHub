package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口预处理闸门。所属域：runtime。
 *
 * <p>纯文字不得多出空步骤；只有 ENTRY_ROUTER 才走这份识别，管理端已选专家对话不能套上。
 */
class EntryPreprocessGateTest {

    @Test
    void neededWhenImageOrFilePresent() {
        assertFalse(EntryPreprocess.needed(List.of(), List.of()));
        assertFalse(EntryPreprocess.needed(null, null));
        assertTrue(EntryPreprocess.needed(List.of("data:image/png;base64,xx"), List.of()));
        assertTrue(EntryPreprocess.needed(List.of(),
                List.of(new UserMessageFiles.Ref("a.txt", 12, "/files/a.txt"))));
        assertTrue(EntryPreprocess.needed(List.of(),
                List.of(new UserMessageFiles.Ref("voice.webm", 24, "/files/voice.webm"))));
    }

    @Test
    void forEntryOnlyMatchesEntryRouter() {
        assertFalse(EntryPreprocess.forEntry(null));
        MaRunSpec other = new MaRunSpec(1L, "demo", "场景", "", "ROUTER_PARALLEL",
                AgentRuntimeConfig.defaultsFor("ROUTER"), List.of(), null, null, false);
        assertFalse(EntryPreprocess.forEntry(other));
        MaRunSpec entry = new MaRunSpec(2L, "entry", "对话入口", "", AgentRuntimeConfig.TEMPLATE_ENTRY_ROUTER,
                AgentRuntimeConfig.defaultsFor("ROUTER"), List.of(), null, null, true);
        assertTrue(EntryPreprocess.forEntry(entry));
    }

    @Test
    void audioNamesAreRecognized() {
        assertTrue(UserMessageFiles.isAudioName("voice.webm"));
        assertTrue(UserMessageFiles.isAudioName("note.WAV"));
        assertFalse(UserMessageFiles.isAudioName("scan.pdf"));
        assertFalse(UserMessageFiles.isAudioName("pic.png"));
    }
}
