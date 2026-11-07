package com.agenthub.memory.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryCollectionName;
import com.agenthub.memory.domain.service.MemoryVectorStore;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 记忆向量集合的<b>受控重建</b>。所属域：memory 的 application。
 * <p>
 * 为什么必须是一个独立动作：平台级 Embedding 换成不同维度时，Milvus 不允许原地改维，
 * 唯一可行的路径是「删集合 → 按新维建集 → 存量打回 PENDING → 补同步」。这条路径会删生产数据，
 * 因此<b>绝不允许</b>挂在「设为平台级」里顺手执行：必须由操作者在危险确认弹窗里显式确认，
 * 并带上目标集合名（防误删）。
 * <p>
 * 安全边界：
 * <ul>
 *   <li>只操作 YAML 配置且落在 {@code agenthub_memory_*} 命名空间的那个集合，请求体里的集合名必须与之完全一致；</li>
 *   <li>前置条件全部满足才动数据：有平台级 Embedding、有维度快照、非补同步中、非重建执行中；</li>
 *   <li>进入重建后 {@code rebuilding} 保持到「全量 PENDING 补完」，期间平台级 Embedding 不可变更；</li>
 *   <li>DROP 成功但 ensure 失败时<b>保持重建态</b>并报错，绝不假装 ready——让运维还能从 status 看出异常。</li>
 * </ul>
 * MySQL 是唯一真源：重建只影响派生向量，失败不回滚 MySQL 业务数据。
 */
