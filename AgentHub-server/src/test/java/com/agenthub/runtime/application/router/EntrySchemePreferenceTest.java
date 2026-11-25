package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.runtime.application.router.EntrySchemePreference.MemberDuty;
import com.agenthub.runtime.domain.model.MaRunSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 入口点名方案、多项交付不要被单兵截走。所属域：runtime。
 *
 * <p>不连库。成员用既往手点里的名字：综合台下是制度知识专家、报告撰写专家，
 * 池里另有裸专家知识问答。preferSchemes 对不上成员，所以裸专家仍在名单里。
 */
class EntrySchemePreferenceTest {

    @Test
    void namedSchemeBeatsSimilarExpert() {
        Candidate scheme = scheme();
        Candidate knowledge = expert("knowledge", "知识问答");
        String question = "请企业综合台根据公司制度整理知识要点，并起草一份报告";

        Candidate named = EntrySchemePreference.namedScheme(question, List.of(knowledge, scheme));

        assertSame(scheme, named);
    }

    @Test
    void twoDeliverablesCoveredByMembersGoToScheme() {
        Candidate scheme = scheme();
        String question = "根据公司制度整理知识要点，并起草一份报告";
        Map<Long, List<MemberDuty>> duties = Map.of(70L, List.of(
                new MemberDuty("制度知识专家", "检索制度与知识"),
                new MemberDuty("报告撰写专家", "起草正式报告")));

        Candidate covered = EntrySchemePreference.coveringScheme(question, List.of(expert("knowledge", "知识问答"), scheme),
                duties);

        assertSame(scheme, covered);
    }

    @Test
    void singlePolicyQuestionDoesNotForceScheme() {
        Candidate scheme = scheme();
        String question = "年假制度是怎么规定的";
        Map<Long, List<MemberDuty>> duties = Map.of(70L, List.of(
                new MemberDuty("制度知识专家", "检索制度与知识"),
                new MemberDuty("报告撰写专家", "起草正式报告")));

        assertNull(EntrySchemePreference.namedScheme(question, List.of(scheme)));
        assertNull(EntrySchemePreference.coveringScheme(question, List.of(scheme), duties));
    }

    @Test
    void smallTalkDoesNotForceScheme() {
        Candidate scheme = scheme();
        Map<Long, List<MemberDuty>> duties = Map.of(70L, List.of(
                new MemberDuty("制度知识专家", "检索制度与知识"),
                new MemberDuty("报告撰写专家", "起草正式报告")));

        assertNull(EntrySchemePreference.namedScheme("你好", List.of(scheme)));
        assertNull(EntrySchemePreference.coveringScheme("你好", List.of(scheme), duties));
    }

    @Test
    void descriptionClausesAlsoCountAsTwoDeliverables() {
        Candidate scheme = scheme("制度检索；报告起草");
        String question = "请先做制度检索，再完成报告起草";

        Candidate covered = EntrySchemePreference.coveringScheme(question, List.of(scheme), Map.of());

        assertEquals("企业综合台", covered.maTarget().name());
    }

    /**
     * 内层覆盖口径：原问句命中制度知识专家与报告撰写专家两位。
     *
     * <p>「制度」与「报告」分别来自两位成员核心名的首尾两字，属于首尾两字档命中。
     */
    @Test
    void innerCoverageHitsBothPolicyAndReport() {
        boolean[] covered = EntrySchemePreference.coveredBy(
                "请综合台根据公司制度整理知识要点，并起草一份报告", innerDuties());

        assertArrayEquals(new boolean[]{false, false, true, true, false}, covered);
    }

    /**
     * 内层覆盖口径：单问「退款制度」只算制度知识专家。
     *
     * <p>「退款」与「制度」都只能靠首尾两字档命中且紧挨着，前一段是修饰、后一段是中心语，
     * 所以退款客服专家不算命中——否则一句制度类问题会被两位成员同时认领。
     */
    @Test
    void innerCoverageDoesNotLetRefundExpertClaimPolicyQuestion() {
        boolean[] covered = EntrySchemePreference.coveredBy("查询下退款制度", innerDuties());

        assertArrayEquals(new boolean[]{false, false, true, false, false}, covered);
    }

