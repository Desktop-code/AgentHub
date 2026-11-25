package com.agenthub.model.application;

import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.infra.crypto.AesGcmSecretStore;
import com.agenthub.spi.crypto.SecretContexts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证密文迁移任务的单测。只验证：旧密钥密文被重写为新密钥格式，已是新格式的行不动。
 */
class CredentialReencryptRunnerTest {

    private static final String OLD_KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String NEW_KEY = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    /**
     * 旧密文应被重写：调用 rewriteCipher，且新密文能被新密钥单独解开。
     */
    @Test
    void rewritesOldCipherWithCurrentKey() {
        ModelCredentialRepository repository = mock(ModelCredentialRepository.class);
        AesGcmSecretStore oldStore = new AesGcmSecretStore(OLD_KEY);
        String context = SecretContexts.modelCredential(9L);

        ModelCredential credential = new ModelCredential();
        credential.setId(42L);
        credential.setProviderId(9L);
        credential.setApiKeyCipher(oldStore.encrypt("sk-migrate-me", context));
        when(repository.listAll()).thenReturn(List.of(credential));

        AesGcmSecretStore rotated = new AesGcmSecretStore(NEW_KEY, List.of(OLD_KEY));
        new CredentialReencryptRunner(repository, rotated).run(null);

        ArgumentCaptor<String> cipher = ArgumentCaptor.forClass(String.class);
        verify(repository).rewriteCipher(anyLong(), cipher.capture(), any());
        String rewritten = cipher.getValue();
        assertTrue(rewritten.startsWith("v2:"));
        assertFalse(rotated.needsUpgrade(rewritten));
        // 用只含新密钥的实例也能解开，证明真的换了密钥而不是仅仅改了前缀
        assertEquals("sk-migrate-me", new AesGcmSecretStore(NEW_KEY).decrypt(rewritten, context));
    }

    /**
     * 已是当前密钥的行不应被重写，避免每次启动都做无谓写库。
     */
    @Test
    void skipsAlreadyCurrentCipher() {
        ModelCredentialRepository repository = mock(ModelCredentialRepository.class);
        AesGcmSecretStore store = new AesGcmSecretStore(NEW_KEY);

        ModelCredential credential = new ModelCredential();
        credential.setId(43L);
        credential.setProviderId(9L);
        credential.setApiKeyCipher(store.encrypt("sk-already-new", SecretContexts.modelCredential(9L)));
        when(repository.listAll()).thenReturn(List.of(credential));

        new CredentialReencryptRunner(repository, store).run(null);

        verify(repository, never()).rewriteCipher(anyLong(), any(), any());
    }
}
