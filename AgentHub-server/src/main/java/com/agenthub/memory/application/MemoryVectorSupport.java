package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.service.MemoryCollectionName;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆向量库就绪编排。所属域：memory。
 * <p>
 * 真源：模型中心里「平台锁定」的 Embedding 实例（{@code platform_locked=1}）
 * + 该实例最近一次连通测试成功快照的维度 {@code embedding_dim}。
 * 不再读 YAML 维度、不再写死实例 9/12。
 * <p>
 * 就绪口径（全部满足）：
 * <ol>
 *   <li>collection 名落在 {@code agenthub_memory_} 命名空间；</li>
 *   <li>存在平台级 Embedding 且已启用；</li>
 *   <li>该实例有 &gt;0 的维度快照；</li>
 *   <li>{@code ensureCollection} 成功（含与既有集合维度一致）。</li>
 * </ol>
 * 无平台级 Embedding 时<b>不 ensure</b>、不建集，只返回 ready=false；LONG 的 MySQL 主路径照常。
 * Rerank 平台级缺失只让 {@code rerankInstanceId=null}，不影响 ready。
 * <p>
 * 降级与重试：
 * <ul>
 *   <li>任何失败只落日志并返回 ready=false，绝不抛出到启动链路；</li>
 *   <li>就绪结果缓存；未就绪时按 {@link #NOT_READY_RETRY_MS} 节流重试，
 *       避免 Milvus 未启动时每次调用都触发建连超时；</li>
 *   <li>设置/换绑平台级、连通测试写回维度快照后由 {@link MemoryPlatformModelHook} 主动失效缓存。</li>
 * </ul>
 * 维度冲突（现网集合维度 ≠ 快照维度）单独作为可观测状态返回：{@code collectionDimension} + 可读 reason，
 * 控制台据此引导受控重建；本类<b>绝不</b>自行 drop 集合。
 */
@Component
public class MemoryVectorSupport {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorSupport.class);

    /** 未就绪快照的最短重试间隔：Milvus 晚启动或集合后建时，60 秒后允许再探测一次 */
    private static final long NOT_READY_RETRY_MS = 60_000L;

    private final MemoryProperties properties;
    private final MemoryVectorModelSlotResolver slots;
    private final MemoryVectorStore store;

    /** 最近一次自检快照；null 表示从未执行或刚被主动失效 */
    private volatile MemoryVectorReadiness snapshot;
    /** 快照生成时刻（毫秒），仅用于未就绪时的重试节流 */
    private volatile long snapshotAtMs;
    /** 失效代数：探测期间若被 invalidate，本次结果不回写，避免把旧实例快照永久缓存 */
    private final AtomicLong generation = new AtomicLong();

    /**
     * @param properties YAML：collection 名（维度仅作排障对照，不参与建集）
     * @param slots      平台级 Embedding / Rerank 解析（带短缓存）
     * @param store      记忆向量存储端口（确保集合存在）
     */
    public MemoryVectorSupport(MemoryProperties properties, MemoryVectorModelSlotResolver slots,
                               MemoryVectorStore store) {
        this.properties = properties;
        this.slots = slots;
        this.store = store;
    }

    /**
     * 取当前就绪快照；首次调用、未就绪超过重试间隔或缓存被失效时重新探测。
     *
     * @return 就绪快照
     */
    public MemoryVectorReadiness readiness() {
        MemoryVectorReadiness current = snapshot;
        if (fresh(current)) {
            return current;
        }
        synchronized (this) {
            // 双检：并发调用只让第一个线程真正执行 ensure
            current = snapshot;
            if (fresh(current)) {
                return current;
            }
            return probeAndCache();
        }
    }

    /**
     * 强制重新自检（启动自检、设置平台级、运维排障用）。ensure 幂等，可安全重复调用。
     *
     * @return 新的就绪快照
     */
    public MemoryVectorReadiness refresh() {
        synchronized (this) {
            return probeAndCache();
        }
    }

    /**
     * 主动失效就绪快照：平台级实例被设置/取消/换绑、或维度快照更新后调用。
     * 只清快照，不立即出站；下一次 {@link #readiness()} 会重新解析并按需 ensure。
     */
    public void invalidate() {
        // 不抢探测锁：模型中心请求线程只做一次原子失效，避免被 Milvus 慢探测拖住
        generation.incrementAndGet();
        snapshot = null;
        snapshotAtMs = 0L;
    }

    /**
     * 是否可用于 LONG 向量双写/召回。入口处用它做短路。
     *
     * @return true 表示集合、维度快照与平台级 Embedding 均可用
     */
    public boolean isReady() {
        return readiness().ready();
    }

    /**
     * 现网记忆集合的真实维度；集合不存在或 Milvus 读不到为 null。
     * 供换绑预检与受控重建判断「同维 / 异维」，不触发 ensure、不出站写。
     *
     * @return 维度；无法判断为 null
     */
    public Integer collectionDimension() {
        return existingDimension(configuredCollection());
    }

    /**
     * YAML 配置的记忆 collection 名（已 trim）；未配置为空串。
     *
     * @return collection 名
     */
    public String configuredCollection() {
        String configured = properties.getMilvusCollection();
        return configured == null ? "" : configured.trim();
    }

    /**
     * 快照是否可直接复用：就绪结果长期有效；未就绪结果只在重试间隔内有效。
     */
    private boolean fresh(MemoryVectorReadiness current) {
        if (current == null) {
            return false;
        }
        if (current.ready()) {
            return true;
        }
        return System.currentTimeMillis() - snapshotAtMs < NOT_READY_RETRY_MS;
    }

    /**
     * 执行一次完整探测并写回快照。
     */
    private MemoryVectorReadiness probeAndCache() {
        long generationAtStart = generation.get();
        MemoryVectorReadiness probed = probe();
        // 探测期间发生过 invalidate：本次结果可能已过期，不回写；下一次 readiness() 会重新探测
        if (generation.get() == generationAtStart) {
            snapshot = probed;
            snapshotAtMs = System.currentTimeMillis();
        }
        return probed;
    }

    /**
     * 探测顺序：collection 名 → 平台级 Embedding（含启用） → 维度快照 → ensure → 平台级 Rerank（可选）。
     * 任一步失败立即返回，不调 ensure、不做任何模型出站。
     */
    private MemoryVectorReadiness probe() {
        String collection = configuredCollection();

        // 集合名必须落在记忆命名空间：防止误用 RAG KB 集合或其它业务集合
        if (!MemoryCollectionName.isManaged(collection)) {
            log.warn("[MEMORY] 记忆向量未就绪：milvus-collection 非法或未配置 value={}", collection);
            return MemoryVectorReadiness.notReady(collection, 0,
                    "agenthub.memory.milvus-collection 未配置或不在 " + MemoryCollectionName.PREFIX + " 命名空间");
        }

        // 平台级 Embedding 是唯一真源；未设置/已停用都不 ensure（无平台级则记忆向量整体跳过）
        Optional<ModelInstance> locked = slots.platformLocked(ModelCapability.EMBEDDING);
        if (locked.isEmpty()) {
            log.warn("[MEMORY] 记忆向量未就绪：未设置平台级 Embedding（模型中心「设为平台级」）");
            return MemoryVectorReadiness.notReady(collection, 0,
                    "未设置平台级 Embedding（请在模型中心对 EMBEDDING 实例设为平台级）");
        }
        ModelInstance embedding = locked.get();
        if (embedding.getStatus() == null || embedding.getStatus() != 1) {
            log.warn("[MEMORY] 记忆向量未就绪：平台级 Embedding 已停用 instanceId={}", embedding.getId());
            return MemoryVectorReadiness.notReady(collection, 0,
                    "平台级 Embedding 已停用，请启用或换绑");
        }

        // 维度只认「最近一次连通测试成功快照」；缺失/非法直接不 ready，禁止回落到 YAML 默认值
        Integer snapshotDim = embedding.getEmbeddingDim();
        if (snapshotDim == null || snapshotDim <= 0) {
            log.warn("[MEMORY] 记忆向量未就绪：平台级 Embedding 缺少维度快照 instanceId={}", embedding.getId());
            return MemoryVectorReadiness.notReady(collection, 0,
                    "平台级 Embedding 缺少最近一次连通测试成功快照 embedding_dim，请先测连通");
        }
        int dimension = snapshotDim;

        // 先读现网集合维度：冲突要作为独立状态暴露给控制台（引导受控重建），而不是只留一行 WARN
        Integer existingDim = existingDimension(collection);
        if (existingDim != null && existingDim > 0 && existingDim != dimension) {
            log.warn("[MEMORY] 记忆向量未就绪：集合维度与快照不一致 collection={} collectionDim={} snapshotDim={}",
                    collection, existingDim, dimension);
            return MemoryVectorReadiness.notReady(collection, dimension,
                    "记忆集合维度 " + existingDim + " 与平台级 Embedding 维度快照 " + dimension
                            + " 不一致，请在记忆向量状态执行「受控重建」", existingDim);
        }

        try {
            // 幂等 ensure：已存在则校验维度/字段一致，不一致由 store 抛错（绝不静默改维）
            store.ensureCollection(collection, dimension);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            String reason = message.contains("不一致")
                    ? "记忆集合维度与平台级 Embedding 快照不一致，需执行受控重建后补同步"
                    : "Milvus 不可用或集合初始化失败";
            // 降级：Milvus 未启动/建集失败只影响向量能力，MySQL 记忆主路径不受影响
            log.warn("[MEMORY] 记忆向量集合初始化失败 collection={} dim={} reason={}",
                    collection, dimension, message);
            return MemoryVectorReadiness.notReady(collection, dimension, reason, existingDim);
        }

        // 平台级 Rerank 只决定能否重排：解析不到不影响 isReady
        Long rerankId = slots.rerank().map(ModelInstance::getId).orElse(null);
        if (rerankId == null) {
            log.debug("[MEMORY] 未配置可用的平台级 Rerank：isReady 不受影响，召回将跳过重排");
        }
        return MemoryVectorReadiness.ready(collection, dimension, embedding.getId(), rerankId);
    }

    /**
     * 读现网集合维度；Milvus 不可用或读不到时返回 null，只记 debug。
     * <p>
     * 单独包一层是因为「读维度失败」与「维度冲突」是两件事：前者不该把原因写成冲突，
     * 后者必须让控制台看到可执行的下一步（受控重建）。
     */
    private Integer existingDimension(String collection) {
        try {
            return store.embeddingDimension(collection);
        } catch (RuntimeException e) {
            log.debug("[MEMORY] 读取记忆集合维度失败，按未知处理 collection={} msg={}",
                    collection, e.getMessage());
            return null;
        }
    }
}