    /**
     * 内层覆盖口径：只打中一位时也只返回这一位，不会把无关成员带上。
     */
    @Test
    void innerCoverageSingleDeliverableStaysSingle() {
        boolean[] covered = EntrySchemePreference.coveredBy("帮我起草一份报告", innerDuties());

        assertArrayEquals(new boolean[]{false, false, false, true, false}, covered);
    }

    /**
     * 内层覆盖口径：寒暄与完全无关的问题一位都不命中，交由判路由模型判断。
     */
    @Test
    void innerCoverageMissesWhenNothingMatches() {
        assertFalse(any(EntrySchemePreference.coveredBy("你好", innerDuties())));
        assertFalse(any(EntrySchemePreference.coveredBy("今天天气怎么样", innerDuties())));
    }

    /**
     * 入口口径不受内层收紧影响：同一句在入口仍按原四档判「多名成员被打中」。
     *
     * <p>「退款」命中退款客服专家的核心名前两字、「制度」命中制度知识专家的核心名前两字，
     * 入口按原口径算两名成员，因此整句交给方案；内层收紧后只算制度知识专家一位。
     * 这条同时锁住两件事：入口没被改，内层确实比入口严。
     */
    @Test
    void entryCoveringStaysLooseWhileInnerNarrows() {
        Candidate scheme = scheme();
        String question = "查询下退款制度";
        List<MemberDuty> duties = List.of(
                new MemberDuty("退款客服专家", "退款售后政策问答与处理建议"),
                new MemberDuty("制度知识专家", "制度与流程检索问答"));

        // 入口：两名成员都被打中 → 整句交给方案
        assertSame(scheme, EntrySchemePreference.coveringScheme(question, List.of(scheme),
                Map.of(70L, duties)));
        // 内层：相邻修饰被收紧，只剩制度知识专家
        assertArrayEquals(new boolean[]{false, true}, EntrySchemePreference.coveredBy(question, duties));
    }

    /** 综合台内层成员顺序：退款客服 / 运维排障 / 制度知识 / 报告撰写 / 对话报销。 */
    private static List<MemberDuty> innerDuties() {
        return List.of(
                new MemberDuty("退款客服专家", "退款售后政策问答与处理建议"),
                new MemberDuty("高级运维排障专家", "先规划再执行，可自动读取日志、分析故障并生成 Markdown 排版报告的专家。"),
                new MemberDuty("制度知识专家", "制度与流程检索问答"),
                new MemberDuty("报告撰写专家", "整理材料为结构化报告并可落盘"),
                new MemberDuty("对话报销专员", "点名「对话报销专员」时交给本专家，负责收齐报销材料并提交。"));
    }

    private static boolean any(boolean[] flags) {
        for (boolean flag : flags) {
            if (flag) {
                return true;
            }
        }
        return false;
    }

    /** 综合台：成员不是知识问答，去重不会把它从入口池拿掉。 */
    private static Candidate scheme() {
        return scheme("同时覆盖制度知识与报告起草");
    }

    private static Candidate scheme(String description) {
        MaRunSpec target = new MaRunSpec(70L, "enterprise", "企业综合台", description, "ROUTER_PARALLEL",
                null, List.of(), null, null, false, Map.of(), Map.of());
        AgentDef shell = new AgentDef();
        shell.setName("企业综合台");
        shell.setDescription(description);
        shell.setStatus(AgentStatus.ENABLED);
        MaCandidate row = new MaCandidate();
        row.setTargetType(MaCandidate.TARGET_MA);
        row.setMaDefId(70L);
        row.setRouteKey("enterprise");
        row.setEnabled(1);
        AgentRoute route = new AgentRoute();
        route.setRouteKey("enterprise");
        route.setStatus(AgentStatus.ENABLED);
        return new Candidate(route, shell, target);
    }

    private static Candidate expert(String key, String name) {
        AgentDef agent = new AgentDef();
        agent.setName(name);
        agent.setStatus(AgentStatus.ENABLED);
        AgentRoute route = new AgentRoute();
        route.setRouteKey(key);
        route.setStatus(AgentStatus.ENABLED);
        return new Candidate(route, agent);
    }
}
