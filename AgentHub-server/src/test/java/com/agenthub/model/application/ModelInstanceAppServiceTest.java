package com.agenthub.model.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.application.MemoryPlatformModelHook;
import com.agenthub.model.api.dto.InstanceSaveReq;
import com.agenthub.model.domain.model.ModelCredential;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.model.ModelProvider;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.model.domain.service.ModelReferencePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型实例保存校验单测。所属域：model。
 * <p>
 * 核心口径（本次定稿）：
 * <ol>
 *   <li>同供应商 + 同 Model ID + <b>不同用途</b> 允许并存（同一 deepseek-flash 既做 CHAT 又做 REWRITE）；</li>
 *   <li>同供应商 + 同 Model ID + <b>同用途</b> 拒绝重复，且文案点出是哪个用途；</li>
 *   <li>展示名只按<b>同供应商</b>判重：跨供应商同名（如两家都叫 qwen-tts）允许，同供应商同名拒绝；</li>
 *   <li>温度按用途收口：CHAT 缺省 0.7、REWRITE 缺省 0.2、EMBEDDING 强制 0。</li>
 * </ol>
 * 约束：仓储全 Mock，零数据库、零网络。
 */
@ExtendWith(MockitoExtension.class)
class ModelInstanceAppServiceTest {

    private static final long PROVIDER_ID = 3L;
    private static final long CREDENTIAL_ID = 7L;

    @Mock
    private ModelInstanceRepository instances;
    @Mock
    private ModelLookup lookup;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;
    @Mock
    private MemoryPlatformModelHook platformHook;

    private ModelInstanceAppService service;

    @BeforeEach
    void setUp() {
        service = new ModelInstanceAppService(instances, lookup, new ModelReferencePolicy(),
                operatorContext, operLogWriter, platformHook);
        stubProviderAndCredential();
        lenient().when(operatorContext.requireUserId()).thenReturn(1L);
        lenient().when(instances.insert(any(), anyLong())).thenReturn(99L);
    }

    /**
     * 同一 Model ID 换用途可以并存：唯一性查询按「供应商 + 模型 + 用途」且返回 false 时放行。
     */
    @Test
    @DisplayName("同 Model ID 不同用途可以并存")
    void sameModelIdDifferentPurposeAllowed() {
        when(instances.displayNameExists(eq(PROVIDER_ID), eq("deepseek-flash-rewrite"), any())).thenReturn(false);
        // 用途维度不冲突（CHAT 已存在，本次是 REWRITE）
        when(instances.modelIdCapabilityExists(PROVIDER_ID, "deepseek-flash", "REWRITE", null)).thenReturn(false);

        service.create(req("deepseek-flash-rewrite", "deepseek-flash", "REWRITE", null));

        // 关键断言：仓储收到的唯一性判定参数里带 REWRITE，而不是按凭证判重
        verify(instances).modelIdCapabilityExists(PROVIDER_ID, "deepseek-flash", "REWRITE", null);
        verify(instances).insert(any(), eq(1L));
    }

    /**
     * 同 Model ID 同用途必须拒绝，且文案指出具体用途，便于用户一眼看懂冲突维度。
     */
    @Test
    @DisplayName("同 Model ID 同用途拒绝重复并指出用途")
    void sameModelIdSamePurposeRejected() {
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(PROVIDER_ID, "deepseek-flash", "CHAT", null)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(req("另一个名字", "deepseek-flash", "CHAT", null)));

