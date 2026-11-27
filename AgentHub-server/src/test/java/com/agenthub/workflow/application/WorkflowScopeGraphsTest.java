package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子图范围前缀的编码换算测试。所属域：workflow 的 application。
 *
 * <p>覆盖 {@code workflow_node_run.node_id / node_code} 的列宽边界：
 * 画布允许 64 字符编码，拼上 10 字符范围前缀后不能超列宽，展示仍要还原原始编码。
 */
class WorkflowScopeGraphsTest {

    @Test
    @DisplayName("超长节点编码：加前缀后不超过 varchar(64)，展示编码仍是原值")
    void longCodeFitsColumnWidthAndKeepsDisplayCode() {
        String prefix = WorkflowScopeGraphs.prefixOf("root/n1#1");
        String longCode = "c".repeat(64);

        String synthetic = WorkflowScopeGraphs.synthetic(prefix, longCode);
        assertThat(synthetic).startsWith(prefix);
        assertThat(synthetic.length()).isLessThanOrEqualTo(64);
        // 截断后补短哈希：同层两个长编码不会因为前段相同而撞名
        assertThat(WorkflowScopeGraphs.synthetic(prefix, longCode + "x"))
                .isNotEqualTo(synthetic);

        WorkflowNode node = new WorkflowNode();
        node.setId("n1");
        node.setCode(longCode);
        node.setType("START");
        WorkflowGraph graph = new WorkflowGraph();
        graph.setNodes(List.of(node));

        WorkflowGraph transformed = WorkflowScopeGraphs.transform(graph, prefix);
        String syntheticId = transformed.getNodes().get(0).getId();
        assertThat(transformed.getNodes().get(0).getCode().length()).isLessThanOrEqualTo(64);
        Map<String, String> displayCodes = WorkflowScopeGraphs.displayCodes(graph, prefix);
        assertThat(displayCodes).containsEntry(syntheticId, longCode);
    }
}
