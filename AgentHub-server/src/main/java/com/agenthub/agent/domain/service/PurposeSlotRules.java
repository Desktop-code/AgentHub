package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用途槽规则。所属域：agent。
 * 仍用 agent_model_bind；生成/多模态/音频/安全槽最多 {@link #MAX_PER_SLOT} 条，CHAT 至少 1；
 * ROUTER 槽仅 ROUTER 模式可配；RERANK 单槽（{@link #RERANK_MAX}），入口在「知识库」Tab；
 * EMBEDDING 不允许 Agent 直绑（跟知识库 / 向量实例走）。
 * SUMMARY / MEMORY 仍是可绑用途，只是控制台入口搬到了「记忆配置」Tab，写路径不变。
 */
public final class PurposeSlotRules {

    /** 每槽有效绑定上限 */
    public static final int MAX_PER_SLOT = 3;

    /**
     * 重排槽上限。重排属检索后处理，Agent 只允许一个实例；
     * 存储仍是 agent_model_bind(purpose=RERANK)，但唯一配置入口是「知识库」Tab。
     */
    public static final int RERANK_MAX = 1;

    private PurposeSlotRules() {
    }

    /**
     * 能否经 Agent 侧用途槽入口绑定该用途。
     * <p>
     * 调用方包含「模型配置」与「记忆配置」两处（SUMMARY / MEMORY 走后者），二者共用同一写路径。
     * <ul>
     *   <li>EMBEDDING 不可：嵌入由知识库 / 向量实例选型并锁定维度，Agent 绑了也不参与运行时；</li>
     *   <li>RERANK 不可：重排属检索链路，入口唯一在「知识库」Tab（rag 域接口写入同一张表）。</li>
     * </ul>
     *
     * @param purpose 目标用途
     * @return true 表示允许在 Agent 用途槽写入
     */
    public static boolean agentBindable(ModelCapability purpose) {
        return purpose != null && purpose != ModelCapability.EMBEDDING && purpose != ModelCapability.RERANK;
    }

    /**
     * 槽容量：RERANK 单槽，其余生成/多模态/音频/安全槽最多 {@link #MAX_PER_SLOT}。
     *
     * @param purpose 目标用途
     * @return 该用途允许的最大绑定数
     */
    public static int slotCapacity(ModelCapability purpose) {
        return purpose == ModelCapability.RERANK ? RERANK_MAX : MAX_PER_SLOT;
    }

    /**
     * 绑定有效用途：purpose 覆盖优先，否则跟实例 capability（含旧码映射）。
     * 列表展示可读历史错绑；新建/改用途须另调 {@link #instanceMatchesSlot} 拒绝错用途。
     *
     * @param bind     绑定，可空 purpose
     * @param instance 实例
     * @return 规范用途；无法解析则空
     */
    public static Optional<ModelCapability> effectivePurpose(AgentModelBind bind, ModelInstance instance) {
        if (bind != null && bind.getPurpose() != null && !bind.getPurpose().isBlank()) {
            return ModelCapability.tryParse(bind.getPurpose());
        }
        if (instance == null || instance.getCapability() == null) {
            return Optional.empty();
        }
        return ModelCapability.tryParse(instance.getCapability());
    }

    /**
     * 实例 capability（含旧码）是否与目标槽用途一致。RERANK 槽不可绑 Embedding/Chat 等。
     *
     * @param instance 模型实例
     * @param slot     目标用途槽
     * @return 一致为 true；实例空或用途无法解析为 false
     */
    public static boolean instanceMatchesSlot(ModelInstance instance, ModelCapability slot) {
        if (instance == null || slot == null) {
            return false;
        }
        return ModelCapability.tryParse(instance.getCapability())
                .map(cap -> cap == slot)
                .orElse(false);
    }

    /**
     * @param purpose 用途
     * @return 是否要求至少一条（仅 CHAT）
     */
    public static boolean requiresAtLeastOne(ModelCapability purpose) {
        return purpose == ModelCapability.CHAT;
    }

    /**
     * ROUTER 用途仅允许在 ROUTER 运行模式下绑定。
     *
     * @param purpose 目标用途
     * @param runMode Agent 运行模式
     * @return 是否允许
     */
    public static boolean purposeAllowedForMode(ModelCapability purpose, String runMode) {
        if (purpose != ModelCapability.ROUTER) {
            return true;
        }
        return AgentRunMode.ROUTER.name().equals(runMode);
    }

    /**
     * 按 priority 升序、同 priority 按 id 升序。
     *
     * @param binds 同槽绑定
     * @return 新列表
     */
    public static List<AgentModelBind> sortByPriority(List<AgentModelBind> binds) {
        return binds.stream()
                .sorted(Comparator.comparing(AgentModelBind::getPriority, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AgentModelBind::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    /**
     * 槽内序号 1..n（按排序后位置）。
     *
     * @param sortedSlot 已排序的同槽绑定
     * @param bindId     当前绑定
     * @return 1-based；找不到为 0
     */
    public static int slotRank(List<AgentModelBind> sortedSlot, Long bindId) {
        for (int i = 0; i < sortedSlot.size(); i++) {
            if (Objects.equals(sortedSlot.get(i).getId(), bindId)) {
                return i + 1;
            }
        }
        return 0;
    }
}
