package com.agenthub.model.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCredential;
import org.springframework.stereotype.Component;

/**
 * 供应商、凭证、实例之间的引用规则。所属域：model。
 * 库里不建物理外键，删除和绑定都在这里拒绝不合法关系。
 */
@Component
public class ModelReferencePolicy {

    /**
     * 还有实例时不允许删供应商，避免卡片消失后实例失去协议和地址。
     *
     * @param instanceCount 未删除实例数
     */
    public void assertProviderDeletable(int instanceCount) {
        if (instanceCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该供应商下仍有模型实例，请先删除实例");
        }
    }

    /**
     * 实例显式绑定凭证，被引用时不能删，否则探活无法解密。
     *
     * @param instanceCount 未删除实例数
     */
    public void assertCredentialDeletable(int instanceCount) {
        if (instanceCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该凭证仍被模型实例引用，不能删除");
        }
    }

    /**
     * 实例上的凭证必须属于所选供应商，防止串用别的厂商 Key。
     *
     * @param credential 已查到的凭证
     * @param providerId 实例声明的供应商
     */
    public void assertCredentialBelongs(ModelCredential credential, Long providerId) {
        if (credential == null || providerId == null || !providerId.equals(credential.getProviderId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "凭证不属于该供应商");
        }
    }
}
