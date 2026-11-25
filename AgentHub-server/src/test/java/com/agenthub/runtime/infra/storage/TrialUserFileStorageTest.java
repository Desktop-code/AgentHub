package com.agenthub.runtime.infra.storage;

import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 非图片附件落盘：按扩展名写入会话目录，超限和空内容必须说明。
 */
class TrialUserFileStorageTest {

    @TempDir
    Path root;

    @Test
    void persistWritesBytesAndKeepsDisplayName() throws Exception {
        TrialUserFileStorage storage = new TrialUserFileStorage(root);
        String payload = dataUrl("季度表".getBytes(StandardCharsets.UTF_8));
        UserMessageFiles.Draft draft = new UserMessageFiles.Draft("季度表.pdf", "pdf", payload);
        List<UserMessageFiles.Ref> stored = storage.persist(9L, List.of(draft));
        assertEquals(1, stored.size());
        assertEquals("季度表.pdf", stored.get(0).name());
        assertEquals("季度表".getBytes(StandardCharsets.UTF_8).length, stored.get(0).size());
        assertTrue(stored.get(0).url().startsWith("/uploads/trial-files/9/"));
        assertTrue(stored.get(0).url().endsWith(".pdf"));
        String relative = stored.get(0).url().substring("/uploads/trial-files/".length());
        assertEquals("季度表", Files.readString(root.resolve(relative)));
    }

    @Test
    void rejectsEmptyAndOversized() {
        TrialUserFileStorage storage = new TrialUserFileStorage(root);
        UserMessageFiles.Draft empty = new UserMessageFiles.Draft("a.pdf", "pdf", "data:application/pdf;base64,");
        TrialFailure blank = assertThrows(TrialFailure.class, () -> storage.persist(3L, List.of(empty)));
        assertTrue(blank.getMessage().contains("为空"));
        byte[] tooBig = new byte[UserMessageFiles.MAX_BYTES + 1];
        UserMessageFiles.Draft huge = new UserMessageFiles.Draft("big.xlsx", "xlsx", dataUrl(tooBig));
        TrialFailure over = assertThrows(TrialFailure.class, () -> storage.persist(3L, List.of(huge)));
        assertTrue(over.getMessage().contains("12MB"));
    }

    /**
     * @param bytes 原文
     * @return 带 base64 的 data URI
     */
    private static String dataUrl(byte[] bytes) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
