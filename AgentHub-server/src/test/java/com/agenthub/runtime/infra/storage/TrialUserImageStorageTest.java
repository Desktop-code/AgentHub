package com.agenthub.runtime.infra.storage;

import com.agenthub.runtime.domain.service.TrialFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附图落盘：data URI 写成文件，http 地址不代下载，坏格式拒绝。
 */
class TrialUserImageStorageTest {

    /** 1×1 PNG */
    private static final String PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @TempDir
    Path root;

    @Test
    void savesPngUnderSessionAndKeepsHttpUrl() throws Exception {
        TrialUserImageStorage storage = new TrialUserImageStorage(root);
        List<String> urls = storage.persist(12L, List.of(PNG, "https://example.com/a.png"));
        assertEquals(2, urls.size());
        assertTrue(urls.get(0).startsWith("/uploads/trial-images/12/"));
        assertTrue(urls.get(0).endsWith(".png"));
        assertEquals("https://example.com/a.png", urls.get(1));
        String relative = urls.get(0).substring("/uploads/trial-images/".length());
        assertTrue(Files.size(root.resolve(relative)) > 0);
        // 目录外不能被这次写入碰到：相对路径必须落在根下面
        assertTrue(root.resolve(relative).normalize().startsWith(root));
    }

    @Test
    void rejectsNonImageDataUri() {
        TrialUserImageStorage storage = new TrialUserImageStorage(root);
        assertThrows(TrialFailure.class, () -> storage.persist(1L, List.of("data:text/plain;base64,YQ==")));
    }
}
