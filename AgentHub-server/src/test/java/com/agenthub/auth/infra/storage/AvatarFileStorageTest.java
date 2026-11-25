package com.agenthub.auth.infra.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头像落盘校验。所属域：auth（单测）。
 */
class AvatarFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void save_rejectsNonImageExtension() {
        AvatarFileStorage storage = new AvatarFileStorage(tempDir.toString());
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(1L, "evil.exe", new byte[]{1, 2, 3}));
    }

    @Test
    void save_rejectsOversized() {
        AvatarFileStorage storage = new AvatarFileStorage(tempDir.toString());
        byte[] big = new byte[(int) AvatarFileStorage.MAX_BYTES + 1];
        assertThrows(IllegalArgumentException.class,
                () -> storage.save(1L, "a.png", big));
    }

    @Test
    void save_writesUnderPrefix() {
        AvatarFileStorage storage = new AvatarFileStorage(tempDir.toString());
        String url = storage.save(9L, "face.PNG", new byte[]{1, 2, 3, 4});
        assertTrue(url.startsWith(AvatarFileStorage.URL_PREFIX + "9/"));
        assertTrue(url.endsWith(".png"));
    }
}
