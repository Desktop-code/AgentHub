package com.agenthub.model.domain.service;

import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型凭证解密的窄测。所属域：model 的 domain。
 *
 * <p>只钉一条不变量：解密模型的 Key 时，AAD **必须**是 {@code model_credential:{providerId}}。
 * 这条错了不会编译失败，只会在线上表现为「全部凭证突然解不开」，因此值得单独锁住。
 * 其它行为（凭证停用校验、错误文案、日志）不在本类职责内，由各调用方的既有用例覆盖。
 */
class ModelCredentialResolverTest {

    /** 正常路径：把密文与 providerId 拼出的 AAD 一起交给 SecretStore。 */
    @Test
    void decryptUsesProviderBoundContext() {
        SecretStore store = mock(SecretStore.class);
        when(store.decrypt("cipher", SecretContexts.modelCredential(7L))).thenReturn("sk-plain");

        String plain = ModelCredentialResolver.decryptKey(store, "cipher", 7L);

        assertEquals("sk-plain", plain);
        verify(store).decrypt(eq("cipher"), eq("model_credential:7"));
    }

    /** 解密失败必须原样抛出：包装成业务异常是调用方的事，本类不得吞掉或换型。 */
    @Test
    void decryptFailurePropagates() {
        SecretStore store = mock(SecretStore.class);
        when(store.decrypt(anyString(), anyString())).thenThrow(new IllegalArgumentException("context 不匹配"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ModelCredentialResolver.decryptKey(store, "cipher", 7L));

        assertEquals("context 不匹配", ex.getMessage());
    }
}
