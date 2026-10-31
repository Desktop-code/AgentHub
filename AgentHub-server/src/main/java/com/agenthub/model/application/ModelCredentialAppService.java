package com.agenthub.model.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.api.dto.CredentialCreateReq;
import com.agenthub.model.api.dto.CredentialResetReq;
import com.agenthub.model.api.dto.CredentialResp;
import com.agenthub.model.api.dto.CredentialUpdateReq;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 凭证用例。所属域：model。
 * 创建与重置只改密文和掩码，响应仅返回掩码，绝不回明文。操作日志只记凭证名，不记 Key 和密文。
 */
@Service
public class ModelCredentialAppService {

    private final ModelCredentialRepository credentials;
    private final ModelInstanceRepository instances;
    private final ModelLookup lookup;
    private final ModelReferencePolicy references;
    private final SecretStore secretStore;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param credentials     凭证
     * @param instances       引用计数
     * @param lookup          按 id 取值
     * @param references      删除和归属校验
     * @param secretStore     加密与掩码
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public ModelCredentialAppService(ModelCredentialRepository credentials, ModelInstanceRepository instances,
                                     ModelLookup lookup, ModelReferencePolicy references, SecretStore secretStore,
                                     OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.credentials = credentials;
        this.instances = instances;
        this.lookup = lookup;
        this.references = references;
        this.secretStore = secretStore;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param providerId 供应商 id
     * @param req        名称和新 Key
     * @return 只含掩码的凭证；不回明文，避免 UI 或网络面板二次泄露
     */
    @Transactional
    public CredentialResp create(Long providerId, CredentialCreateReq req) {
        lookup.requireProvider(providerId);
        String name = ModelChecks.requiredText(req.getName(), 64, "凭证名");
        assertNameFree(providerId, name, null);
        // 明文只在本次方法内用于加密和算掩码，方法结束即失去引用
        String plainKey = ModelChecks.apiKey(req.getApiKey());
        ModelCredential credential = new ModelCredential();
        credential.setProviderId(providerId);
        credential.setName(name);
        // context 绑定供应商：密文被搬到别的供应商下即无法解密
        credential.setApiKeyCipher(secretStore.encrypt(plainKey, SecretContexts.modelCredential(providerId)));
        credential.setApiKeyMask(secretStore.mask(plainKey));
        credential.setStatus(ModelChecks.status(req.getStatus()));
        Long id = credentials.insert(credential, operatorContext.requireUserId());
        operLogWriter.write("model", "create", "model_credential", String.valueOf(id), "添加凭证 " + name);
        return toResp(lookup.requireCredential(id));
    }

    /**
     * @param providerId   路径上的供应商，用来确认凭证没有串到别的厂商
     * @param credentialId 凭证 id
     * @param req          新名称和状态
     * @return 不含明文的凭证
     */
    @Transactional
    public CredentialResp update(Long providerId, Long credentialId, CredentialUpdateReq req) {
        ModelCredential existing = requireOwned(providerId, credentialId);
        String name = ModelChecks.requiredText(req.getName(), 64, "凭证名");
        assertNameFree(providerId, name, credentialId);
        int newStatus = ModelChecks.status(req.getStatus());
        String action = existing.getStatus() != null && existing.getStatus() != newStatus
                ? (RecordStatus.isEnabled(newStatus) ? "enable" : "disable")
                : "update";
        existing.setName(name);
        existing.setStatus(newStatus);
        credentials.update(existing, operatorContext.requireUserId());
        operLogWriter.write("model", action, "model_credential", String.valueOf(credentialId), "更新凭证 " + name);
        return toResp(lookup.requireCredential(credentialId));
    }

    /**
     * @param providerId   供应商 id
     * @param credentialId 凭证 id
     */
    @Transactional
    public void delete(Long providerId, Long credentialId) {
        ModelCredential existing = requireOwned(providerId, credentialId);
        references.assertCredentialDeletable(instances.countByCredentialId(credentialId));
        credentials.logicalDelete(credentialId, operatorContext.requireUserId());
        operLogWriter.write("model", "remove", "model_credential", String.valueOf(credentialId), "删除凭证 " + existing.getName());
    }

    /**
     * @param providerId   供应商 id
     * @param credentialId 凭证 id
     * @param req          新的明文 Key
     * @return 只含新掩码的凭证；明文不出接口
     */
    @Transactional
    public CredentialResp resetKey(Long providerId, Long credentialId, CredentialResetReq req) {
        ModelCredential existing = requireOwned(providerId, credentialId);
        // 明文仅用于重新加密与重算掩码；context 仍绑原供应商，搬表后不可解
        String plainKey = ModelChecks.apiKey(req.getApiKey());
        existing.setApiKeyCipher(secretStore.encrypt(plainKey, SecretContexts.modelCredential(existing.getProviderId())));
        existing.setApiKeyMask(secretStore.mask(plainKey));
        credentials.update(existing, operatorContext.requireUserId());
        operLogWriter.write("model", "resetKey", "model_credential", String.valueOf(credentialId), "重置凭证 " + existing.getName());
        return toResp(lookup.requireCredential(credentialId));
    }

    private ModelCredential requireOwned(Long providerId, Long credentialId) {
        lookup.requireProvider(providerId);
        ModelCredential credential = lookup.requireCredential(credentialId);
        references.assertCredentialBelongs(credential, providerId);
        return credential;
    }

    private void assertNameFree(Long providerId, String name, Long excludeId) {
        if (credentials.nameExists(providerId, name, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "同一供应商下凭证名已存在");
        }
    }

    private CredentialResp toResp(ModelCredential row) {
        CredentialResp resp = new CredentialResp();
        resp.setId(row.getId());
        resp.setProviderId(row.getProviderId());
        resp.setName(row.getName());
        resp.setApiKeyMask(row.getApiKeyMask());
        resp.setStatus(row.getStatus());
        resp.setCreateTime(row.getCreateTime());
        return resp;
    }
}
