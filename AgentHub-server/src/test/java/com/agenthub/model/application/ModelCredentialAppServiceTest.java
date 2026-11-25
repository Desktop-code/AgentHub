package com.agenthub.model.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.api.dto.CredentialResetReq;
import com.agenthub.model.api.dto.CredentialResp;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证重置 Key 的单测。所属域：model。
 * <p>
 * 核心口径：
 * <ol>
 *   <li>重置只换密文与掩码，凭证 id / 供应商绑定 / 名称 / 状态都不变——实例继续按原凭证出站；</li>
 *   <li>响应里只有掩码，没有明文字段；</li>
 *   <li>被实例引用时仍然允许重置（删除才被拒），且响应带引用条数供前端禁用删除。</li>
 * </ol>
 * 约束：仓储、加密、操作日志全部 Mock，零数据库、零网络、不落任何真实 Key。
 */
class ModelCredentialAppServiceTest {

    private static final long PROVIDER_ID = 3L;
    private static final long CREDENTIAL_ID = 7L;

    /**
     * 重置后密文与掩码都被替换，id / name / status 不变，响应不含明文。
     */
    @Test
    @DisplayName("重置 Key：只换密文与掩码，响应只出掩码")
    void resetKeyReplacesCipherAndReturnsMaskOnly() {
        ModelCredentialRepository credentials = mock(ModelCredentialRepository.class);
        ModelInstanceRepository instances = mock(ModelInstanceRepository.class);
        ModelLookup lookup = mock(ModelLookup.class);
        SecretStore secretStore = mock(SecretStore.class);
        OperatorContext operatorContext = mock(OperatorContext.class);
        OperLogWriter operLogWriter = mock(OperLogWriter.class);

        ModelCredential existing = new ModelCredential();
        existing.setId(CREDENTIAL_ID);
        existing.setProviderId(PROVIDER_ID);
        existing.setName("主 Key");
        existing.setStatus(1);
        existing.setApiKeyCipher("old-cipher");
        existing.setApiKeyMask("sk-****0000");

        when(lookup.requireProvider(PROVIDER_ID)).thenReturn(null);
        when(lookup.requireCredential(CREDENTIAL_ID)).thenReturn(existing);
        when(secretStore.encrypt("sk-new-plain", SecretContexts.modelCredential(PROVIDER_ID)))
                .thenReturn("new-cipher");
        when(secretStore.mask("sk-new-plain")).thenReturn("sk-****1234");
        when(operatorContext.requireUserId()).thenReturn(1L);
        // 重置后重新查库拿最新行：这里直接回同一对象（仓储 update 已把它改写）
        when(instances.countByCredentialId(CREDENTIAL_ID)).thenReturn(4);

        ModelCredentialAppService service = new ModelCredentialAppService(
                credentials, instances, lookup, new ModelReferencePolicy(),
                secretStore, operatorContext, operLogWriter);

        CredentialResetReq req = new CredentialResetReq();
        req.setApiKey("sk-new-plain");
        CredentialResp resp = service.resetKey(PROVIDER_ID, CREDENTIAL_ID, req);

        // 存储侧只动了密文和掩码
        assertEquals("new-cipher", existing.getApiKeyCipher());
        assertEquals("sk-****1234", existing.getApiKeyMask());
        assertEquals("主 Key", existing.getName());
        assertEquals(1, existing.getStatus());
        assertEquals(PROVIDER_ID, existing.getProviderId());
        verify(credentials).update(eq(existing), eq(1L));

        // 响应只出掩码：DTO 没有明文字段，掩码必须是新值
        assertEquals("sk-****1234", resp.getApiKeyMask());
        assertEquals(CREDENTIAL_ID, resp.getId());
        assertEquals(4, resp.getInstanceCount());
        // 引用条数只是提示，重置本身不因被引用而拒绝
        assertTrue(resp.getInstanceCount() > 0);
    }

