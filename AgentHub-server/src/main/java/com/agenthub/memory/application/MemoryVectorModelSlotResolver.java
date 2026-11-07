package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆向量链路的实例解析。所属域：memory。
 * <p>
 * 唯一真源是模型中心里「平台锁定」的实例（{@code model_instance.platform_locked=1}）：
 * EMBEDDING / RERANK 各最多一条，由数据库唯一索引 {@code uk_platform_slot} 兜底。
 * 不再写死 9/12，也不取列表第一条、不回落 Agent 知识库绑定的向量实例。
 * <p>
 * 短缓存：解析结果缓存 {@code agenthub.memory.platform-model-cache-ms}（默认 30 秒），
 * 由模型中心「设为平台级 / 换绑 / 连通测试快照更新」主动调用 {@link #invalidate()} 失效；
 * 未命中才查库。缓存只存实例快照对象，不做出站。
 */
@Component
public class MemoryVectorModelSlotResolver {

    /** 缓存条目：实例 + 写入时刻，仅用于 TTL 判定 */
    private final Map<ModelCapability, CacheEntry> cache = new EnumMap<>(ModelCapability.class);

    private final ModelInstanceRepository instances;
    private final MemoryProperties properties;

    /**
     * @param instances  模型实例仓储（只读）
     * @param properties 取缓存 TTL
     */
    public MemoryVectorModelSlotResolver(ModelInstanceRepository instances, MemoryProperties properties) {
        this.instances = instances;
        this.properties = properties;
    }

    /**
     * 解析可用的 Embedding 平台实例（platform_locked=1 且已启用）。用于出站装配；
     * 未配置或已停用返回 empty，不抛异常。
     *
     * @return 可用的 Embedding 实例
     */
    public Optional<ModelInstance> embedding() {
        return platformLocked(ModelCapability.EMBEDDING).filter(MemoryVectorModelSlotResolver::enabled);
    }

    /**
     * 解析可用的 Rerank 平台实例。失败不挡 isReady，只让召回跳过重排。
     *
     * @return 可用的 Rerank 实例
     */
    public Optional<ModelInstance> rerank() {
        return platformLocked(ModelCapability.RERANK).filter(MemoryVectorModelSlotResolver::enabled);
    }

    /**
     * 解析平台锁定实例本身（含已停用行），供就绪原因区分「未设置」与「设置了但停用」。
     *
     * @param capability 用途；非 EMBEDDING / RERANK 直接 empty
     * @return 平台锁定实例
     */
    public Optional<ModelInstance> platformLocked(ModelCapability capability) {
        if (capability != ModelCapability.EMBEDDING && capability != ModelCapability.RERANK) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(capability);
        if (entry != null && now - entry.atMs() < cacheTtlMs()) {
            return Optional.ofNullable(entry.instance());
        }
        synchronized (this) {
            // 双检：并发未命中只查一次库
            entry = cache.get(capability);
            if (entry != null && now - entry.atMs() < cacheTtlMs()) {
                return Optional.ofNullable(entry.instance());
            }
            ModelInstance found = instances.findPlatformLocked(capability).orElse(null);
            cache.put(capability, new CacheEntry(found, now));
            return Optional.ofNullable(found);
        }
    }

    /**
     * 主动失效：模型中心设置/取消平台级、换绑、连通测试写回维度快照后调用。
     * 同时清空 memory 就绪快照，确保下一次调用按新实例重新 ensure。
     */
    public synchronized void invalidate() {
        cache.clear();
    }

    /**
     * 缓存 TTL；配置非正数按 30 秒处理，避免被误配成无限缓存。
     */
    private long cacheTtlMs() {
        long configured = properties.getPlatformModelCacheMs();
        return configured > 0 ? configured : 30_000L;
    }

    /**
     * status 口径与全项目一致：仅 1 视为启用，null/0/其它脏数据都算停用。
     */
    private static boolean enabled(ModelInstance instance) {
        return instance.getStatus() != null && instance.getStatus() == 1;
    }

    /**
     * @param instance 缓存的实例，未设置平台级时为 null
     * @param atMs     写入时刻
     */
    private record CacheEntry(ModelInstance instance, long atMs) {
    }
}
