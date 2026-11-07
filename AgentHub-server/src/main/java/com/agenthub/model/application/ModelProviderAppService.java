package com.agenthub.model.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.api.dto.CredentialResp;
import com.agenthub.model.api.dto.ProviderCreateReq;
import com.agenthub.model.api.dto.ProviderResp;
import com.agenthub.model.api.dto.ProviderUpdateReq;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商用例。所属域：model。
 * 创建默认不带凭证（控制台后置到凭证弹窗）；若请求带齐 credentialName+apiKey 则仍建首条。
 * 名称全局唯一（未删除范围内）。
 */
@Service
public class ModelProviderAppService {

    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final ModelInstanceRepository instances;
    private final ModelLookup lookup;
    private final ModelReferencePolicy references;
    private final SecretStore secretStore;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param providers       供应商
     * @param credentials     凭证
     * @param instances       用来判断能否删除
     * @param lookup          按 id 取值
     * @param references      引用规则
     * @param secretStore     加密首条 Key
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志，摘要不含 Key
     */
    public ModelProviderAppService(ModelProviderRepository providers, ModelCredentialRepository credentials,
                                   ModelInstanceRepository instances, ModelLookup lookup,
                                   ModelReferencePolicy references, SecretStore secretStore,
                                   OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.providers = providers;
        this.credentials = credentials;
        this.instances = instances;
        this.lookup = lookup;
        this.references = references;
        this.secretStore = secretStore;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @return 卡片列表。掩码取第一条启用凭证，没有启用凭证时取最早一条
     */
    public List<ProviderResp> list() {
        Map<Long, List<ModelCredential>> grouped = credentials.listAll().stream()
                .collect(Collectors.groupingBy(ModelCredential::getProviderId));
        // 一次取「含平台级实例」的供应商集合：卡片用它在右上角显示锁，避免逐卡计数
        Set<Long> lockedProviderIds = instances.platformLockedProviderIds();
        List<ProviderResp> result = new ArrayList<>();
        for (ModelProvider provider : providers.listAll()) {
            List<ModelCredential> rows = grouped.getOrDefault(provider.getId(), List.of());
            ProviderResp resp = toResp(provider, rows);
            resp.setPlatformLocked(lockedProviderIds.contains(provider.getId()));
            result.add(resp);
        }
        return result;
    }

    /**
     * @param id 供应商 id
     * @return 详情和凭证掩码，不含明文
     */
    public ProviderResp get(Long id) {
        ModelProvider provider = lookup.requireProvider(id);
        ProviderResp resp = toResp(provider, credentials.listByProviderId(id));
        resp.setPlatformLocked(instances.countPlatformLockedByProvider(id) > 0);
        return resp;
    }

    /**
     * @param req 供应商；凭证字段均可空
     * @return 详情，凭证行只有掩码（不回明文）
     */
    @Transactional
    public ProviderResp create(ProviderCreateReq req) {
        String name = ModelChecks.requiredText(req.getName(), 64, "名称");
        assertNameFree(name, null);
        ModelProvider provider = new ModelProvider();
        provider.setName(name);
        provider.setProtocol(ModelChecks.protocol(req.getProtocol()).name());
        provider.setBaseUrl(ModelChecks.baseUrl(req.getBaseUrl()));
        provider.setStatus(ModelChecks.status(req.getStatus()));
        provider.setRemark(ModelChecks.remark(req.getRemark()));
        Long operatorId = operatorContext.requireUserId();
        Long providerId = providers.insert(provider, operatorId);

        // 旧客户端仍可一次带首条凭证；控制台新建不传这两项
        boolean hasCredName = req.getCredentialName() != null && !req.getCredentialName().isBlank();
        boolean hasApiKey = req.getApiKey() != null && !req.getApiKey().isBlank();
        if (hasCredName || hasApiKey) {
            if (!hasCredName || !hasApiKey) {
                throw new BizException(ErrorCode.BAD_REQUEST, "创建首条凭证时请同时填写凭证名和 API Key");
            }
            // 明文只在建凭证时用于加密与算掩码，不进入返回值
            String plainKey = ModelChecks.apiKey(req.getApiKey());
            String credentialName = ModelChecks.requiredText(req.getCredentialName(), 64, "凭证名");
            if (credentials.nameExists(providerId, credentialName, null)) {
                throw new BizException(ErrorCode.DUPLICATE, "同一供应商下凭证名已存在");
            }
            credentials.insert(
                    newCredential(providerId, credentialName, plainKey, ModelChecks.status(req.getStatus())),
                    operatorId);
        }

        operLogWriter.write("model", "create", "model_provider", String.valueOf(providerId), "创建供应商 " + provider.getName());
        return get(providerId);
    }

    /**
     * 已有实例时不允许改协议，避免探活路径和已保存的模型对不上。
     *
     * @param id  供应商 id
     * @param req 新资料，不含 Key
     * @return 更新后的详情
     */
    @Transactional
    public ProviderResp update(Long id, ProviderUpdateReq req) {
        ModelProvider existing = lookup.requireProvider(id);
        ModelProtocol protocol = ModelChecks.protocol(req.getProtocol());
        if (!protocol.name().equals(existing.getProtocol()) && instances.countByProviderId(id) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该供应商下仍有模型实例，不能修改协议");
        }
        int newStatus = ModelChecks.status(req.getStatus());
        String action = existing.getStatus() != null && existing.getStatus() != newStatus
                ? (RecordStatus.isEnabled(newStatus) ? "enable" : "disable")
                : "update";
        String name = ModelChecks.requiredText(req.getName(), 64, "名称");
        assertNameFree(name, id);
        existing.setName(name);
        existing.setProtocol(protocol.name());
        existing.setBaseUrl(ModelChecks.baseUrl(req.getBaseUrl()));
        existing.setStatus(newStatus);
        existing.setRemark(ModelChecks.remark(req.getRemark()));
        providers.update(existing, operatorContext.requireUserId());
        operLogWriter.write("model", action, "model_provider", String.valueOf(id), "更新供应商 " + existing.getName());
        return get(id);
    }

    /**
     * 仍有实例则拒绝。没有实例时连同凭证一起逻辑删除，避免留下无法挂靠的 Key。
     *
     * @param id 供应商 id
     */
    @Transactional
    public void delete(Long id) {
        ModelProvider existing = lookup.requireProvider(id);
        // 平台级实例可能被记忆/检索链路依赖：厂商卡片不可删，先走换绑或取消平台级
        if (instances.countPlatformLockedByProvider(id) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该供应商下存在平台级模型实例，不能删除；请先换绑或取消平台级");
        }
        references.assertProviderDeletable(instances.countByProviderId(id));
        Long operatorId = operatorContext.requireUserId();
        List<ModelCredential> rows = credentials.listByProviderId(id);
        for (ModelCredential row : rows) {
            references.assertCredentialDeletable(instances.countByCredentialId(row.getId()));
            credentials.logicalDelete(row.getId(), operatorId);
        }
        providers.logicalDelete(id, operatorId);
        operLogWriter.write("model", "remove", "model_provider", String.valueOf(id),
                "删除供应商 " + existing.getName() + "，凭证 " + rows.size() + " 条");
    }

    /**
     * 供应商名称冲突则抛 DUPLICATE。
     *
     * @param name      名称
     * @param excludeId 更新时排除自身
     */
    private void assertNameFree(String name, Long excludeId) {
        if (providers.nameExists(name, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "供应商名称已存在");
        }
    }

    private ModelCredential newCredential(Long providerId, String name, String plainKey, int status) {
        ModelCredential credential = new ModelCredential();
        credential.setProviderId(providerId);
        credential.setName(name);
        // 与凭证接口保持同一绑定规则：密文只能在该供应商下解密
        credential.setApiKeyCipher(secretStore.encrypt(plainKey, SecretContexts.modelCredential(providerId)));
        credential.setApiKeyMask(secretStore.mask(plainKey));
        credential.setStatus(status);
        return credential;
    }

    private ProviderResp toResp(ModelProvider provider, List<ModelCredential> rows) {
        ProviderResp resp = new ProviderResp();
        resp.setId(provider.getId());
        resp.setName(provider.getName());
        resp.setProtocol(provider.getProtocol());
        resp.setBaseUrl(provider.getBaseUrl());
        resp.setStatus(provider.getStatus());
        resp.setRemark(provider.getRemark());
        resp.setCredentialCount(rows.size());
        resp.setApiKeyMask(summaryMask(rows));
        resp.setCreateTime(provider.getCreateTime());
        resp.setUpdateTime(provider.getUpdateTime());
        List<CredentialResp> views = new ArrayList<>();
        for (ModelCredential row : rows) {
            views.add(toCredential(row));
        }
        resp.setCredentials(views);
        return resp;
    }

    private CredentialResp toCredential(ModelCredential row) {
        CredentialResp resp = new CredentialResp();
        resp.setId(row.getId());
        resp.setProviderId(row.getProviderId());
        resp.setName(row.getName());
        resp.setApiKeyMask(row.getApiKeyMask());
        resp.setStatus(row.getStatus());
        resp.setCreateTime(row.getCreateTime());
        return resp;
    }

    private String summaryMask(List<ModelCredential> rows) {
        for (ModelCredential row : rows) {
            if (RecordStatus.isEnabled(row.getStatus())) {
                return row.getApiKeyMask();
            }
        }
        return rows.isEmpty() ? null : rows.get(0).getApiKeyMask();
    }
}
