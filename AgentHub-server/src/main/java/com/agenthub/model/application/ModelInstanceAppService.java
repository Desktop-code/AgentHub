package com.agenthub.model.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.application.MemoryPlatformModelHook;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型实例用例。所属域：model。实例必须绑定本供应商下一条启用中的凭证。
 * 唯一性定稿：<b>同供应商 + 同 Model ID + 同用途 + 未删除</b> 不可重复；
 * 展示名只要求<b>同一供应商内</b>不重复，不同供应商允许同名（如两家都提供 qwen-tts）。
 * 同一 Model ID 允许按不同用途建多个实例（如同一个 deepseek-flash 既做 CHAT 又做 REWRITE）。
 * 展示名由用户自填，不再要求等于 Model ID。
 * 温度由 {@link com.agenthub.model.domain.service.ModelTemperaturePolicy} 按用途收口：
 * 不采样用途强制 0，采样用途缺省补默认值，用户已存值保留。
 */
@Service
public class ModelInstanceAppService {

    private static final Logger log = LoggerFactory.getLogger(ModelInstanceAppService.class);

    private final ModelInstanceRepository instances;
    private final ModelLookup lookup;
    private final ModelReferencePolicy references;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final MemoryPlatformModelHook platformHook;

    /**
     * @param instances       实例
     * @param lookup          供应商、凭证、实例
     * @param references      凭证归属
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     * @param platformHook    平台级变更后失效记忆向量缓存并触发有界补同步（memory 域）
     */
    public ModelInstanceAppService(ModelInstanceRepository instances, ModelLookup lookup,
                                   ModelReferencePolicy references, OperatorContext operatorContext,
                                   OperLogWriter operLogWriter, MemoryPlatformModelHook platformHook) {
        this.instances = instances;
        this.lookup = lookup;
        this.references = references;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.platformHook = platformHook;
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
        // 平台级实例：先留原始关键字段，保存后比对；改关键参数必须走换绑/取消平台级
        PlatformSnapshot before = PlatformSnapshot.of(existing);
        fromReq(existing, req);
        assertPlatformImmutable(before, existing);
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
        // 平台级实例不可停用：一旦停用，记忆/检索链路会整体失去向量能力且无显式换绑动作
        if (isPlatformLocked(existing) && !RecordStatus.isEnabled(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台级实例不可停用，请先取消平台级或换绑其他实例");
        }
        existing.setStatus(status);
        instances.update(existing, operatorContext.requireUserId());
        String action = RecordStatus.isEnabled(status) ? "enable" : "disable";
        // 平台级由停用→启用：立即失效解析/就绪缓存，避免最长 60 秒仍按「已停用」降级向量能力
        if (isPlatformLocked(existing) && RecordStatus.isEnabled(status)) {
            ModelCapability.tryParse(existing.getCapability())
                    .ifPresent(platformHook::onSnapshotUpdated);
        }
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
        // 平台级实例不可删除：删除会让平台级 Embedding/Rerank 悄然消失，必须显式换绑或取消锁定
        if (isPlatformLocked(existing)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台级实例不可删除，请先取消平台级或换绑其他实例");
        }
        instances.logicalDelete(id, operatorContext.requireUserId());
        operLogWriter.write("model", "remove", "model_instance", String.valueOf(id), "删除模型 " + existing.getDisplayName());
    }

    /**
     * 设置 / 取消平台级锁定（EMBEDDING、RERANK 各最多一条）。
     * <p>
     * 规则（产品拍板）：
     * <ol>
     *   <li>仅 EMBEDDING / RERANK 可平台级；同一用途已有另一条时必须 {@code confirmRebind=true}，
     *       否则拒绝并返回换绑警告文案；确认后在本事务内先解锁旧实例再锁定新实例（防唯一索引冲突）；</li>
     *   <li>EMBEDDING 必须「连通测试成功 + 有正数 {@code embedding_dim} 快照」，否则不允许设为平台级；</li>
     *   <li>补同步进行中 / 受控重建未完成时，禁止设置、换绑、取消平台级 Embedding（一套互斥）；</li>
     *   <li>幂等：重复设置同一实例为平台级不报错，直接返回当前详情。</li>
     * </ol>
     * 副作用：设置或换绑成功后失效记忆向量缓存；Embedding 获得可用平台级时 ensure 集合并投递有界一轮补同步。
     * <b>本方法绝不删除集合</b>：异维换绑只会让记忆向量变为 not_ready，重建必须另走受控重建 API。
     *
     * @param id           实例 id
     * @param locked       true=设为平台级，false=取消平台级
     * @param confirmRebind 已存在的同用途平台级被替换时，是否已获用户二次确认（仅 EMBEDDING 有向量重建风险）
     * @return 更新后的详情
     * @throws BizException 用途不支持、重复平台级未确认、无维度快照、同步中切换、快照更新失败时
     */
    @Transactional
    public InstanceResp setPlatformLock(Long id, boolean locked, boolean confirmRebind) {
        ModelInstance target = lookup.requireInstance(id);
        ModelCapability capability = ModelCapability.tryParse(target.getCapability())
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "该实例用途不是 EMBEDDING / RERANK，不能设为平台级"));
        if (capability != ModelCapability.EMBEDDING && capability != ModelCapability.RERANK) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "只有 EMBEDDING / RERANK 用途的实例可以设为平台级");
        }
        boolean currentlyLocked = isPlatformLocked(target);
        // 幂等：状态未变不重复写库、不重复触发补同步
        if (currentlyLocked == locked) {
            return get(id);
        }
        // 平台级 Embedding 槽位的变更（设 / 换 / 取消）与「补同步 / 受控重建」互斥：
        // 重建期间集合可能刚被删建，此时换维会让缺口无法归因，统一在这一处拦
        if (capability == ModelCapability.EMBEDDING) {
            assertEmbeddingSlotMutable();
        }
        if (locked) {
            return lockPlatform(id, target, capability, confirmRebind);
        }
        instances.updatePlatformLock(id, 0, operatorContext.requireUserId());
        platformHook.onPlatformChanged(capability, false);
        operLogWriter.write("model", "platform-unlock", "model_instance", String.valueOf(id),
                "取消平台级 " + target.getDisplayName() + "（" + capability.name() + "）");
        return get(id);
    }

    /**
     * 设为平台级：校验 → 必要时先解锁旧实例 → 加锁 → 失效缓存并（Embedding）ensure + 有界补同步。
     * 独立成私有方法只为让 {@link #setPlatformLock} 的分支可读；事务仍在外层。
     */
    private InstanceResp lockPlatform(Long id, ModelInstance target, ModelCapability capability,
                                      boolean confirmRebind) {
        // 停用实例不允许设为平台级：平台级是全局依赖，锁一条停用实例会立刻让记忆/检索失去向量能力；
        // 而平台级实例又不允许再停用/删除，只能靠取消平台级救援。前后端同时拦，避免绕过界面直调接口
        if (!RecordStatus.isEnabled(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "实例已停用，请先启用再设为平台级");
        }
        // 产品拍板：连通测试成功后才允许设为平台级，避免把不可用实例锁成全局依赖
        if (target.getLastTestOk() == null || target.getLastTestOk() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "请先对该实例做连通测试并成功，再设为平台级");
        }
        // Embedding 是向量空间的唯一来源：无成功快照维就不允许锁定，否则 ensure 无法确定维度
        if (capability == ModelCapability.EMBEDDING
                && (target.getEmbeddingDim() == null || target.getEmbeddingDim() <= 0)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该 Embedding 实例缺少连通测试维度快照 embedding_dim，请重新测试连通");
        }
        ModelInstance other = instances.findPlatformLocked(capability).orElse(null);
        boolean rebind = other != null && !other.getId().equals(id);
        if (rebind) {
            // 已有平台级再换绑：Embedding 会重建/补同步向量，必须显式二次确认
            if (!confirmRebind) {
                throw new BizException(ErrorCode.BAD_REQUEST, rebindWarning(capability));
            }
            // 补同步 / 重建的执行互斥已在外层 assertEmbeddingSlotMutable 统一拦截，这里只处理槽位顺序
            // 先解锁旧实例：uk_platform_slot 唯一索引要求同一用途只有一个非空槽
            instances.updatePlatformLock(other.getId(), 0, operatorContext.requireUserId());
        }
        try {
            instances.updatePlatformLock(id, 1, operatorContext.requireUserId());
        } catch (DuplicateKeyException e) {
            // 并发下唯一索引兜底：另一请求已抢占同用途平台级，返回可读提示而不是 500
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该用途已有平台级实例（可能被并发设置），请刷新后重试");
        }
        // 加锁后立即失效解析缓存，让 ensure 读到新实例与维度快照；Embedding 需补同步，Rerank 不需要
        platformHook.onPlatformChanged(capability, capability == ModelCapability.EMBEDDING);
        // 换绑审计：旧/新实例 id 与维度必须可追溯；不打印任何凭证或 Key
        log.info("[MODEL] 平台级{} capability={} oldInstanceId={} newInstanceId={} oldDim={} newDim={} operatorId={}",
                rebind ? "换绑" : "设置", capability,
                rebind ? other.getId() : null, id,
                rebind ? other.getEmbeddingDim() : null, target.getEmbeddingDim(),
                operatorContext.requireUserId());
        operLogWriter.write("model", rebind ? "platform-rebind" : "platform-lock", "model_instance",
                String.valueOf(id), (rebind ? "换绑" : "设为") + "平台级 " + target.getDisplayName()
                        + "（" + capability.name() + (rebind ? "，原实例 " + other.getId() + " 已解锁" : "") + "）");
        return get(id);
    }

    /**
     * 平台级 Embedding 槽位可变更性校验：受控重建未完成或补同步进行中一律拒绝。
     * <p>
     * 文案区分两种状态：用户据此知道该等「重建」还是等「同步」，而不是同一句模糊提示。
     *
     * @throws BizException 重建中或补同步中
     */
    private void assertEmbeddingSlotMutable() {
        if (platformHook.isRebuilding()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "记忆向量重建进行中（含等待补同步完成），禁止设置 / 换绑 / 取消平台级 Embedding；请等待重建完成后再操作");
        }
        if (platformHook.isSyncRunning()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "记忆向量补同步进行中，禁止切换平台级 Embedding；请等待本轮同步结束后再操作");
        }
    }

    /**
     * 换绑二次确认文案：Embedding 明确提示可能重建 collection 与全量补同步，Rerank 只提示替换。
     *
     * @param capability 用途
     * @return 面向操作者的警告文案
     */
    private String rebindWarning(ModelCapability capability) {
        if (capability == ModelCapability.EMBEDDING) {
            return "该用途已有平台级 Embedding。换绑只切换平台级槽位：若新快照维度与现网记忆集合不一致，"
                    + "向量将不可用（召回降级 MySQL Top N），需另执行「重建记忆向量集合」；"
                    + "确认请带 confirmRebind=true 重新提交";
        }
        return "该用途已有平台级 Rerank。换绑将替换全局重排实例；"
                + "确认请带 confirmRebind=true 重新提交";
    }

    /**
     * 平台级实例的不可变字段校验：用途、供应商、凭证、模型 ID、扩展参数（含温度/维度相关）任一变化即拒绝。
     * 展示名允许改，因为它不参与出站与向量空间。
     *
     * @param before 保存前快照
     * @param after  保存后实例（已被 fromReq 覆盖）
     * @throws BizException 平台级实例被修改关键参数时
     */
    private void assertPlatformImmutable(PlatformSnapshot before, ModelInstance after) {
        if (!before.locked()) {
            return;
        }
        boolean capabilityChanged = !before.capability().equals(after.getCapability());
        boolean providerChanged = !before.providerId().equals(after.getProviderId());
        boolean credentialChanged = !before.credentialId().equals(after.getCredentialId());
        boolean modelChanged = !before.modelId().equals(after.getModelId());
        // 温度由策略统一收口（EMBEDDING / RERANK 恒为 0），不参与「关键参数」比较，否则改名也会被误判
        String beforeExtra = ModelCapability.tryParse(before.capability())
                .map(capability -> ModelTemperaturePolicy.applyTemperature(before.extraJson(), capability))
                .orElse(before.extraJson());
        boolean extraChanged = !java.util.Objects.equals(beforeExtra, after.getExtraJson());
        boolean statusChanged = !before.status().equals(after.getStatus());
        if (capabilityChanged || providerChanged || credentialChanged || modelChanged
                || extraChanged || statusChanged) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台级实例不可修改用途/供应商/凭证/模型 ID/扩展参数或停用；请先取消平台级或走换绑流程");
        }
    }

    /**
     * @param instance 实例
     * @return 是否处于平台级锁定
     */
    private static boolean isPlatformLocked(ModelInstance instance) {
        return instance.getPlatformLocked() != null && instance.getPlatformLocked() == 1;
    }

    /**
     * 平台级不可变字段快照。仅用于保存前后比对，不落库。
     *
     * @param locked       保存前是否平台级
     * @param providerId   供应商
     * @param credentialId 凭证
     * @param modelId      厂商模型 ID
     * @param capability   用途
     * @param extraJson    扩展参数
     * @param status       启用状态
     */
    private record PlatformSnapshot(boolean locked, Long providerId, Long credentialId, String modelId,
                                    String capability, String extraJson, Integer status) {

        static PlatformSnapshot of(ModelInstance instance) {
            boolean locked = instance.getPlatformLocked() != null && instance.getPlatformLocked() == 1;
            return new PlatformSnapshot(locked, instance.getProviderId(), instance.getCredentialId(),
                    instance.getModelId(), instance.getCapability(), instance.getExtraJson(),
                    instance.getStatus());
        }
    }

    /**
     * 入参 → 实例。校验顺序：供应商/凭证归属 → 用途 → 文本 → 展示名（同供应商）→ 模型用途唯一性 → 温度策略。
     *
     * @param instance 目标实例（新建为空对象，更新为已存在实例）
     * @param req      保存入参
     * @return 已填好的实例
     * @throws BizException 凭证停用、同供应商下名称重复或「模型 ID + 用途」重复时
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
        // 新建 excludeId=null；更新排除自身。展示名判重按供应商收口，跨供应商同名不算冲突
        Long excludeId = instance.getId();
        if (instances.displayNameExists(provider.getId(), displayName, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "该供应商下已存在同名模型「" + displayName + "」");
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
        resp.setPlatformLocked(instance.getPlatformLocked());
        resp.setEmbeddingDim(instance.getEmbeddingDim());
        resp.setExtraJson(instance.getExtraJson());
        resp.setLastTestTime(instance.getLastTestTime());
        resp.setLastTestOk(instance.getLastTestOk());
        resp.setLastTestMsg(instance.getLastTestMsg());
        resp.setCreateTime(instance.getCreateTime());
        resp.setUpdateTime(instance.getUpdateTime());
        return resp;
    }
}
