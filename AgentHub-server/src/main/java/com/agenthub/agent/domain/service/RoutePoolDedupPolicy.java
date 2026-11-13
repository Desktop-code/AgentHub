package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.MaCandidate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 路由池去重规则。所属域：agent 的 domain。
 *
 * <p>规则（用户拍板，写死）：同一个 Agent 既以「裸专家」入池、又被某套已入池的编排方案
 * 当作成员包含时，<b>保留方案、去掉裸专家</b>，避免入口和场景方案抢同一个人。
 * 两套已入池方案之间即使成员重叠也不合并、不删方案——选哪套交给入口 Router 判。
 *
 * <p>为什么单独一个纯策略而不写进 {@link MaDefPolicy}：后者是「写库前硬校验」，
 * 本类只做「名单裁剪」，保存侧（去掉重复行 / 拒绝重复加入）与运行侧（展开候选时过滤）
 * 都要用同一套判定，单独成类才能保证两处口径不漂移。
 *
 * <p>「方案成员」只读目标方案自己的 {@code ma_candidate}；本类不查库，
 * 由调用方给出按方案 id 取候选的函数，避免 domain 反向依赖仓储实现。
 */
public final class RoutePoolDedupPolicy {

    private RoutePoolDedupPolicy() {
    }

    /**
     * 池内所有已入池方案的成员 Agent 主键（并集）。
     *
     * <p>只看 AGENT 候选：方案 / 工作流候选没有 agentId，天然不参与「谁被谁包含」的判断。
     * 停用的方案候选也计入：它在名单里就仍然占着这条线路，裁剪口径与用户看到的名单保持一致。
     *
     * @param pool         路由池（启用 / 停用都算）
     * @param candidatesOf 按方案主键取该方案的候选
     * @return 已被某套方案覆盖的 Agent 主键；没有方案候选时为空集合
     */
    public static Set<Long> coveredAgentIds(List<MaCandidate> pool,
                                            Function<Long, List<MaCandidate>> candidatesOf) {
        Set<Long> covered = new LinkedHashSet<>();
        if (pool == null || pool.isEmpty() || candidatesOf == null) {
            return covered;
        }
        for (MaCandidate item : pool) {
            if (!item.isMaTarget() || item.getMaDefId() == null) {
                continue;
            }
            for (MaCandidate member : membersOf(item.getMaDefId(), candidatesOf)) {
                if (member.getAgentId() != null) {
                    covered.add(member.getAgentId());
                }
            }
        }
        return covered;
    }

    /**
     * 裁剪路由池：方案优先，去掉「已被某套入池方案包含」的裸专家。
     *
     * <p>保留原有顺序（入口按 sort_no / 入池顺序判优先），不重排、不新增行。
     * 方案 / 工作流候选原样保留。
     *
     * @param pool         路由池
     * @param candidatesOf 按方案主键取该方案的候选
     * @return 裁剪后的名单；没有重复时返回原 {@code pool} 实例
     */
    public static List<MaCandidate> preferSchemes(List<MaCandidate> pool,
                                                  Function<Long, List<MaCandidate>> candidatesOf) {
        if (pool == null || pool.isEmpty()) {
            return pool == null ? List.of() : pool;
        }
        Set<Long> covered = coveredAgentIds(pool, candidatesOf);
        if (covered.isEmpty()) {
            return pool;
        }
        return pool.stream()
                .filter(item -> item.isMaTarget() || item.isWorkflowTarget()
                        || item.getAgentId() == null || !covered.contains(item.getAgentId()))
                .toList();
    }

    /**
     * 查找「包含该 Agent」的第一套已入池方案，用于拒绝「重复加入裸专家」时给出具体方案名。
     *
     * @param agentId      待加入的 Agent 主键
     * @param pool         当前路由池
     * @param candidatesOf 按方案主键取该方案的候选
     * @return 命中方案主键；没有为空
     */
    public static Optional<Long> schemeCovering(Long agentId, List<MaCandidate> pool,
                                                Function<Long, List<MaCandidate>> candidatesOf) {
        if (agentId == null || pool == null || pool.isEmpty() || candidatesOf == null) {
            return Optional.empty();
        }
        for (MaCandidate item : pool) {
            if (!item.isMaTarget() || item.getMaDefId() == null) {
                continue;
            }
            boolean contains = membersOf(item.getMaDefId(), candidatesOf).stream()
                    .anyMatch(member -> agentId.equals(member.getAgentId()));
            if (contains) {
                return Optional.of(item.getMaDefId());
            }
        }
        return Optional.empty();
    }

    /** 目标方案的成员候选；查不到按空集合处理，不让一次缺失拖垮整轮裁剪。 */
    private static List<MaCandidate> membersOf(Long maDefId, Function<Long, List<MaCandidate>> candidatesOf) {
        List<MaCandidate> members = candidatesOf.apply(maDefId);
        return members == null ? List.of() : members;
    }
}