        assertTrue(ex.getMessage().contains("CHAT"), "文案应指明冲突用途：" + ex.getMessage());
        verify(instances, never()).insert(any(), anyLong());
    }

    /**
     * 新建 CHAT 且未填温度：extraJson 落库后温度应为 0.7。
     */
    @Test
    @DisplayName("新建 CHAT 缺省温度落 0.7")
    void chatDefaultsToPointSeven() throws Exception {
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(any(), any(), any(), any())).thenReturn(false);

        service.create(req("chat-model", "deepseek-flash", "CHAT", null));

        assertEquals(0.7, insertedTemperature(), 1e-9);
    }

    /**
     * 新建 REWRITE 且未填温度：extraJson 落库后温度应为 0.2。
     */
    @Test
    @DisplayName("新建 REWRITE 缺省温度落 0.2")
    void rewriteDefaultsToPointTwo() throws Exception {
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(any(), any(), any(), any())).thenReturn(false);

        service.create(req("rewrite-model", "deepseek-flash", "REWRITE", null));

        assertEquals(0.2, insertedTemperature(), 1e-9);
    }

    /**
     * EMBEDDING 即使前端传了温度也强制 0，并保留同 JSON 的 dimension 扩展键。
     */
    @Test
    @DisplayName("EMBEDDING 温度强制 0 且保留 dimension")
    void embeddingTemperatureForcedToZero() throws Exception {
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(any(), any(), any(), any())).thenReturn(false);

        service.create(req("bge", "bge-m3", "EMBEDDING", "{\"temperature\":1.5,\"dimension\":1024}"));

        ModelInstance inserted = capturedInsert();
        var root = new ObjectMapper().readTree(inserted.getExtraJson());
        assertEquals(0.0, root.get("temperature").asDouble(), 1e-9);
        assertEquals(1024, root.get("dimension").asInt());
    }

    /**
     * 编辑已有实例时用户已存温度不得被静默覆盖。
     */
    @Test
    @DisplayName("编辑保留用户已存温度")
    void updateKeepsExistingTemperature() throws Exception {
        ModelInstance existing = new ModelInstance();
        existing.setId(11L);
        existing.setProviderId(PROVIDER_ID);
        existing.setCredentialId(CREDENTIAL_ID);
        existing.setStatus(1);
        when(lookup.requireInstance(11L)).thenReturn(existing);
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(any(), any(), any(), eq(11L))).thenReturn(false);

        service.update(11L, req("chat-model", "deepseek-flash", "CHAT", "{\"temperature\":0.9}"));

        ArgumentCaptor<ModelInstance> captor = ArgumentCaptor.forClass(ModelInstance.class);
        verify(instances).update(captor.capture(), eq(1L));
        var root = new ObjectMapper().readTree(captor.getValue().getExtraJson());
        assertEquals(0.9, root.get("temperature").asDouble(), 1e-9);
    }

    /**
     * 跨供应商同名必须放行：判重键带的是<b>本次所选供应商</b>，不是全局展示名。
     * <p>
     * 复现场景：A 供应商已有 qwen-tts，再到 B 供应商建同名 qwen-tts，应创建成功。
     */
    @Test
    @DisplayName("跨供应商同名可创建（判重键带本供应商）")
    void sameNameAcrossProvidersAllowed() {
        long otherProvider = 4L;
        long otherCredential = 8L;
        stubProviderAndCredential(otherProvider, otherCredential);
        when(instances.displayNameExists(otherProvider, "qwen-tts", null)).thenReturn(false);
        when(instances.modelIdCapabilityExists(otherProvider, "qwen-tts", "TTS", null)).thenReturn(false);

        service.create(req(otherProvider, otherCredential, "qwen-tts", "qwen-tts", "TTS", null));

        // 关键断言：判重只查 B 供应商，不查 A 供应商（A 下同名实例不应影响创建）
        verify(instances).displayNameExists(otherProvider, "qwen-tts", null);
        verify(instances).insert(any(), eq(1L));
    }

    /**
     * 同供应商同名仍拒绝，且文案点明「该供应商下已存在」，避免用户误以为是全局重名。
     */
    @Test
    @DisplayName("同供应商同名拒绝并点明该供应商下已存在")
    void sameNameSameProviderRejected() {
        when(instances.displayNameExists(PROVIDER_ID, "qwen-tts", null)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(req("qwen-tts", "qwen-tts", "TTS", null)));

        assertTrue(ex.getMessage().contains("该供应商下已存在"), "文案应说明是同供应商重名：" + ex.getMessage());
        verify(instances, never()).insert(any(), anyLong());
    }

    /**
     * 编辑改名或改供应商时，按「新供应商 + 新名称」判重并排除自身，不能误伤自己。
     */
    @Test
    @DisplayName("编辑按新供应商判重且排除自身")
    void updateUsesScopedKeyAndExcludesSelf() {
        long newProvider = 4L;
        long newCredential = 8L;
        ModelInstance existing = new ModelInstance();
        existing.setId(11L);
        existing.setProviderId(PROVIDER_ID);
        existing.setCredentialId(CREDENTIAL_ID);
        existing.setStatus(1);
        when(lookup.requireInstance(11L)).thenReturn(existing);
        stubProviderAndCredential(newProvider, newCredential);
        when(instances.displayNameExists(newProvider, "qwen-tts", 11L)).thenReturn(false);
        when(instances.modelIdCapabilityExists(newProvider, "qwen-tts", "TTS", 11L)).thenReturn(false);

        service.update(11L, req(newProvider, newCredential, "qwen-tts", "qwen-tts", "TTS", null));

        verify(instances).displayNameExists(newProvider, "qwen-tts", 11L);
        verify(instances).modelIdCapabilityExists(newProvider, "qwen-tts", "TTS", 11L);
    }

    // ---------- 刀10：平台级锁定 ----------

    /**
     * EMBEDDING 没有连通测试成功快照维时不能设为平台级，否则 ensure 无法确定维度。
     */
    @Test
    @DisplayName("无维度快照不允许设平台级")
    void platformLockEmbeddingRequiresDimensionSnapshot() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, null);
        when(lookup.requireInstance(11L)).thenReturn(target);

        BizException ex = assertThrows(BizException.class, () -> service.setPlatformLock(11L, true, false));

        assertTrue(ex.getMessage().contains("embedding_dim"), ex.getMessage());
        verify(instances, never()).updatePlatformLock(anyLong(), any(), anyLong());
    }

    /**
     * 停用实例不允许设为平台级：平台级是全局依赖，锁一条停用实例会立刻让记忆/检索失去向量能力。
     * 前端按钮已置灰，这里验证接口层同样拒绝（防绕过界面直调）。
     */
    @Test
    @DisplayName("停用实例不允许设平台级")
    void disabledInstanceCannotBecomePlatform() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024);
        target.setStatus(0);
        when(lookup.requireInstance(11L)).thenReturn(target);

        BizException ex = assertThrows(BizException.class, () -> service.setPlatformLock(11L, true, false));

        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
        verify(instances, never()).updatePlatformLock(anyLong(), any(), anyLong());
    }

    /**
     * 同用途已有另一条平台级且未二次确认：拒绝，并给出「可能重建 + 补同步」的换绑警告。
     */
    @Test
    @DisplayName("已有平台级未确认时拒绝换绑")
    void secondPlatformWithoutConfirmRejected() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024);
        when(lookup.requireInstance(11L)).thenReturn(target);
        when(instances.findPlatformLocked(com.agenthub.model.domain.model.ModelCapability.EMBEDDING))
                .thenReturn(Optional.of(platformTarget(5L, "EMBEDDING", 1, 768)));

        BizException ex = assertThrows(BizException.class, () -> service.setPlatformLock(11L, true, false));

        assertTrue(ex.getMessage().contains("重建记忆向量集合"), ex.getMessage());
        verify(instances, never()).updatePlatformLock(anyLong(), any(), anyLong());
    }

    /**
     * 补同步进行中禁止切换平台级 Embedding（含取消），避免换维与在途补写互相踩踏。
     */
    @Test
    @DisplayName("补同步进行中禁止切换平台级 Embedding")
    void rebindWhileSyncRunningRejected() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024);
        when(lookup.requireInstance(11L)).thenReturn(target);
        when(platformHook.isSyncRunning()).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.setPlatformLock(11L, true, true));

        assertTrue(ex.getMessage().contains("同步进行中"), ex.getMessage());
        verify(instances, never()).updatePlatformLock(anyLong(), any(), anyLong());
    }

    /**
     * 受控重建未完成（仍有待补）时禁止设置/换绑/取消平台级 Embedding：重建与换维互斥。
     */
    @Test
    @DisplayName("重建进行中禁止变更平台级 Embedding")
    void rebindWhileRebuildingRejected() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024);
        when(lookup.requireInstance(11L)).thenReturn(target);
        when(platformHook.isRebuilding()).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.setPlatformLock(11L, true, false));

        assertTrue(ex.getMessage().contains("重建进行中"), ex.getMessage());
        verify(instances, never()).updatePlatformLock(anyLong(), any(), anyLong());
    }

    /**
     * 首次设置平台级 Embedding：加锁后必须通知 memory 域失效缓存、ensure 并触发有界补同步。
     */
    @Test
    @DisplayName("首次设平台级 Embedding 触发 ensure 与补同步")
    void firstPlatformLockTriggersMemoryHook() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024);
        when(lookup.requireInstance(11L)).thenReturn(target, target);
        when(instances.findPlatformLocked(com.agenthub.model.domain.model.ModelCapability.EMBEDDING))
                .thenReturn(Optional.empty());

        service.setPlatformLock(11L, true, false);

        verify(instances).updatePlatformLock(11L, 1, 1L);
        verify(platformHook).onPlatformChanged(com.agenthub.model.domain.model.ModelCapability.EMBEDDING, true);
    }

    /**
     * 平台级实例不可删除、不可停用：必须显式取消锁定或换绑。
     */
    @Test
    @DisplayName("平台级实例不可删除/停用")
    void platformLockedInstanceCannotBeDeletedOrDisabled() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024, 1);
        when(lookup.requireInstance(11L)).thenReturn(target);

        BizException deleteEx = assertThrows(BizException.class, () -> service.delete(11L));
        assertTrue(deleteEx.getMessage().contains("不可删除"), deleteEx.getMessage());
        verify(instances, never()).logicalDelete(anyLong(), anyLong());

        com.agenthub.model.api.dto.InstanceStatusReq disable = new com.agenthub.model.api.dto.InstanceStatusReq();
        disable.setStatus(0);
        BizException disableEx = assertThrows(BizException.class, () -> service.changeStatus(11L, disable));
        assertTrue(disableEx.getMessage().contains("不可停用"), disableEx.getMessage());
        verify(instances, never()).update(any(), anyLong());
    }

    /**
     * 平台级由停用→启用：必须立即失效记忆解析/就绪缓存，避免继续按「已停用」降级。
     */
    @Test
    @DisplayName("平台级实例重新启用时失效记忆缓存")
    void enablingPlatformLockedInvalidatesMemoryCache() {
        ModelInstance target = platformTarget(11L, "EMBEDDING", 1, 1024, 1);
        target.setStatus(0);
        when(lookup.requireInstance(11L)).thenReturn(target, target);

        com.agenthub.model.api.dto.InstanceStatusReq enable = new com.agenthub.model.api.dto.InstanceStatusReq();
        enable.setStatus(1);
        service.changeStatus(11L, enable);

        verify(instances).update(target, 1L);
        verify(platformHook).onSnapshotUpdated(com.agenthub.model.domain.model.ModelCapability.EMBEDDING);
    }

    /**
     * 编辑平台级实例的模型 ID / 扩展参数属于改向量空间，必须拒绝。
     */
    @Test
    @DisplayName("平台级实例不可改关键参数")
    void platformLockedInstanceCannotChangeCriticalFields() {
        ModelInstance existing = platformTarget(11L, "EMBEDDING", 1, 1024, 1);
        existing.setModelId("bge-old");
        when(lookup.requireInstance(11L)).thenReturn(existing);
        when(instances.displayNameExists(eq(PROVIDER_ID), any(), any())).thenReturn(false);
        when(instances.modelIdCapabilityExists(any(), any(), any(), any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(11L, req("bge", "bge-new", "EMBEDDING", "{\"dimension\":2048}")));

        assertTrue(ex.getMessage().contains("平台级实例不可修改"), ex.getMessage());
        verify(instances, never()).update(any(), anyLong());
    }

    /**
     * 构造平台级候选实例。
     */
    private ModelInstance platformTarget(Long id, String capability, int testOk, Integer dim) {
        return platformTarget(id, capability, testOk, dim, 0);
    }

    /**
     * 构造平台级候选实例。
     *
     * @param locked 1=保存前已是平台级，用于校验不可删除/不可改关键参数
     */
    private ModelInstance platformTarget(Long id, String capability, int testOk, Integer dim, int locked) {
        ModelInstance instance = new ModelInstance();
        instance.setPlatformLocked(locked);
        instance.setId(id);
        instance.setProviderId(PROVIDER_ID);
        instance.setCredentialId(CREDENTIAL_ID);
        instance.setDisplayName("bge-" + id);
        instance.setModelId("bge-m3");
        instance.setCapability(capability);
        instance.setStatus(1);
        instance.setLastTestOk(testOk);
        instance.setEmbeddingDim(dim);
        return instance;
    }

    // ---------- 辅助 ----------

    /**
     * 构造一份必填齐全的保存入参。
     *
     * @param displayName 展示名
     * @param modelId     厂商模型 ID
     * @param capability  用途码
     * @param extraJson   扩展 JSON，可空
     * @return 入参
     */
    private InstanceSaveReq req(String displayName, String modelId, String capability, String extraJson) {
        return req(PROVIDER_ID, CREDENTIAL_ID, displayName, modelId, capability, extraJson);
    }

    /**
     * 构造指定供应商 / 凭证的保存入参，供跨供应商判重用例使用。
     *
     * @param providerId   供应商 id
     * @param credentialId 凭证 id
     * @param displayName  展示名
     * @param modelId      厂商模型 ID
     * @param capability   用途码
     * @param extraJson    扩展 JSON，可空
     * @return 入参
     */
    private InstanceSaveReq req(long providerId, long credentialId, String displayName,
                                String modelId, String capability, String extraJson) {
        InstanceSaveReq req = new InstanceSaveReq();
        req.setProviderId(providerId);
        req.setCredentialId(credentialId);
        req.setDisplayName(displayName);
        req.setModelId(modelId);
        req.setCapability(capability);
        req.setExtraJson(extraJson);
        return req;
    }

    /**
     * 供应商与凭证都可用（启用中，凭证归属正确）。
     */
    private void stubProviderAndCredential() {
        stubProviderAndCredential(PROVIDER_ID, CREDENTIAL_ID);
    }

    /**
     * 按指定 id 装配供应商与凭证，供跨供应商用例复用。
     *
     * @param providerId   供应商 id
     * @param credentialId 凭证 id
     */
    private void stubProviderAndCredential(long providerId, long credentialId) {
        ModelProvider provider = new ModelProvider();
        provider.setId(providerId);
        provider.setName("测试供应商-" + providerId);
        provider.setStatus(1);
        lenient().when(lookup.requireProvider(providerId)).thenReturn(provider);

        ModelCredential credential = new ModelCredential();
        credential.setId(credentialId);
        credential.setProviderId(providerId);
        credential.setName("主 Key");
        credential.setApiKeyMask("sk-****1234");
        credential.setStatus(1);
        lenient().when(lookup.requireCredential(credentialId)).thenReturn(credential);

        // create / update 末尾经 lookup.requireInstance 回读详情，给一份能拼出 resp 的最小实例
        ModelInstance saved = new ModelInstance();
        saved.setId(99L);
        saved.setProviderId(providerId);
        saved.setCredentialId(credentialId);
        saved.setStatus(1);
        lenient().when(lookup.requireInstance(99L)).thenReturn(saved);
    }

    /**
     * @return 本次 insert 收到的实例
     */
    private ModelInstance capturedInsert() {
        ArgumentCaptor<ModelInstance> captor = ArgumentCaptor.forClass(ModelInstance.class);
        verify(instances).insert(captor.capture(), eq(1L));
        return captor.getValue();
    }

    /**
     * @return 本次 insert 实例的 extraJson.temperature
     */
    private double insertedTemperature() throws Exception {
        return new ObjectMapper().readTree(capturedInsert().getExtraJson()).get("temperature").asDouble();
    }
}
