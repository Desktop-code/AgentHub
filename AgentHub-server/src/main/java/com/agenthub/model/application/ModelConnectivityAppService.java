package com.agenthub.model.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.api.dto.ConnectivityResp;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.service.ModelCredentialResolver;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.agenthub.model.domain.service.PurposeRoute;
import com.agenthub.model.infra.adapter.ModelClientFactory;
import com.agenthub.model.infra.adapter.ProbeCommand;
import com.agenthub.model.infra.adapter.ProbeResult;
import com.agenthub.spi.crypto.SecretStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 连通测试。所属域：model。只编排解密和适配器，不拼 HTTP 报文。
 * 先经 {@link ModelPurposeMatrix} 分流：未适配格直接记失败文案，不解密、不打 Chat。
 * 成功和失败都写入最近连通三列。日志只记实例、协议、是否成功和耗时，不记 Key、密文和摘要。
 */
@Slf4j
@Service
public class ModelConnectivityAppService {

    private final ModelLookup lookup;
    private final ModelInstanceRepository instances;
    private final ModelReferencePolicy references;
    private final SecretStore secretStore;
    private final ModelClientFactory clients;
    private final OperLogWriter operLogWriter;

    /**
     * @param lookup        实例、供应商、凭证
     * @param instances     回写最近连通，不改实例配置
     * @param references    凭证必须属于该供应商
     * @param secretStore   解密出站 Key
     * @param clients       协议适配器
     * @param operLogWriter 操作日志
     */
    public ModelConnectivityAppService(ModelLookup lookup, ModelInstanceRepository instances,
                                       ModelReferencePolicy references, SecretStore secretStore,
                                       ModelClientFactory clients, OperLogWriter operLogWriter) {
        this.lookup = lookup;
        this.instances = instances;
        this.references = references;
        this.secretStore = secretStore;
        this.clients = clients;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param id 实例 id
     * @return success、耗时和脱敏说明
     */
    public ConnectivityResp test(Long id) {
        ModelInstance instance = lookup.requireInstance(id);
        ModelProvider provider = lookup.requireProvider(instance.getProviderId());
        ModelCredential credential = lookup.requireCredential(instance.getCredentialId());
        references.assertCredentialBelongs(credential, provider.getId());
        ModelProtocol protocol = ModelChecks.protocol(provider.getProtocol());
        ModelCapability capability = ModelChecks.capability(instance.getCapability());
        // CUSTOM 仍走 HTTP 可达；其余协议先过矩阵，未适配不解密、不出站
        if (protocol != ModelProtocol.CUSTOM) {
            PurposeRoute route = ModelPurposeMatrix.resolve(protocol, capability);
            if (!route.adapted()) {
                return finishUnsupported(id, instance, protocol, capability, route.message());
            }
        }
        String plainKey = decrypt(credential);
        ProbeResult result = clients.require(protocol).probe(new ProbeCommand(
                provider.getBaseUrl(), plainKey, instance.getModelId(), capability));
        // 探活结束后再落库，避免失败请求把事务挂在出站 HTTP 上
        String summary = ProbeSummary.of(result.message(), plainKey);
        return finish(id, instance, protocol, capability, result, summary);
    }

    /**
     * 用途未适配：不解密、不 HTTP，仍写入最近连通，避免前端以为「没测过」。
     */
    private ConnectivityResp finishUnsupported(Long id, ModelInstance instance, ModelProtocol protocol,
                                               ModelCapability capability, String message) {
        ProbeResult result = ProbeResult.unsupported(message);
        String summary = ProbeSummary.of(result.message(), null);
        return finish(id, instance, protocol, capability, result, summary);
    }

    private ConnectivityResp finish(Long id, ModelInstance instance, ModelProtocol protocol,
                                    ModelCapability capability, ProbeResult result, String summary) {
        instances.recordProbe(id, LocalDateTime.now(), result.success() ? 1 : 0, summary);
        log.info("连通测试 instanceId={} protocol={} capability={} success={} latencyMs={}",
                id, protocol.name(), capability.name(), result.success(), result.latencyMs());
        operLogWriter.write("model", "test", "model_instance", String.valueOf(id),
                "连通测试 " + instance.getDisplayName() + (result.success() ? " 成功" : " 失败"));
        ConnectivityResp resp = new ConnectivityResp();
        resp.setSuccess(result.success());
        resp.setLatencyMs(result.latencyMs());
        resp.setMessage(summary);
        resp.setProtocol(protocol.name());
        resp.setCapability(capability.name());
        return resp;
    }

    /**
     * 主密钥变更后旧密文会解不开。提示重置 Key，不把密文放进异常。
     * context 必须与加密时一致（模型凭证绑供应商 id），否则同样解不开。
     */
    private String decrypt(ModelCredential credential) {
        try {
            return ModelCredentialResolver.decryptKey(secretStore, credential.getApiKeyCipher(),
                    credential.getProviderId());
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "凭证无法解密，请重置 Key");
        }
    }
}
