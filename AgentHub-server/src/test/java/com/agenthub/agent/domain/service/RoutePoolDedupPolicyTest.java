package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.MaCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由池去重策略用例。所属域：agent 的 domain。
 *
 * <p>钉死用户拍板的四条口径：方案优先于重复裸专家；两套方案成员重叠不合并；
 * 方案 / 工作流候选永远保留；方案查不到成员时不做无谓裁剪。
 */
class RoutePoolDedupPolicyTest {

    /** 按方案 id 取成员的桩：只给两套方案，其余视为查不到。 */
    private static Function<Long, List<MaCandidate>> members(Map<Long, List<Long>> schemeToAgents) {
        return id -> schemeToAgents.getOrDefault(id, List.of()).stream()
                .map(RoutePoolDedupPolicyTest::agentItem)
                .toList();
    }

    /** 同一 Agent 兼作「裸专家入池」和「方案成员」时，只留方案。 */
    @Test
    void coveredBareAgentIsDroppedInFavourOfScheme() {
        MaCandidate scheme = maItem(7L);
        MaCandidate bareA = agentItem(100L);
        MaCandidate bareD = agentItem(400L);

        List<MaCandidate> result = RoutePoolDedupPolicy.preferSchemes(
                List.of(scheme, bareA, bareD), members(Map.of(7L, List.of(100L, 200L, 300L))));

        assertEquals(List.of(scheme, bareD), result);
    }

    /** 两套方案成员重叠：都不删、不合并，交给入口 Router 自己判。 */
    @Test
    void overlappingSchemesAreBothKept() {
        MaCandidate first = maItem(7L);
        MaCandidate second = maItem(8L);

        List<MaCandidate> result = RoutePoolDedupPolicy.preferSchemes(
                List.of(first, second), members(Map.of(7L, List.of(100L, 200L), 8L, List.of(200L, 300L))));

        assertEquals(List.of(first, second), result);
    }

    /** 工作流候选没有 agentId，不能因为「看起来重复」被裁掉。 */
    @Test
    void workflowTargetsSurviveTrimming() {
        MaCandidate scheme = maItem(7L);
        MaCandidate workflow = new MaCandidate();
        workflow.setTargetType(MaCandidate.TARGET_WORKFLOW);
        workflow.setWorkflowDefId(5L);
        MaCandidate bareA = agentItem(100L);

        List<MaCandidate> result = RoutePoolDedupPolicy.preferSchemes(
                List.of(scheme, workflow, bareA), members(Map.of(7L, List.of(100L))));

        assertEquals(List.of(scheme, workflow), result);
    }

    /** 只有裸专家、没有方案时原样返回（不复制、不重排），避免上层误判名单被改动。 */
    @Test
    void poolWithoutSchemeIsReturnedAsIs() {
        List<MaCandidate> pool = List.of(agentItem(100L), agentItem(200L));

        List<MaCandidate> result = RoutePoolDedupPolicy.preferSchemes(pool, members(Map.of()));

        assertTrue(pool == result, "无方案时应返回原实例");
    }

    /** 覆盖集合只收 AGENT 成员：方案 / 工作流成员不参与「谁被谁包含」。 */
    @Test
    void coveredAgentIdsOnlyCollectsAgentMembers() {
        Set<Long> covered = RoutePoolDedupPolicy.coveredAgentIds(
                List.of(maItem(7L)), members(Map.of(7L, List.of(100L))));

        assertEquals(Set.of(100L), covered);
    }

    /** 重复加入裸专家时，返回包含它的方案主键，用于给出具体方案名。 */
    @Test
    void schemeCoveringFindsTheOwnerScheme() {
        Optional<Long> covering = RoutePoolDedupPolicy.schemeCovering(
                200L, List.of(maItem(7L), maItem(8L)),
                members(Map.of(7L, List.of(100L), 8L, List.of(200L))));

        assertEquals(Optional.of(8L), covering);
    }

    /** 没有方案包含该 Agent 时应当放行。 */
    @Test
    void schemeCoveringIsEmptyWhenNoSchemeContainsAgent() {
        Optional<Long> covering = RoutePoolDedupPolicy.schemeCovering(
                999L, List.of(maItem(7L)), members(Map.of(7L, List.of(100L))));

        assertFalse(covering.isPresent());
    }

    /** 裸专家候选。 */
    private static MaCandidate agentItem(Long agentId) {
        MaCandidate item = new MaCandidate();
        item.setAgentId(agentId);
        item.setEnabled(1);
        return item;
    }

    /** 方案候选。 */
    private static MaCandidate maItem(Long maDefId) {
        MaCandidate item = new MaCandidate();
        item.setTargetType(MaCandidate.TARGET_MA);
        item.setMaDefId(maDefId);
        item.setEnabled(1);
        return item;
    }
}
