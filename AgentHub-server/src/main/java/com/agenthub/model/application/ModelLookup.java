package com.agenthub.model.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelCredentialRepository;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.repository.ModelProviderRepository;
import org.springframework.stereotype.Component;

/**
 * 按 id 取未删除的模型资料。所属域：model。三个应用服务共用，避免各写一套「不存在」。
 */
@Component
public class ModelLookup {

    private final ModelProviderRepository providers;
    private final ModelCredentialRepository credentials;
    private final ModelInstanceRepository instances;

    /**
     * @param providers   供应商
     * @param credentials 凭证
     * @param instances   实例
     */
    public ModelLookup(ModelProviderRepository providers, ModelCredentialRepository credentials, ModelInstanceRepository instances) {
        this.providers = providers;
        this.credentials = credentials;
        this.instances = instances;
    }

    /**
     * @param id 供应商 id
     * @return 未删除的供应商
     * @throws BizException 不存在时 NOT_FOUND
     */
    public ModelProvider requireProvider(Long id) {
        return providers.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "供应商不存在"));
    }

    /**
     * @param id 凭证 id
     * @return 未删除的凭证
     * @throws BizException 不存在时 NOT_FOUND
     */
    public ModelCredential requireCredential(Long id) {
        return credentials.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "凭证不存在"));
    }

    /**
     * @param id 实例 id
     * @return 未删除的实例
     * @throws BizException 不存在时 NOT_FOUND
     */
    public ModelInstance requireInstance(Long id) {
        return instances.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型实例不存在"));
    }
}
