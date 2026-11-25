package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.application.WorkflowGraphCodec;
import com.agenthub.workflow.domain.model.WorkflowAgentBinding;
import com.agenthub.workflow.domain.model.WorkflowBindingSnapshot;
import com.agenthub.workflow.domain.model.WorkflowToolBinding;
import com.agenthub.workflow.domain.model.WorkflowValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图校验纯函数单测。所属域：workflow 的 domain。
 * <p>覆盖决议硬钉：环、DEFAULT 缺失/重复、FORK 无 JOIN、AGENT→ROUTER、双 START、
 * HUMAN 超时策略、悬空边、绑定缺失的发布/草稿分级。
 */
class WorkflowGraphValidatorTest {

    private final WorkflowGraphCodec codec = new WorkflowGraphCodec(new ObjectMapper());

    @Test
    @DisplayName("最小骨架 1 START + 1 END 应校验通过")
    void minimalSkeletonPasses() {
        WorkflowValidationResult result = validate(codec.defaultGraph(), WorkflowBindingSnapshot.empty(), true);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("空图必须报 START_MISSING 与 END_MISSING，不能静默通过")
    void emptyGraphFails() {
        WorkflowValidationResult result = validate(graph("", ""), WorkflowBindingSnapshot.empty(), true);

        assertThat(codes(result.getErrors())).contains("START_MISSING", "END_MISSING");
    }

    @Test
    @DisplayName("两个 START 报 START_MULTIPLE")
    void multipleStartFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "start2", "START", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n3", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        WorkflowValidationResult result = validate(json, WorkflowBindingSnapshot.empty(), true);

        assertThat(codes(result.getErrors())).contains("START_MULTIPLE");
    }

