package com.agenthub.memory.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.memory.api.dto.MemoryVectorRebuildReq;
import com.agenthub.memory.application.MemoryVectorRebindPreviewService;
import com.agenthub.memory.application.MemoryVectorRebuildService;
import com.agenthub.memory.application.MemoryVectorRebuildState;
import com.agenthub.memory.application.MemoryVectorReconciler;
import com.agenthub.memory.application.MemoryVectorSupport;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.api.ModelPermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LONG 向量存量补同步与受控重建的运维入口。所属域：memory。
 * <p>
 * 为什么是手动接口而不是定时任务：刀5 拍板「触发二选一」选 a 方案——
 * 补同步会真出站（Embedding + Milvus），自动跑等于把出站节奏交给基础设施状态，
 * 且会与主对话抢配额。定时 / 启动全量留到后续单独一刀。
 * <p>
 * 权限：
 * <ul>
 *   <li>status / reconcile：{@link MemoryPermissions#ITEM_EDIT} 或
 *       {@link ModelPermissions#INSTANCE_PLATFORM}（OR）。status 让模型页的平台级管理员能看到维冲突提示；
 *       reconcile 放开给平台级管理员，是因为重建后的存量必须靠它一轮轮补齐，
 *       否则会出现「重建未完成但无权推进」；</li>
 *   <li>rebind-preview / rebuild 需要 {@link ModelPermissions#INSTANCE_PLATFORM}：它们服务于平台级
 *       Embedding 换维，权限与「设平台级」同级（重建权限不得低于设平台级）。危险动作靠
 *       {@code confirmRebuild} + 集合名校验把关，不再叠加需要单独授权的权限码。</li>
 * </ul>
 * <p>
 * 与可选应用内定时（{@code MemoryVectorReconcileJob}）的关系：两者共用同一个
 * {@link MemoryVectorReconciler#runOnce()}，CAS 互斥 / 未就绪整轮跳过 / 单轮上限全部复用；
 * 定时默认关闭，<b>启动时不扫全量</b>。
 * <p>
 * 单轮有硬上限（{@code agenthub.memory.vector-reconcile-batch-limit}）：返回体带上限与本轮统计，
 * 调用方据此决定是否再触发一轮。
 */
@RestController
@RequestMapping("/api/memory/vector")
public class MemoryVectorSyncController {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorSyncController.class);

    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorSupport support;
    private final MemoryVectorRebuildState rebuildState;
    private final MemoryVectorRebuildService rebuildService;
    private final MemoryVectorRebindPreviewService previewService;
    private final MemoryProperties properties;

    /**
     * @param reconciler     存量补同步编排
     * @param support        就绪快照（平台级解析 + ensure 结果 + 未就绪原因）
     * @param rebuildState   重建状态（status 暴露 rebuilding）
     * @param rebuildService 受控重建
     * @param previewService 换绑预检
     * @param properties     平台配置（status 暴露 autoDrain，前端据此决定是否还要手动按钮）
     */
    public MemoryVectorSyncController(MemoryVectorReconciler reconciler, MemoryVectorSupport support,
                                      MemoryVectorRebuildState rebuildState,
                                      MemoryVectorRebuildService rebuildService,
                                      MemoryVectorRebindPreviewService previewService,
                                      MemoryProperties properties) {
        this.reconciler = reconciler;
        this.support = support;
        this.rebuildState = rebuildState;
        this.rebuildService = rebuildService;
        this.previewService = previewService;
        this.properties = properties;
    }

    /**
     * 只读排障：当前记忆向量是否就绪，以及未就绪原因（无平台级 Embedding / 无维度快照 /
     * ensure 失败 / 维与集合不一致等）。注意：未命中缓存时会触发一次幂等 ensure。
     * <p>
     * 额外暴露 {@code collectionDimension / dimensionConflict / rebuilding}：
     * 前端据此在维冲突时给出「去重建」入口，而不是只留一行后台 WARN。
     *
     * @return 就绪快照（不含任何密钥）
     */
    @SaCheckPermission(value = {MemoryPermissions.ITEM_EDIT, ModelPermissions.INSTANCE_PLATFORM},
            mode = SaMode.OR)
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        MemoryVectorReadiness readiness = support.readiness();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", readiness.ready());
        body.put("collectionName", readiness.collectionName());
        body.put("dimension", readiness.dimension());
        body.put("collectionDimension", readiness.collectionDimension());
        body.put("dimensionConflict", readiness.dimensionConflict());
        body.put("embeddingInstanceId", readiness.embeddingInstanceId());
        body.put("rerankInstanceId", readiness.rerankInstanceId());
        body.put("rerankReady", readiness.rerankReady());
        // 补同步 / 重建进行中时禁止切换平台级 Embedding；排障页据此解释接口拒绝原因
        body.put("syncing", reconciler.isRunning());
        body.put("rebuilding", rebuildState.isRebuilding());
        // rebuildRunning：当前进程真的在执行 DROP/ensure/requeue/首轮补同步。
        // 页面刷新后本地「请求在途」标记会丢，前端靠这个字段继续显示「重建执行中」。
        body.put("rebuildRunning", rebuildState.isRunning());
        // autoDrain：重建未完成 / 平台级换绑留下待补时，服务端是否会自动续补。
        // 前端据此把「继续补同步」按钮换成自动进度，刷新 / 切换页面后仍能看到「进行中」，不需要人工点。
        body.put("autoDrain", properties.isVectorRebuildDrainEnabled());
        // draining：当前是否有续补请求（重建未完成，或换绑后有剩余存量）
        body.put("draining", properties.isVectorRebuildDrainEnabled() && rebuildState.isDrainWanted());
        // 重建开始时刻（毫秒，0=未在重建）：仅排障与前端展示已持续时间，不含任何密钥
        body.put("rebuildStartedAt", rebuildState.startedAtMs());
        // 待补条数：重建中页头提示要能显示"还差多少"，-1 表示读取失败（不要当成已补齐）
        body.put("pendingSync", reconciler.pendingCount());
        body.put("reason", readiness.reason());
        return Result.ok(body);
    }

    /**
     * 换绑预检：返回当前平台级实例、目标实例、双方维度快照、现网集合维度、同步/重建状态，
     * 以及建议动作（同维换绑 / 异维换绑后需受控重建 / 暂不可换）。
     * <p>
     * 只读，不 ensure、不 drop、不出站；前端按 {@code action} 决定弹一级还是两级确认。
     *
     * @param instanceId 目标实例 id
     * @return 预检结果
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_PLATFORM)
    @GetMapping("/rebind-preview/{instanceId}")
    public Result<MemoryVectorRebindPreviewService.Preview> rebindPreview(@PathVariable Long instanceId) {
        return Result.ok(previewService.preview(instanceId));
    }

    /**
     * 受控重建记忆向量集合：DROP 现网记忆专用集合 → 按平台级 Embedding 快照维 ensure →
     * 有效 LONG 打回 PENDING（清零失败次数）→ 有界一轮补同步。
     * <p>
     * 前置：平台级 Embedding 已设且有维度快照、非补同步中、非重建中、{@code confirmRebuild=true}、
     * 集合名与配置完全一致。重建未完成（仍 pending）期间禁止改平台级 Embedding。
     * <p>
     * 权限只要求 {@code model:instance:platform}（与设平台级同级，符合「重建权限不得低于设平台级」）；
     * 危险操作的闸门由 {@code confirmRebuild} + 集合名校验承担，不额外引入需要单独授权的权限码，
     * 避免已具备平台级管理权的管理员因漏执行菜单种子而无法排障。
     *
     * @param req 显式确认标志 + 目标集合名
     * @return 重建结果（维度、重排条数、本轮补同步统计、当前就绪状态）
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_PLATFORM)
    @PostMapping("/rebuild")
    public Result<MemoryVectorRebuildService.RebuildResult> rebuild(@RequestBody MemoryVectorRebuildReq req) {
        boolean confirmed = req != null && Boolean.TRUE.equals(req.getConfirmRebuild());
        String collectionName = req == null ? null : req.getCollectionName();
        return Result.ok(rebuildService.rebuild(collectionName, confirmed));
    }

    /**
     * 手动触发一轮补同步。同步执行，但条数有硬上限，不会拉长成启动全量。
     * <p>
     * 若处于「重建未完成」状态，本轮结束后会按待补条数判定是否收口重建态。
     * <p>
     * 权限放开给 {@code model:instance:platform}：重建后的存量必须靠本接口一轮轮补齐，
     * 若只有 ITEM_EDIT 能调，平台级管理员会卡在「重建未完成但无法推进」。
     *
     * @return 本轮统计（pendingBefore / reason / scanned / synced / failed / skipped / rebuilding）
     */
    @SaCheckPermission(value = {MemoryPermissions.ITEM_EDIT, ModelPermissions.INSTANCE_PLATFORM},
            mode = SaMode.OR)
    @PostMapping("/reconcile")
    public Result<Map<String, Object>> reconcile() {
        // 先只读估算规模：不让「有多少待补」这件事本身触发额外出站
        int pending = reconciler.pendingCount();
        MemoryVectorReconciler.Summary summary = reconciler.runOnce();
        // 重建态收口：全量 PENDING 补完才允许再次变更平台级 Embedding
        boolean drained = rebuildState.clearIfDrained(reconciler.pendingCount());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pendingBefore", pending);
        body.put("reason", summary.reason());
        body.put("scanned", summary.scanned());
        body.put("synced", summary.synced());
        body.put("failed", summary.failed());
        body.put("skipped", summary.skipped());
        body.put("rebuilding", rebuildState.isRebuilding());
        body.put("released", drained);
        log.info("[MEMORY] 手动补同步 pendingBefore={} reason={} scanned={} synced={} failed={} rebuilding={}",
                pending, summary.reason(), summary.scanned(), summary.synced(), summary.failed(),
                rebuildState.isRebuilding());
        return Result.ok(body);
    }
}
