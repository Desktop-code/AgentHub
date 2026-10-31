package com.agenthub.model.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.api.dto.InstancePageReq;
import com.agenthub.model.api.dto.InstanceResp;
import com.agenthub.model.api.dto.InstanceSaveReq;
import com.agenthub.model.api.dto.InstanceStatusReq;
import com.agenthub.model.domain.model.InstanceQuery;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.agenthub.model.domain.service.ModelTemperaturePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型实例用例。所属域：model。实例必须绑定本供应商下一条启用中的凭证。
 * 唯一性定稿：<b>同供应商 + 同 Model ID + 同用途 + 未删除</b> 不可重复；
 * 同一 Model ID 允许按不同用途建多个实例（如同一个 deepseek-flash 既做 CHAT 又做 REWRITE）。
 * 展示名由用户自填，不再要求等于 Model ID。
 * 温度由 {@link com.agenthub.model.domain.service.ModelTemperaturePolicy} 按用途收口：
 * 不采样用途强制 0，采样用途缺省补默认值，用户已存值保留。
 */
@Service
public class ModelInstanceAppService {

    private final ModelInstanceRepository instances;
    private final ModelLookup lookup;
    private final ModelReferencePolicy references;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param instances       实例
     * @param lookup          供应商、凭证、实例
     * @param references      凭证归属
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public ModelInstanceAppService(ModelInstanceRepository instances, ModelLookup lookup,
                                   ModelReferencePolicy references, OperatorContext operatorContext,
                                   OperLogWriter operLogWriter) {
        this.instances = instances;
        this.lookup = lookup;
        this.references = references;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页和筛选
     * @return 带供应商名、凭证名和掩码的一页数据
     */
    public PageResult<InstanceResp> page(InstancePageReq req) {
        InstanceQuery query = new InstanceQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setProviderId(req.getProviderId());
        // 筛选不抛「不支持的用途」：未知码给空页；已知码兼查洗库前旧码
        if (req.getCapability() != null && !req.getCapability().isBlank()) {
            var parsed = ModelCapability.tryParse(req.getCapability());
            if (parsed.isEmpty()) {
                return PageResult.of(0, req.getCurrent(), req.getSize(), List.of());
            }
            query.setCapabilities(ModelCapability.storageCodes(parsed.get()));
        }
        query.setKeyword(req.getKeyword());
        PageResult<ModelInstance> page = instances.page(query);
        List<InstanceResp> records = new ArrayList<>();
        for (ModelInstance instance : page.getRecords()) {
            records.add(toResp(instance));
        }
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * @param id 实例 id
     * @return 详情
     */
    public InstanceResp get(Long id) {
        return toResp(lookup.requireInstance(id));
    }

    /**
     * @param req 供应商、凭证和模型 ID
     * @return 新建后的详情
     */
    @Transactional
    public InstanceResp create(InstanceSaveReq req) {
        ModelInstance instance = fromReq(new ModelInstance(), req);
        Long id = instances.insert(instance, operatorContext.requireUserId());
        operLogWriter.write("model", "create", "model_instance", String.valueOf(id), "创建模型 " + instance.getDisplayName());
        return get(id);
    }

    /**
     * @param id  实例 id
     * @param req 新资料
     * @return 更新后的详情
     */
    @Transactional
    public InstanceResp update(Long id, InstanceSaveReq req) {
        ModelInstance existing = lookup.requireInstance(id);
        int previous = existing.getStatus() == null ? RecordStatus.ENABLED.getCode() : existing.getStatus();
        fromReq(existing, req);
        String action = previous == existing.getStatus() ? "update" : (RecordStatus.isEnabled(existing.getStatus()) ? "enable" : "disable");
        instances.update(existing, operatorContext.requireUserId());
        operLogWriter.write("model", action, "model_instance", String.valueOf(id), "更新模型 " + existing.getDisplayName());
        return get(id);
    }

    /**
     * @param id  实例 id
     * @param req 目标状态
     * @return 更新后的详情
     */
    @Transactional
    public InstanceResp changeStatus(Long id, InstanceStatusReq req) {
        ModelInstance existing = lookup.requireInstance(id);
        int status = ModelChecks.status(req.getStatus());
        existing.setStatus(status);
        instances.update(existing, operatorContext.requireUserId());
        String action = RecordStatus.isEnabled(status) ? "enable" : "disable";
        operLogWriter.write("model", action, "model_instance", String.valueOf(id), "更新模型状态 " + existing.getDisplayName());
        return get(id);
    }

    /**
     * 逻辑删除实例（deleted=1）。不级联清理 billing 账本/日汇总，历史用量保留。
     *
     * @param id 实例 id
     */
    @Transactional
    public void delete(Long id) {
        ModelInstance existing = lookup.requireInstance(id);
        instances.logicalDelete(id, operatorContext.requireUserId());
        operLogWriter.write("model", "remove", "model_instance", String.valueOf(id), "删除模型 " + existing.getDisplayName());
    }

    /**
     * 入参 → 实例。校验顺序：供应商/凭证归属 → 用途 → 文本 → 展示名 → 模型用途唯一性 → 温度策略。
     *
     * @param instance 目标实例（新建为空对象，更新为已存在实例）
     * @param req      保存入参
     * @return 已填好的实例
     * @throws BizException 凭证停用、名称或「模型 ID + 用途」重复时
     */
    private ModelInstance fromReq(ModelInstance instance, InstanceSaveReq req) {
        ModelProvider provider = lookup.requireProvider(req.getProviderId());
        ModelCredential credential = lookup.requireCredential(req.getCredentialId());
        references.assertCredentialBelongs(credential, provider.getId());
        if (!RecordStatus.isEnabled(credential.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择启用中的凭证");
        }
        ModelCapability capability = ModelChecks.capability(req.getCapability());
        String displayName = ModelChecks.requiredText(req.getDisplayName(), 64, "名称");
        String modelId = ModelChecks.requiredText(req.getModelId(), 128, "模型 ID");
        // 新建 excludeId=null；更新排除自身
        Long excludeId = instance.getId();
        if (instances.displayNameExists(displayName, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "模型名称已存在");
        }
        // 唯一键是「供应商 + 模型 ID + 用途」：同模型换个用途（CHAT→REWRITE）可以并存
        if (instances.modelIdCapabilityExists(provider.getId(), modelId, capability.name(), excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, duplicateMessage(capability));
        }
        instance.setProviderId(provider.getId());
        instance.setCredentialId(credential.getId());
        instance.setDisplayName(displayName);
        instance.setModelId(modelId);
        instance.setCapability(capability.name());
        instance.setStatus(ModelChecks.status(req.getStatus()));
        // 温度按用途收口：不采样用途锁 0；采样用途缺省补默认值、已存值保留（编辑不静默覆盖用户选择）
        String checkedExtra = ModelChecks.extraJson(req.getExtraJson());
        instance.setExtraJson(ModelTemperaturePolicy.applyTemperature(checkedExtra, capability));
        return instance;
    }

    /**
     * 重复文案要指出「哪个用途已占用」，否则用户看不出是用途维度冲突。
     *
     * @param capability 本次提交的用途
     * @return 面向操作者的中文提示
     */
    private String duplicateMessage(ModelCapability capability) {
        return "同一供应商下该模型 ID 已存在「" + capability.name() + "」用途的实例，请换用途或编辑已有实例";
    }

    private InstanceResp toResp(ModelInstance instance) {
        ModelProvider provider = lookup.requireProvider(instance.getProviderId());
        ModelCredential credential = lookup.requireCredential(instance.getCredentialId());
        InstanceResp resp = new InstanceResp();
        resp.setId(instance.getId());
        resp.setProviderId(instance.getProviderId());
        resp.setProviderName(provider.getName());
        resp.setCredentialId(instance.getCredentialId());
        resp.setCredentialName(credential.getName());
        resp.setApiKeyMask(credential.getApiKeyMask());
        resp.setDisplayName(instance.getDisplayName());
        resp.setModelId(instance.getModelId());
        resp.setCapability(instance.getCapability());
        resp.setStatus(instance.getStatus());
        resp.setExtraJson(instance.getExtraJson());
        resp.setLastTestTime(instance.getLastTestTime());
        resp.setLastTestOk(instance.getLastTestOk());
        resp.setLastTestMsg(instance.getLastTestMsg());
        resp.setCreateTime(instance.getCreateTime());
        resp.setUpdateTime(instance.getUpdateTime());
        return resp;
    }
}