    @Test
    @DisplayName("有向环报 CYCLE")
    void cycleFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "a", "AGENT", "{\"agentId\":1}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "NORMAL") + "," +
                        edge("e3", "n3", "n2", "NORMAL"));

        WorkflowValidationResult result = validate(json, WorkflowBindingSnapshot.empty(), true);

        assertThat(codes(result.getErrors())).contains("CYCLE");
    }

    @Test
    @DisplayName("节点 code 图内重复报 NODE_CODE_DUPLICATE")
    void duplicateCodeFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "start", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL"));

        WorkflowValidationResult result = validate(json, WorkflowBindingSnapshot.empty(), true);

        assertThat(codes(result.getErrors())).contains("NODE_CODE_DUPLICATE");
    }

    @Test
    @DisplayName("CONDITION 缺 DEFAULT 抱错，恰好 1 条通过")
    void conditionDefaultRequired() {
        String missing = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "cond", "CONDITION", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "COND"));
        assertThat(codes(validate(missing, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("CONDITION_DEFAULT_MISSING");

        String ok = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "cond", "CONDITION", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "COND") + "," +
                        edge("e3", "n2", "n3", "DEFAULT"));
        assertThat(validate(ok, WorkflowBindingSnapshot.empty(), true).getErrors()).isEmpty();
    }

    @Test
    @DisplayName("CONDITION 两条 DEFAULT 报 CONDITION_DEFAULT_MULTIPLE")
    void conditionDefaultDuplicateFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "cond", "CONDITION", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "DEFAULT") + "," +
                        edge("e3", "n2", "n3", "DEFAULT"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("CONDITION_DEFAULT_MULTIPLE");
    }

    @Test
    @DisplayName("PARALLEL_FORK 无对应 JOIN 报 FORK_WITHOUT_JOIN")
    void forkWithoutJoinFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "fork", "PARALLEL_FORK", "{\"forkGroupId\":\"g1\"}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("FORK_WITHOUT_JOIN");
    }

    @Test
    @DisplayName("FORK 与 JOIN 同组成对应通过")
    void pairedForkJoinPasses() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "fork", "PARALLEL_FORK", "{\"forkGroupId\":\"g1\"}") + "," +
                        node("n3", "join", "PARALLEL_JOIN", "{\"forkGroupId\":\"g1\"}") + "," +
                        node("n4", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," +
                        edge("e2", "n2", "n3", "NORMAL") + "," +
                        edge("e3", "n3", "n4", "NORMAL"));

        assertThat(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()).isEmpty();
    }

    @Test
    @DisplayName("AGENT 绑 ROUTER：即使 Agent 存在且启用也必须 error（W2）")
    void agentRouterIsHardError() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "router", "AGENT", "{\"agentId\":9}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        Map<Long, WorkflowAgentBinding> agents = new HashMap<>();
        agents.put(9L, new WorkflowAgentBinding(9L, "总控路由", "ROUTER", 1));

        WorkflowValidationResult result =
                validate(json, new WorkflowBindingSnapshot(agents, Map.of()), true);

        assertThat(codes(result.getErrors())).contains("AGENT_ROUTER_FORBIDDEN");
        assertThat(result.getErrors())
                .anyMatch(issue -> issue.getMessage().contains("PARALLEL_FORK"));
    }

    @Test
    @DisplayName("AGENT 绑定不存在：发布 error、草稿 warn")
    void missingAgentBindingLevelDependsOnPublishMode() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "leaf", "AGENT", "{\"agentId\":404}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        WorkflowValidationResult publish = validate(json, WorkflowBindingSnapshot.empty(), true);
        WorkflowValidationResult draft = validate(json, WorkflowBindingSnapshot.empty(), false);

        assertThat(codes(publish.getErrors())).contains("AGENT_NOT_FOUND");
        assertThat(codes(draft.getWarns())).contains("AGENT_NOT_FOUND");
        assertThat(codes(draft.getErrors())).doesNotContain("AGENT_NOT_FOUND");
    }

    @Test
    @DisplayName("AGENT 绑定已停用：发布 error")
    void disabledAgentFailsPublish() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "leaf", "AGENT", "{\"agentId\":9}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        Map<Long, WorkflowAgentBinding> agents = new HashMap<>();
        agents.put(9L, new WorkflowAgentBinding(9L, "叶子", "REACT", 0));

        assertThat(codes(validate(json, new WorkflowBindingSnapshot(agents, Map.of()), true).getErrors()))
                .contains("AGENT_DISABLED");
    }

    @Test
    @DisplayName("TOOL 引用不存在的工具：发布 error")
    void missingToolFailsPublish() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "tool", "TOOL", "{\"toolId\":88}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("TOOL_NOT_FOUND");
    }

    @Test
    @DisplayName("HUMAN 未配置超时策略报 HUMAN_TIMEOUT_MISSING")
    void humanWithoutTimeoutFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "approve", "HUMAN", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("HUMAN_TIMEOUT_MISSING");
    }

    @Test
    @DisplayName("HUMAN 超时策略非法同样 error")
    void humanWithInvalidTimeoutFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "approve", "HUMAN", "{\"timeoutPolicy\":\"IGNORE\"}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("HUMAN_TIMEOUT_INVALID");
    }

    @Test
    @DisplayName("悬空边（端点不存在）报 EDGE_DANGLING")
    void danglingEdgeFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n1", "ghost", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("EDGE_DANGLING");
    }

    @Test
    @DisplayName("P1 节点（SUBGRAPH）本刀不支持，报 NODE_TYPE_P1_ONLY")
    void p1NodeRejected() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "sub", "SUBGRAPH", "{}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("NODE_TYPE_P1_ONLY");
    }

    @Test
    @DisplayName("指纹对节点数组顺序不敏感，依赖变化后变化")
    void fingerprintStableAndSensitive() {
        String a = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "b", "AGENT", "{\"agentId\":2}") + "," +
                        node("n3", "a", "TOOL", "{\"toolId\":3}") + "," +
                        node("n4", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL"));
        String b = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n3", "a", "TOOL", "{\"toolId\":3}") + "," +
                        node("n2", "b", "AGENT", "{\"agentId\":2}") + "," +
                        node("n4", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL"));
        String changed = a.replace("\"toolId\":3", "\"toolId\":4");

        String fpA = WorkflowFingerprint.of(codec.parse(a));
        String fpB = WorkflowFingerprint.of(codec.parse(b));
        String fpChanged = WorkflowFingerprint.of(codec.parse(changed));

        assertThat(fpA).hasSize(64).isEqualTo(fpB);
        assertThat(fpChanged).isNotEqualTo(fpA);
        // 无依赖图也要有稳定非空指纹
        assertThat(WorkflowFingerprint.of(codec.parse(codec.defaultGraph())))
                .isEqualTo(WorkflowFingerprint.of(codec.parse(codec.defaultGraph())));
    }

    @Test
    @DisplayName("HUMAN 处理人规则非法：拒发（HUMAN_ASSIGNEE_INVALID）")
    void humanWithInvalidAssigneeFails() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "approve", "HUMAN",
                                "{\"timeoutPolicy\":\"AUTO_REJECT\",\"assigneeRule\":\"boss\"}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        assertThat(codes(validate(json, WorkflowBindingSnapshot.empty(), true).getErrors()))
                .contains("HUMAN_ASSIGNEE_INVALID");
    }

    @Test
    @DisplayName("HUMAN 未配处理人规则：只给 warn，不阻断发布（空规则兼容）")
    void humanWithEmptyAssigneeOnlyWarns() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "approve", "HUMAN", "{\"timeoutPolicy\":\"AUTO_REJECT\"}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));

        WorkflowValidationResult result = validate(json, WorkflowBindingSnapshot.empty(), true);

        assertThat(result.passed()).isTrue();
        assertThat(codes(result.getWarns())).contains("HUMAN_ASSIGNEE_EMPTY");
    }

    @Test
    @DisplayName("HUMAN 处理人规则合法：ROLE / USER 都不产生 error")
    void humanWithValidAssigneePasses() {
        String role = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "approve", "HUMAN",
                                "{\"timeoutPolicy\":\"AUTO_REJECT\",\"assigneeRule\":\"ROLE:ops_admin\"}") + "," +
                        node("n3", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL") + "," + edge("e2", "n2", "n3", "NORMAL"));
        String user = role.replace("ROLE:ops_admin", "USER:7,9");

        assertThat(validate(role, WorkflowBindingSnapshot.empty(), true).getErrors()).isEmpty();
        assertThat(validate(user, WorkflowBindingSnapshot.empty(), true).getErrors()).isEmpty();
    }

    @Test
    @DisplayName("节点数硬顶可由配置覆盖：超过生效上限报 GRAPH_TOO_LARGE")
    void maxNodesPerGraphOverride() {
        String json = graph(
                node("n1", "start", "START", "{}") + "," +
                        node("n2", "end", "END", "{}"),
                edge("e1", "n1", "n2", "NORMAL"));

        WorkflowValidationResult result = WorkflowGraphValidator.validate(
                codec.parse(json), WorkflowBindingSnapshot.empty(), true, 1);

        assertThat(codes(result.getErrors())).contains("GRAPH_TOO_LARGE");
        assertThat(result.getErrors())
                .anyMatch(issue -> issue.getMessage().contains("max-nodes-per-graph"));
    }

    private WorkflowValidationResult validate(String json,
                                             WorkflowBindingSnapshot bindings,
                                             boolean publishMode) {
        return WorkflowGraphValidator.validate(codec.parse(json), bindings, publishMode);
    }

    private static List<String> codes(List<com.agenthub.workflow.domain.model.WorkflowIssue> issues) {
        return issues.stream().map(com.agenthub.workflow.domain.model.WorkflowIssue::getCode).toList();
    }

    private static String graph(String nodes, String edges) {
        return "{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
    }

    private static String node(String id, String code, String type, String config) {
        return "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"type\":\"" + type
                + "\",\"name\":\"" + code + "\",\"config\":" + config + "}";
    }

    private static String edge(String id, String source, String target, String type) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source + "\",\"target\":\"" + target
                + "\",\"type\":\"" + type + "\",\"config\":{}}";
    }
}