    /**
     * 操作日志只记凭证名，不含 Key。
     */
    @Test
    @DisplayName("重置 Key 的操作日志不含明文")
    void resetKeyLogsNameOnly() {
        ModelCredentialRepository credentials = mock(ModelCredentialRepository.class);
        ModelInstanceRepository instances = mock(ModelInstanceRepository.class);
        ModelLookup lookup = mock(ModelLookup.class);
        SecretStore secretStore = mock(SecretStore.class);
        OperatorContext operatorContext = mock(OperatorContext.class);
        OperLogWriter operLogWriter = mock(OperLogWriter.class);

        ModelCredential existing = new ModelCredential();
        existing.setId(CREDENTIAL_ID);
        existing.setProviderId(PROVIDER_ID);
        existing.setName("备用 Key");
        existing.setStatus(1);

        when(lookup.requireProvider(PROVIDER_ID)).thenReturn(null);
        when(lookup.requireCredential(CREDENTIAL_ID)).thenReturn(existing);
        when(secretStore.encrypt(any(), any())).thenReturn("new-cipher");
        when(secretStore.mask(any())).thenReturn("sk-****9876");
        when(operatorContext.requireUserId()).thenReturn(1L);

        ModelCredentialAppService service = new ModelCredentialAppService(
                credentials, instances, lookup, new ModelReferencePolicy(),
                secretStore, operatorContext, operLogWriter);

        CredentialResetReq req = new CredentialResetReq();
        req.setApiKey("sk-secret-value");
        service.resetKey(PROVIDER_ID, CREDENTIAL_ID, req);

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(operLogWriter).write(eq("model"), eq("resetKey"), eq("model_credential"),
                eq(String.valueOf(CREDENTIAL_ID)), summary.capture());
        assertFalse(summary.getValue().contains("sk-secret-value"));
        assertTrue(summary.getValue().contains("备用 Key"));
    }

    /**
     * 引用数取不到时，默认按 0 展示，不影响重置本身成功。
     */
    @Test
    @DisplayName("重置 Key 不依赖实例计数即可成功")
    void resetKeyWorksWithoutInstanceCount() {
        ModelCredentialRepository credentials = mock(ModelCredentialRepository.class);
        ModelInstanceRepository instances = mock(ModelInstanceRepository.class);
        ModelLookup lookup = mock(ModelLookup.class);
        SecretStore secretStore = mock(SecretStore.class);
        OperatorContext operatorContext = mock(OperatorContext.class);
        OperLogWriter operLogWriter = mock(OperLogWriter.class);

        ModelCredential existing = new ModelCredential();
        existing.setId(CREDENTIAL_ID);
        existing.setProviderId(PROVIDER_ID);
        existing.setName("单 Key");
        existing.setStatus(1);

        when(lookup.requireProvider(PROVIDER_ID)).thenReturn(null);
        when(lookup.requireCredential(CREDENTIAL_ID)).thenReturn(existing);
        when(secretStore.encrypt(any(), any())).thenReturn("cipher-2");
        when(secretStore.mask(any())).thenReturn("sk-****1111");
        when(operatorContext.requireUserId()).thenReturn(1L);
        // countByCredentialId 不 stub：Mockito 默认返回 0，模拟「查不到引用」的路径

        ModelCredentialAppService service = new ModelCredentialAppService(
                credentials, instances, lookup, new ModelReferencePolicy(),
                secretStore, operatorContext, operLogWriter);

        CredentialResetReq req = new CredentialResetReq();
        req.setApiKey("sk-another");
        CredentialResp resp = service.resetKey(PROVIDER_ID, CREDENTIAL_ID, req);

        // 没引用时计数是 0；掩码仍是后端算出的掩码，不是明文
        assertEquals(0, resp.getInstanceCount());
        assertEquals("sk-****1111", resp.getApiKeyMask());
        assertNotEquals("sk-another", resp.getApiKeyMask());
    }
}