@Service
public class MemoryVectorRebuildService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorRebuildService.class);

    private final MemoryVectorSupport support;
    private final MemoryVectorModelSlotResolver slots;
    private final MemoryVectorStore store;
    private final MemoryItemRepository items;
    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorRebuildState rebuildState;
    private final MemoryProperties properties;
    private final OperLogWriter operLogWriter;
    private final OperatorContext operatorContext;

    /**
     * @param support        就绪编排（取现网维度、重建后重新自检）
     * @param slots          平台级实例解析（取目标维度与实例 id）
     * @param store          记忆向量存储端口（唯一的 drop 入口）
     * @param items          条目仓储（把有效 LONG 打回 PENDING）
     * @param reconciler     补同步（有界一轮）
     * @param rebuildState   重建状态机（与平台级变更互斥）
     * @param properties     单轮补同步上限（仅日志用）
     * @param operLogWriter  审计日志（不含密钥）
     * @param operatorContext 操作者
     */
    public MemoryVectorRebuildService(MemoryVectorSupport support, MemoryVectorModelSlotResolver slots,
                                      MemoryVectorStore store, MemoryItemRepository items,
                                      MemoryVectorReconciler reconciler, MemoryVectorRebuildState rebuildState,
                                      MemoryProperties properties,
                                      OperLogWriter operLogWriter, OperatorContext operatorContext) {
        this.support = support;
        this.slots = slots;
        this.store = store;
        this.items = items;
        this.reconciler = reconciler;
        this.rebuildState = rebuildState;
        this.properties = properties;
        this.operLogWriter = operLogWriter;
        this.operatorContext = operatorContext;
    }

    /**
     * 是否处于「重建未完成」状态（含等待补同步）。模型中心据此拒绝平台级 Embedding 变更。
     *
     * @return true 表示重建进行中
     */
    public boolean isRebuilding() {
        return rebuildState.isRebuilding();
    }

    /**
     * 执行一次受控重建。同步执行，但补同步只有<b>有界一轮</b>，剩余存量靠重复调用 reconcile 推进。
     *
     * @param collectionName  请求方声明的目标集合名，必须与当前配置一致
     * @param confirmRebuild  是否已获操作者危险确认
     * @return 重建结果（维度、重排条数、本轮补同步统计、当前就绪状态）
     * @throws BizException 未确认、集合名不符、缺少平台级/快照维、补同步或重建进行中、DROP/建集失败
     */
    public RebuildResult rebuild(String collectionName, boolean confirmRebuild) {
        // 危险动作必须先有显式确认：缺这个标志一律拒绝，避免被「普通设平台级」顺手带出来
        if (!confirmRebuild) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "重建记忆向量集合不可撤销（会删除现网记忆向量并全量重排补同步），请显式确认 confirmRebuild=true");
        }
        String configured = support.configuredCollection();
        if (!MemoryCollectionName.isManaged(configured)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "记忆 collection 未配置或不在 " + MemoryCollectionName.PREFIX + " 命名空间，拒绝重建");
        }
        // 集合名必须与配置完全一致：接口不接受「随便给个名字」，防止误删其它集合
        if (collectionName == null || !collectionName.trim().equals(configured)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "collectionName 必须是当前配置的记忆集合 " + configured + "，禁止指定任意集合名");
        }
        ModelInstance embedding = slots.platformLocked(ModelCapability.EMBEDDING)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "未设置平台级 Embedding，无法重建记忆向量集合"));
        if (embedding.getStatus() == null || embedding.getStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "平台级 Embedding 已停用，请启用后再重建");
        }
        Integer dimension = embedding.getEmbeddingDim();
        if (dimension == null || dimension <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "平台级 Embedding 缺少连通测试维度快照 embedding_dim，请先测试连通");
        }
        // 与补同步互斥：在途补写会往「即将被删的集合」里写点，互相踩踏
        if (reconciler.isRunning()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "记忆向量补同步进行中，禁止重建；请等待本轮补同步结束后重试");
        }
        // 与自身互斥：同一时刻只允许一次 DEL / ensure / requeue
        if (!rebuildState.tryBegin()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "记忆向量重建正在执行中，请等待本轮结束后重试");
        }
        Integer previousDim = null;
        boolean dropped = false;
        try {
            previousDim = support.collectionDimension();
            // 1) 删集合：store 内部再校验一次命名空间；集合不存在视为成功（幂等）
            store.dropCollection(configured);
            dropped = true;
            // 2) 按平台级 Embedding 快照维重建，字段模板固定
            store.ensureCollection(configured, dimension);
            int requeued = items.requeueValidLong(LocalDateTime.now());
            // 3) 失效并重新自检：ready 以真实 ensure 结果为准，绝不由本方法直接置真
            support.invalidate();
            MemoryVectorReadiness readiness = support.refresh();
            // 4) 有界一轮补同步；剩余靠 POST /api/memory/vector/reconcile 继续推进
            MemoryVectorReconciler.Summary summary = reconciler.runOnce();
            int pendingAfter = reconciler.pendingCount();
            rebuildState.clearIfDrained(pendingAfter);
            log.info("[MEMORY] 受控重建完成 collection={} oldDim={} newDim={} embeddingInstanceId={}"
                            + " requeued={} scanned={} synced={} failed={} pendingAfter={} ready={}",
                    configured, previousDim, dimension, embedding.getId(),
                    requeued, summary.scanned(), summary.synced(), summary.failed(), pendingAfter,
                    readiness.ready());
            operLogWriter.write("memory", "vector-rebuild", "memory_collection", configured,
                    "受控重建记忆向量集合：旧维 " + previousDim + " → 新维 " + dimension
                            + "，平台级 Embedding " + embedding.getId()
                            + "，重排 " + requeued + " 条，本轮补同步 " + summary.synced() + " 条"
                            + "，待补 " + pendingAfter + " 条，操作者 " + operatorContext.requireUserId());
            return new RebuildResult(configured, dimension, embedding.getId(), previousDim, requeued,
                    summary.scanned(), summary.synced(), summary.failed(), pendingAfter,
                    readiness.ready(), readiness.reason(), rebuildState.isRebuilding(),
                    properties.getVectorReconcileBatchLimit());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // 重建失败：就绪缓存必须失效，下一次探测读真实集合状态，绝不沿用旧的 ready=true
            support.invalidate();
            if (!dropped) {
                // DROP 没执行成功 → 现网数据未被破坏，回滚状态，避免无谓锁住平台级槽位
                rebuildState.abort();
            }
            log.warn("[MEMORY] 受控重建失败 collection={} targetDim={} dropped={} type={} msg={}",
                    configured, dimension, dropped, e.getClass().getSimpleName(), e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST,
                    dropped
                            ? "集合已删除但重建未完成（" + safeMessage(e) + "）；请在 status 查看原因并重试重建"
                            : "重建失败，现网集合未改动（" + safeMessage(e) + "）");
        } finally {
            rebuildState.endRun();
        }
    }

    /**
     * 取异常消息并截断，避免把整段堆栈塞进接口响应。
     */
    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    /**
     * 重建结果。字段全部为「已发生的事实」，不含任何密钥。
     *
     * @param collectionName        被重建的记忆集合
     * @param dimension             重建使用的平台级 Embedding 维度
     * @param embeddingInstanceId   平台级 Embedding 实例 id
     * @param previousCollectionDimension 重建前集合维度；集合不存在为 null
     * @param requeued              被打回 PENDING 的有效 LONG 条数
     * @param scanned               本轮补同步扫描条数
     * @param synced                本轮补同步成功条数
     * @param failed                本轮补同步失败条数
     * @param pendingAfter          本轮结束后仍待补的条数
     * @param ready                 重建后是否已就绪
     * @param reason                未就绪原因；就绪为空串
     * @param rebuilding            是否仍处于重建未完成状态（还有待补）
     * @param batchLimit            单轮补同步上限（提示调用方还要触发几轮）
     */
    public record RebuildResult(String collectionName, int dimension, long embeddingInstanceId,
                                Integer previousCollectionDimension, int requeued,
                                int scanned, int synced, int failed, int pendingAfter,
                                boolean ready, String reason, boolean rebuilding, int batchLimit) {
    }
}
