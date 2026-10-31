package com.agenthub.model.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.model.domain.model.InstanceQuery;
import com.agenthub.model.domain.model.ModelInstance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 模型实例仓储。所属域：model。
 */
public interface ModelInstanceRepository {

    /**
     * @param query 供应商、用途和关键词可空
     * @return 一页实例
     */
    PageResult<ModelInstance> page(InstanceQuery query);

    /**
     * @param id 实例 id
     * @return 未删除的实例
     */
    Optional<ModelInstance> findById(Long id);

    /**
     * 按主键解析实例，含已逻辑删除行。
     * 仅供用量展示等历史引用拼名；业务校验与下拉仍用 {@link #findById}。
     *
     * @param id 实例 id
     * @return 存在则带回，deleted=1 表示已软删
     */
    Optional<ModelInstance> findByIdIncludingDeleted(Long id);

    /**
     * 平台默认某用途。只返回已启用实例，按 id 升序，调用方取第一条。
     *
     * @param capability 用途枚举名，如 SUMMARY
     * @return 未删除且已启用的实例
     */
    List<ModelInstance> listEnabledByCapability(String capability);

    /**
     * @param providerId 供应商 id
     * @return 未删除实例数，用于删除供应商前的引用检查
     */
    int countByProviderId(Long providerId);

    /**
     * @param credentialId 凭证 id
     * @return 未删除实例数，用于删除凭证前的引用检查
     */
    int countByCredentialId(Long credentialId);

    /**
     * 展示名是否已被未删除实例占用（全局）。
     *
     * @param displayName 展示名
     * @param excludeId   更新时排除自身；新建传 null
     * @return true 表示冲突
     */
    boolean displayNameExists(String displayName, Long excludeId);

    /**
     * 「同供应商 + 同 modelId + 同用途」是否已有未删除实例。
     * <p>
     * 为什么按供应商而非凭证判定：同一 deepseek 供应商下可能挂多条凭证，
     * 但它们的模型能力相同，按凭证判定会放过「同供应商同模型同用途」的重复行。
     * 用途不同（如 CHAT 与 REWRITE）允许并存；软删行不计入，删了可以再建。
     *
     * @param providerId 供应商 id
     * @param modelId    厂商模型 ID
     * @param capability 用途规范码（大写枚举名）
     * @param excludeId  更新时排除自身；新建传 null
     * @return true 表示冲突
     */
    boolean modelIdCapabilityExists(Long providerId, String modelId, String capability, Long excludeId);

    /**
     * @param instance   新实例
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(ModelInstance instance, Long operatorId);

    /**
     * @param instance   已存在的实例
     * @param operatorId 更新人
     */
    void update(ModelInstance instance, Long operatorId);

    /**
     * 记下一次连通测试。成功和失败都写，不改实例配置。
     *
     * @param id       实例 id
     * @param testedTime 测试时间
     * @param ok       1 成功，0 失败
     * @param message  已去掉 Key 的摘要，最长 255
     */
    void recordProbe(Long id, LocalDateTime testedTime, int ok, String message);

    /**
     * @param id         实例 id
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}
