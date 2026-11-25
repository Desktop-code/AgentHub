package com.agenthub.model.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.model.domain.model.ModelCredential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除和归属规则。不访问数据库。
 */
class ModelReferencePolicyTest {

    private final ModelReferencePolicy policy = new ModelReferencePolicy();

    /**
     * 还有实例时供应商和凭证都不能删；凭证必须属于声明的供应商。
     * 凭证的拒绝文案要带引用条数，用户才知道「有几个实例占着」而不是一句笼统的不能删。
     */
    @Test
    void rejectsDanglingReferences() {
        assertThrows(BizException.class, () -> policy.assertProviderDeletable(1));
        BizException credentialInUse = assertThrows(BizException.class,
                () -> policy.assertCredentialDeletable(2));
        assertTrue(credentialInUse.getMessage().contains("2 个模型实例"));
        assertTrue(credentialInUse.getMessage().contains("重置 Key"));
        assertDoesNotThrow(() -> policy.assertProviderDeletable(0));
        ModelCredential credential = new ModelCredential();
        credential.setProviderId(7L);
        assertThrows(BizException.class, () -> policy.assertCredentialBelongs(credential, 8L));
        assertDoesNotThrow(() -> policy.assertCredentialBelongs(credential, 7L));
    }
}
