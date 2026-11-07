package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台级 Embedding / Rerank 换绑预检。所属域：memory 的 application。
 * <p>
 * 为什么需要预检：前端只知道「另一条实例的连通快照维」，不知道<b>现网记忆集合的真实维度</b>；
 * 而「同维换绑」与「异维换绑」的确认级别完全不同（后者必须额外走受控重建）。
 * 把判定收到服务端，前端只按 {@code action} / {@code rebuildRequired} 决定弹几级确认。
 * <p>
 * 本类<b>只读</b>：不 ensure、不 drop、不出站，任何失败都以可读文案返回 {@code allowed=false}，
 * 不用异常把「暂时不能换绑」变成 500。
 */
@Service
public class MemoryVectorRebindPreviewService {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorRebindPreviewService.class);

    private final ModelInstanceRepository instances;
    private final MemoryVectorModelSlotResolver slots;
    private final MemoryVectorSupport support;
    private final MemoryVectorReconciler reconciler;
    private final MemoryVectorRebuildState rebuildState;

    /**
     * @param instances    模型实例仓储（只读）
     * @param slots        平台级实例解析（短缓存）
     * @param support      就绪编排（取现网集合维度）
     * @param reconciler   补同步（判断是否同步中）
     * @param rebuildState 重建状态（判断是否重建中）
     */
    public MemoryVectorRebindPreviewService(ModelInstanceRepository instances,
                                            MemoryVectorModelSlotResolver slots,
                                            MemoryVectorSupport support,
                                            MemoryVectorReconciler reconciler,
                                            MemoryVectorRebuildState rebuildState) {
        this.instances = instances;
        this.slots = slots;
        this.support = support;
        this.reconciler = reconciler;
        this.rebuildState = rebuildState;
    }

    /**
     * 预检「把目标实例设为平台级」会发生什么。
     * <p>
     * 返回结构同时服务三件事：换绑确认文案（旧实例 / 新实例 / 双方维度）、是否需要二级「受控重建」、
     * 以及为什么现在不能换（同步中 / 重建中 / 无快照维 / 未测连通）。
     *
     * @param targetInstanceId 目标实例 id
     * @return 预检结果，不含任何密钥
     * @throws BizException 实例不存在时
     */
    public Preview preview(Long targetInstanceId) {
        ModelInstance target = instances.findById(targetInstanceId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "模型实例不存在或已删除"));
        ModelCapability capability = ModelCapability.tryParse(target.getCapability()).orElse(null);

        // 用途不在可锁定范围内：前端不该走到这里，但接口要能自我解释
        if (capability != ModelCapability.EMBEDDING && capability != ModelCapability.RERANK) {
            return Preview.of(target, null, support.configuredCollection(), null, false, false,
                    false, "该实例用途不是 EMBEDDING / RERANK，不能设为平台级", "NOT_LOCKABLE", List.of());
        }

        boolean rebuilding = rebuildState.isRebuilding();
        boolean syncing = reconciler.isRunning();

        // 互斥优先于其它校验：重建 / 补同步期间连「能不能换」都不该讨论，直接给出一句可读拒绝。
        // 放在解析平台级槽位之前：被拒时不必打缓存/DB，也不去读 Milvus 集合维度
        String blocked = blockedReason(capability, target, rebuilding, syncing);
        if (blocked != null) {
            return Preview.of(target, null, support.configuredCollection(), null,
                    rebuilding, syncing, false, blocked, "BLOCKED", List.of());
        }

        ModelInstance current = slots.platformLocked(capability).orElse(null);
        Integer collectionDim = capability == ModelCapability.EMBEDDING ? support.collectionDimension() : null;

        if (capability == ModelCapability.RERANK) {
            // Rerank 不参与向量空间，换绑只是替换全局重排实例，没有重建步骤
            String action = current != null ? "REBIND_RERANK" : "SET_RERANK";
            List<String> warnings = current != null
                    ? List.of("换绑将替换全局重排实例「" + current.getDisplayName() + "」，召回仍可正常进行。")
                    : List.of();
            return Preview.of(target, current, support.configuredCollection(), null,
                    rebuilding, syncing, true, "", action, warnings);
        }

        Integer targetDim = target.getEmbeddingDim();
        boolean rebuildRequired = collectionDim != null && collectionDim > 0
                && targetDim != null && targetDim > 0 && !collectionDim.equals(targetDim);
        String action = rebuildRequired
                ? "REBIND_REQUIRES_REBUILD"
                : (current != null ? "REBIND_SAME_DIM" : "SET_EMBEDDING");
        List<String> warnings = new ArrayList<>();
        if (rebuildRequired) {
            warnings.add("目标实例维度 " + targetDim + " 与现网记忆集合维度 " + collectionDim + " 不一致："
                    + "换绑后记忆向量不可用（召回降级 MySQL Top N），必须执行「重建记忆向量集合」才能恢复。");
        } else if (current != null) {
            warnings.add("换绑后旧实例「" + current.getDisplayName() + "」退出平台级，可能触发有界一轮补同步。");
        }
        if (collectionDim == null || collectionDim <= 0) {
            // 集合尚未创建：换绑后 ensure 会按新快照维直接建集，不存在维度冲突
            warnings.add("暂未读到现网记忆集合维度（可能尚未创建），按同维处理，换绑后会自动 ensure 建集。");
        }
        log.info("[MEMORY] 换绑预检 targetInstanceId={} currentInstanceId={} targetDim={} collectionDim={} action={}",
                target.getId(), current == null ? null : current.getId(), targetDim, collectionDim, action);
        return Preview.of(target, current, support.configuredCollection(), collectionDim,
                rebuilding, syncing, true, "",
                action, List.copyOf(warnings));
    }

    /**
     * 前置拒绝原因：重建中 / 补同步中 / 未连通过 / 无维度快照。
     * <p>
     * 顺序有讲究：先报「正在进行的基础设施动作」，再报实例自身条件——
     * 否则用户会先去重测连通，回来仍然被同步中拒绝。
     *
     * @param capability 目标用途
     * @param target     目标实例
     * @param rebuilding 是否重建中
     * @param syncing    是否补同步中
     * @return 拒绝文案；可换绑时返回 null
     */
    private String blockedReason(ModelCapability capability, ModelInstance target,
                                boolean rebuilding, boolean syncing) {
        if (rebuilding && capability == ModelCapability.EMBEDDING) {
            return "记忆向量重建进行中（含等待补同步完成），暂不能更换平台级 Embedding";
        }
        if (syncing && capability == ModelCapability.EMBEDDING) {
            return "记忆向量补同步进行中，暂不能切换平台级 Embedding；请等待本轮结束后重试";
        }
        if (target.getLastTestOk() == null || target.getLastTestOk() != 1) {
            return "该实例最近一次连通测试未成功，请先测试连通";
        }
        if (capability == ModelCapability.EMBEDDING
                && (target.getEmbeddingDim() == null || target.getEmbeddingDim() <= 0)) {
            return "该 Embedding 实例缺少连通测试维度快照 embedding_dim，请重新测试连通";
        }
        return null;
    }

    /**
     * 换绑预检结果。只描述事实与建议，不含任何密钥。
     *
     * @param targetInstanceId      目标实例 id
     * @param targetDisplayName     目标实例展示名
     * @param targetCapability      目标用途码
     * @param targetEmbeddingDim    目标实例维度快照（仅 EMBEDDING 有值）
     * @param currentInstanceId     现平台级实例 id；null 表示首次设置
     * @param currentDisplayName    现平台级实例展示名；null 表示首次设置
     * @param currentEmbeddingDim   现平台级实例维度快照
     * @param collectionName        记忆 collection 名
     * @param collectionDimension   现网集合真实维度；读不到为 null
     * @param syncing               是否补同步进行中
     * @param rebuilding            是否重建进行中
     * @param allowed               是否允许立刻换绑
     * @param reason                不允许时的可读原因
     * @param action                SET_EMBEDDING / REBIND_SAME_DIM / REBIND_REQUIRES_REBUILD / SET_RERANK / REBIND_RERANK / BLOCKED / NOT_LOCKABLE
     * @param rebuildRequired       是否需要换绑后执行受控重建（异维）
     * @param warnings              前端确认弹窗要展示的要点
     */
    public record Preview(Long targetInstanceId, String targetDisplayName, String targetCapability,
                          Integer targetEmbeddingDim,
                          Long currentInstanceId, String currentDisplayName, Integer currentEmbeddingDim,
                          String collectionName, Integer collectionDimension,
                          boolean syncing, boolean rebuilding, boolean allowed, String reason,
                          String action, boolean rebuildRequired, List<String> warnings) {

        /**
         * 便捷工厂：从实例对象展开成响应字段，避免调用方到处 get 与参数顺序出错。
         * 用静态工厂而不是第二个构造器，避免 Jackson 在反序列化元数据上误判 canonical 构造器。
         *
         * @param target              目标实例
         * @param current             现平台级实例；首次设置为 null
         * @param collectionName      记忆 collection 名
         * @param collectionDimension 现网集合维度
         * @param rebuilding          是否重建中
         * @param syncing             是否补同步中
         * @param allowed             是否允许立刻换绑
         * @param reason              不允许时的可读原因
         * @param action              建议动作码
         * @param warnings            确认弹窗要点
         * @return 预检结果
         */
        public static Preview of(ModelInstance target, ModelInstance current, String collectionName,
                                 Integer collectionDimension, boolean rebuilding, boolean syncing,
                                 boolean allowed, String reason, String action, List<String> warnings) {
            return new Preview(target.getId(), target.getDisplayName(), target.getCapability(),
                    target.getEmbeddingDim(),
                    current == null ? null : current.getId(),
                    current == null ? null : current.getDisplayName(),
                    current == null ? null : current.getEmbeddingDim(),
                    collectionName, collectionDimension, syncing, rebuilding, allowed, reason,
                    action, "REBIND_REQUIRES_REBUILD".equals(action), warnings);
        }
    }
}
