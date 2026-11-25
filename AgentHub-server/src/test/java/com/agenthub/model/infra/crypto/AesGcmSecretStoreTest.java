package com.agenthub.model.infra.crypto;

import com.agenthub.spi.crypto.SecretContexts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AES-GCM 往返、AAD 绑定、主密钥轮换、掩码与主密钥校验。不连数据库。
 */
class AesGcmSecretStoreTest {

    private static final String KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String NEW_KEY = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
    private static final String CTX_A = SecretContexts.modelCredential(7L);
    private static final String CTX_B = SecretContexts.modelCredential(8L);

    /**
     * 同一明文两次加密应得到不同密文，但都能解回原文；密文带 v2 与密钥指纹前缀。
     */
    @Test
    void roundTripUsesFreshIv() {
        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        String first = store.encrypt("sk-test-1234567890", CTX_A);
        String second = store.encrypt("sk-test-1234567890", CTX_A);
        assertNotEquals(first, second);
        assertTrue(first.startsWith("v2:"));
        assertEquals(4, first.split(":", -1).length);
        assertEquals("sk-test-1234567890", store.decrypt(first, CTX_A));
        assertEquals("sk-test-1234567890", store.decrypt(second, CTX_A));
    }

    /**
     * AAD 必须匹配：把密文换到另一个 context 下必须解不开。这是防跨表/跨记录搬动的核心。
     */
    @Test
    void otherContextCannotDecrypt() {
        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        String cipher = store.encrypt("sk-live-abcdef", CTX_A);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.decrypt(cipher, CTX_B));
        assertFalse(ex.getMessage().contains("sk-live"));
        // 换成工具源的 context 同样失败
        assertThrows(IllegalArgumentException.class,
                () -> store.decrypt(cipher, SecretContexts.toolSource(7L)));
    }

    /**
     * 改动密文后必须失败，且异常里不带原文。
     */
    @Test
    void tamperedCipherCannotDecrypt() {
        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        String cipher = store.encrypt("sk-live-abcdef", CTX_A);
        String broken = cipher.substring(0, cipher.length() - 2) + "aa";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> store.decrypt(broken, CTX_A));
        assertFalse(ex.getMessage().contains("sk-live"));
    }

    /**
     * 轮换过渡期：旧密钥放进 previous-keys 后仍能读旧密文，且新写入使用新密钥并标记需要迁移。
     */
    @Test
    void rotationKeepsOldReadableAndMarksUpgrade() {
        AesGcmSecretStore oldStore = new AesGcmSecretStore(KEY);
        String oldCipher = oldStore.encrypt("sk-old-key", CTX_A);

        AesGcmSecretStore rotated = new AesGcmSecretStore(NEW_KEY, List.of(KEY));
        // 旧密文仍可解
        assertEquals("sk-old-key", rotated.decrypt(oldCipher, CTX_A));
        // 旧密文被判定为需要迁移到当前主密钥
        assertTrue(rotated.needsUpgrade(oldCipher));
        // 新写入用新密钥，无需再迁移
        String fresh = rotated.encrypt("sk-new-key", CTX_A);
        assertFalse(rotated.needsUpgrade(fresh));
        assertEquals(rotated.currentKeyId(), fresh.split(":", -1)[1]);
    }

    /**
     * 没有配置历史密钥时，旧主密钥的密文必须解不开，不能静默返回空串。
     */
    @Test
    void missingPreviousKeyFails() {
        String oldCipher = new AesGcmSecretStore(KEY).encrypt("sk-old-key", CTX_A);
        AesGcmSecretStore rotated = new AesGcmSecretStore(NEW_KEY);
        assertThrows(IllegalArgumentException.class, () -> rotated.decrypt(oldCipher, CTX_A));
    }

    /**
     * 升级前的旧格式（iv:ciphertext，无版本、无 AAD）仍能读，并标记为需要迁移。
     */
    @Test
    void legacyFormatStillReadable() throws Exception {
        String plain = "sk-legacy-123456";
        byte[] key = AesGcmSecretStore.decodeKey(KEY);
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String legacy = java.util.Base64.getEncoder().encodeToString(iv) + ":"
                + java.util.Base64.getEncoder().encodeToString(encrypted);

        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        assertEquals(plain, store.decrypt(legacy, CTX_A));
        assertTrue(store.needsUpgrade(legacy));
    }

    /**
     * 掩码保留前缀和末四位，中间不出现完整 Key。
     */
    @Test
    void maskHidesMiddle() {
        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        assertEquals("sk-t****7890", store.mask("sk-test-1234567890"));
        assertEquals("****cd", store.mask("abcd"));
        assertThrows(IllegalArgumentException.class, () -> store.mask("  "));
    }

    /**
     * 空 context 必须被拒绝：否则会产生可任意搬动的无绑定密文。
     */
    @Test
    void blankContextRejected() {
        AesGcmSecretStore store = new AesGcmSecretStore(KEY);
        assertThrows(IllegalArgumentException.class, () -> store.encrypt("sk-x", " "));
        assertThrows(IllegalArgumentException.class, () -> store.decrypt("v2:a:b:c", null));
    }

    /**
     * 空主密钥要在启动期被拒绝，文案指向配置项。
     */
    @Test
    void blankMasterKeyFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> AesGcmSecretStore.decodeKey(" "));
        assertTrue(ex.getMessage().contains("AGENTHUB_SECRET_KEY"));
        assertThrows(IllegalStateException.class, () -> new AesGcmSecretStore("abcd"));
    }
}
