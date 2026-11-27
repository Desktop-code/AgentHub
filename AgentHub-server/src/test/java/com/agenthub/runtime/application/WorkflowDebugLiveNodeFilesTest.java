package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试收口时「已流出的文件行不能因为按库现算而消失」的用例。所属域：runtime 的 application。
 *
 * <p>钉住本刀的症状：取消 / 人工驳回走请求线程，拿不到引擎线程上的投影，
 * 收口只能按库现算终态卡；而 {@code workflow_node_run} 没有文件清单这一列，
 * 现算的卡会把用户已经看到的文件行连同下载入口一起抹掉。
 *
 * <p>边界：纯内存视图，不连库、不出站。
 */
class WorkflowDebugLiveNodeFilesTest {

    /**
     * 核心场景：终态卡与上一帧的节点对得上，文件行要回到同一个子图内部节点上。
     */
    @Test
    @DisplayName("收口卡从上一帧补回子图内部节点的文件行")
    void keepsInnerNodeFiles() {
        WorkflowRunView previous = view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of(file("guess-number.html", 11586))),
                node("x8f9629e7_check_agent", "SUCCESS", "impl_check", List.of()));
        WorkflowRunView rebuilt = view(
                node("x8f9629e7_impl_agent", "CANCELLED", "impl_check", List.of()),
                node("x8f9629e7_check_agent", "SKIPPED", "impl_check", List.of()));

        WorkflowRunView merged = WorkflowDebugLive.withNodeFiles(rebuilt, previous);

        WorkflowRunView.Node inner = merged.nodes().get(0);
        assertEquals(1, inner.files().size(), "文件行要补回来：" + merged.nodes());
        assertEquals("guess-number.html", inner.files().get(0).fileName());
        // 状态必须仍按库里算出来的终态，不被上一帧改回去
        assertEquals("CANCELLED", inner.status());
        // 没有文件的节点不新造文件行
        assertTrue(merged.nodes().get(1).files().isEmpty());
        assertEquals("SUCCESS", merged.status());
    }

    /**
     * 按库还原不出子图内部节点（只剩父框）时，文件落到父图 SUBGRAPH 节点上。
     */
    @Test
    @DisplayName("内部节点缺席时文件落到父图子图节点")
    void bubblesToSubgraphFrame() {
        WorkflowRunView previous = view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of(file("guess-number.html", 11586))),
                subgraph("impl_check", "SUCCESS", ""));
        WorkflowRunView rebuilt = view(subgraph("impl_check", "CANCELLED", ""));

        WorkflowRunView merged = WorkflowDebugLive.withNodeFiles(rebuilt, previous);

        assertEquals(1, merged.nodes().size());
        assertEquals(1, merged.nodes().get(0).files().size(), "父框要给出可下载入口");
        assertEquals("guess-number.html", merged.nodes().get(0).files().get(0).fileName());
    }

    /**
     * 重建卡自己已经带了同名文件行时以它为准，不重复追加。
     */
    @Test
    @DisplayName("重建卡已有的文件行优先且不重复")
    void keepsRebuiltFiles() {
        WorkflowRunView previous = view(
                node("impl_agent", "SUCCESS", "", List.of(file("guess-number.html", 11586))));
        WorkflowRunView rebuilt = view(
                node("impl_agent", "SUCCESS", "", List.of(file("guess-number.html", 2048))));

        WorkflowRunView merged = WorkflowDebugLive.withNodeFiles(rebuilt, previous);

        assertEquals(1, merged.nodes().get(0).files().size());
        assertEquals(2048L, merged.nodes().get(0).files().get(0).size());
    }

    /**
     * 没有上一帧 / 上一帧没有文件：原样返回同一个视图，收口口径不变。
     */
    @Test
    @DisplayName("没有可补的文件时原样返回")
    void keepsRebuiltWhenNothingToCarry() {
        WorkflowRunView rebuilt = view(node("impl_agent", "SUCCESS", "", List.of()));

        assertSame(rebuilt, WorkflowDebugLive.withNodeFiles(rebuilt, null));
        assertSame(rebuilt, WorkflowDebugLive.withNodeFiles(rebuilt, view()));
        assertSame(rebuilt, WorkflowDebugLive.withNodeFiles(rebuilt, view(
                node("impl_agent", "SUCCESS", "", List.of()))));
    }

    /**
     * 造一张卡。
     *
     * @param nodes 节点
     * @return 视图
     */
    private static WorkflowRunView view(WorkflowRunView.Node... nodes) {
        return new WorkflowRunView("小游戏研发", "mini_game_dev", "SUCCESS", "", List.of(nodes),
                null, null, 136L);
    }

    /**
     * 造一个普通节点。
     *
     * @param code   节点编码
     * @param status 状态
     * @param group  所属框
     * @param files  文件行
     * @return 节点
     */
    private static WorkflowRunView.Node node(String code, String status, String group,
                                             List<WorkflowRunView.NodeFile> files) {
        return new WorkflowRunView.Node(code, code, "AGENT", status, "", "", List.of(), files,
                "", 1, group, 0, 0, "", "");
    }

    /**
     * 造一个父图 SUBGRAPH 节点。
     *
     * @param code   编码
     * @param status 状态
     * @param group  外层框
     * @return 节点
     */
    private static WorkflowRunView.Node subgraph(String code, String status, String group) {
        return new WorkflowRunView.Node(code, "实现和检查 · v1", "SUBGRAPH", status, "已完成", "",
                List.of(), List.of(), "", 1, group, 0, 0, "", "");
    }

    /**
     * 造一行文件。
     *
     * @param fileName 文件名
     * @param size     体积
     * @return 文件行
     */
    private static WorkflowRunView.NodeFile file(String fileName, long size) {
        return new WorkflowRunView.NodeFile(fileName, size);
    }
}
