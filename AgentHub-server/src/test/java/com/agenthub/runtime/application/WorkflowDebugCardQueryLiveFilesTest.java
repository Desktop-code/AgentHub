package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试详情「按库现算 + 直播快照补文件行」的用例。所属域：runtime 的 application。
 *
 * <p>钉住本刀的症状：调试运行没有工作台会话事件骨架，文件清单只活在
 * {@link WorkflowDebugLiveCards} 那一帧里。详情卡片按库现算算不出文件，
 * 刷新 / 关掉再打开后子图内部节点的文件行整块消失；这里验证详情会从快照补回，
 * 且只补文件、不动状态与正文。
 *
 * <p>边界：纯内存视图与 JSON，不连库、不出站。
 */
class WorkflowDebugCardQueryLiveFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 只有 json 参与合并，其余依赖传 null 即可。 */
    private final WorkflowDebugCardQuery query = new WorkflowDebugCardQuery(null, null, null, JSON);

    /**
     * 核心场景：详情卡片与快照的节点对得上，文件行要回到同一个子图内部节点。
     */
    @Test
    @DisplayName("详情卡片从直播快照补回子图内部节点的文件行")
    void fillsInnerNodeFiles() {
        Map<String, Object> base = WorkflowRunJson.payload(view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of()),
                node("x8f9629e7_check_agent", "SUCCESS", "impl_check", List.of())));
        WorkflowRunView live = view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of(file("guess-number.html", 11586))),
                node("x8f9629e7_check_agent", "SUCCESS", "impl_check", List.of()));

        Map<String, Object> merged = query.withLiveFiles(base, live);

        List<Map<String, Object>> nodes = nodesOf(merged);
        Map<String, Object> inner = nodes.get(0);
        assertEquals("x8f9629e7_impl_agent", inner.get("code"));
        assertEquals("impl_check", inner.get("group"));
        List<?> files = (List<?>) inner.get("files");
        assertNotNull(files, "文件行要补回来：" + merged);
        assertEquals(1, files.size());
        assertEquals("guess-number.html", ((Map<?, ?>) files.get(0)).get("fileName"));
        // 没有文件的节点仍不写 files 键，不空出一行
        assertFalse(nodes.get(1).containsKey("files"));
        // 详情自己的字段不被快照覆盖
        assertEquals("SUCCESS", inner.get("status"));
    }

    /**
     * 快照里只有内部节点、详情里只剩父图 SUBGRAPH（子图范围还原失败）：
     * 文件要落到看得见的那层父框上，不能消失。
     */
    @Test
    @DisplayName("详情只剩父图子图节点时文件落到父框")
    void bubblesToSubgraphFrame() {
        Map<String, Object> base = WorkflowRunJson.payload(view(
                new WorkflowRunView.Node("impl_check", "实现和检查 · v1", "SUBGRAPH", "SUCCESS",
                        "已完成", "", List.of(), List.of(), "", 1, "", 0, 0, "", "")));
        WorkflowRunView live = view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of(file("guess-number.html", 11586))),
                new WorkflowRunView.Node("impl_check", "实现和检查 · v1", "SUBGRAPH", "SUCCESS",
                        "已完成", "", List.of(), List.of(), "", 1, "", 0, 0, "", ""));

        Map<String, Object> merged = query.withLiveFiles(base, live);

        Map<String, Object> frame = nodesOf(merged).get(0);
        assertEquals("impl_check", frame.get("code"));
        List<?> files = (List<?>) frame.get("files");
        assertNotNull(files, "父框要给出可下载入口：" + merged);
        assertEquals("guess-number.html", ((Map<?, ?>) files.get(0)).get("fileName"));
    }

    /** 没有直播快照时原样返回（同一引用），详情查询行为不变。 */
    @Test
    @DisplayName("没有直播快照时不改详情卡片")
    void keepsPayloadWithoutLive() {
        Map<String, Object> base = WorkflowRunJson.payload(view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of())));

        assertSame(base, query.withLiveFiles(base, null));
        assertSame(base, query.withLiveFiles(base, view()));
    }

    /** 快照里没有节点时同样原样返回。 */
    @Test
    @DisplayName("快照没有节点时不改详情卡片")
    void keepsPayloadWhenLiveHasNoNodes() {
        Map<String, Object> base = WorkflowRunJson.payload(view(
                node("x8f9629e7_impl_agent", "SUCCESS", "impl_check", List.of())));

        assertSame(base, query.withLiveFiles(base, view()));
        assertTrue(nodesOf(base).get(0).containsKey("code"));
    }

    /**
     * 造一张卡。
     *
     * @param nodes 节点
     * @return 视图
     */
    private static WorkflowRunView view(WorkflowRunView.Node... nodes) {
        return new WorkflowRunView("小游戏研发", "mini_game_dev", "SUCCESS", "",
                List.of(nodes), null, null, 136L);
    }

    /**
     * 造一个节点。
     *
     * @param code  节点编码（子图内部带范围前缀）
     * @param status 状态
     * @param group 所属框
     * @param files 文件行
     * @return 节点
     */
    private static WorkflowRunView.Node node(String code, String status, String group,
                                             List<WorkflowRunView.NodeFile> files) {
        return new WorkflowRunView.Node(code, code, "AGENT", status, "已完成", "",
                List.of(), files, "", 1, group, 0, 0, "", "");
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

    /**
     * 取载荷里的节点数组。
     *
     * @param payload 载荷
     * @return 节点列表
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodesOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("nodes");
    }
}
