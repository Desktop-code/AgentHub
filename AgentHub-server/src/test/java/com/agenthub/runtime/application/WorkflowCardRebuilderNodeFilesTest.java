package com.agenthub.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刷新回放整卡重建时「节点文件行不能丢」的用例。所属域：runtime 的 application。
 *
 * <p>钉住本刀的症状：子图 run 的最后一张骨架会被整卡按库重建
 * （{@code WorkflowCardRebuilder#rebuiltWithScopes} → {@code persisted}），而
 * 「哪个节点写出了哪个文件」只存在于骨架里——{@code workflow_node_run} 没有这一列，
 * 文件本身在 run 的工作区目录。重建时若不搬，刷新后子图内部节点（例如
 * {@code x8f9629e7_impl_agent}「游戏实现」）的文件行整块消失。
 *
 * <p>边界：纯 JSON 入参，不连库、不出站；只校验文件行，状态 / 正文 / 过程步口径不变。
 */
class WorkflowCardRebuilderNodeFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 核心场景：骨架里子图内部节点有文件行，重建后的新卡没有 → 必须搬到同一个节点下。
     */
    @Test
    @DisplayName("子图内部节点的文件行在整卡重建后仍然留在原节点上")
    void keepsInnerNodeFileRows() {
        ObjectNode skeleton = skeletonWithFiles();
        ObjectNode rebuilt = rebuiltWithoutFiles();

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        JsonNode inner = nodeOf(rebuilt, "x8f9629e7_impl_agent");
        assertNotNull(inner, "子图内部节点仍在时间线上");
        JsonNode files = inner.get("files");
        assertTrue(files instanceof ArrayNode, "文件行必须搬回来：" + rebuilt);
        assertEquals(1, files.size(), "同名文件只有一行");
        assertEquals("guess-number.html", files.get(0).path("fileName").asText());
        assertEquals(11586L, files.get(0).path("size").asLong());
        // 归属不变：仍挂在父图 SUBGRAPH 节点那一层框里
        assertEquals("impl_check", inner.path("group").asText());
    }

    /**
     * 重建后的卡片自己已经带了文件行时不覆盖：投影已经算出来就以它为准。
     */
    @Test
    @DisplayName("新卡已经带文件行时不被骨架覆盖")
    void keepsRebuiltFilesWhenPresent() {
        ObjectNode skeleton = skeletonWithFiles();
        ObjectNode rebuilt = rebuiltWithoutFiles();
        ObjectNode inner = (ObjectNode) nodeOf(rebuilt, "x8f9629e7_impl_agent");
        inner.set("files", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("fileName", "guess-number.html").put("size", 2048L)));

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        assertEquals(2048L, nodeOf(rebuilt, "x8f9629e7_impl_agent")
                .path("files").get(0).path("size").asLong(), "新卡自己那份优先");
    }

    /**
     * 多轮循环：同编码不同轮次各搬各的，不把第 1 轮的文件挂到第 2 轮。
     */
    @Test
    @DisplayName("同编码多轮按轮次分别匹配")
    void matchesByAttempt() {
        ObjectNode skeleton = JSON.createObjectNode();
        skeleton.set("nodes", JSON.createArrayNode()
                .add(node("play_impl", 1, "first.html", 10L))
                .add(node("play_impl", 2, "second.html", 20L)));
        ObjectNode rebuilt = JSON.createObjectNode();
        ObjectNode roundTwo = JSON.createObjectNode().put("code", "play_impl").put("attempt", 2);
        rebuilt.set("nodes", JSON.createArrayNode().add(roundTwo));

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        assertEquals(1, roundTwo.path("files").size());
        assertEquals("second.html", roundTwo.path("files").get(0).path("fileName").asText(),
                "第 2 轮只拿第 2 轮的文件");
    }

    /** 骨架没有文件行时一个新键都不加：没有文件就不占位置。 */
    @Test
    @DisplayName("骨架没有文件行时新卡不出现空数组")
    void addsNothingWhenSkeletonHasNoFiles() {
        ObjectNode skeleton = JSON.createObjectNode();
        skeleton.set("nodes", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("code", "x8f9629e7_impl_agent").put("status", "SUCCESS")));
        ObjectNode rebuilt = rebuiltWithoutFiles();

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        assertFalse(nodeOf(rebuilt, "x8f9629e7_impl_agent").has("files"), "没文件就不要空出一行");
    }

    /**
     * 子图范围还原失败、内部节点整块拍平时：文件改挂到看得见的父图 SUBGRAPH 节点，
     * 不能跟着内部节点一起消失（「某一面看不到内部节点也要有下载入口」）。
     */
    @Test
    @DisplayName("子图被拍平时文件落到父图 SUBGRAPH 节点")
    void bubblesFilesToSubgraphFrameWhenInnerNodesMissing() {
        ObjectNode skeleton = skeletonWithFiles();
        // 重建结果只剩父图两个节点：子图内部节点一条都没有
        ObjectNode rebuilt = JSON.createObjectNode();
        ObjectNode frame = JSON.createObjectNode().put("code", "impl_check")
                .put("type", "SUBGRAPH").put("status", "SUCCESS");
        rebuilt.set("nodes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("code", "play_design").put("type", "AGENT")
                        .put("status", "SUCCESS"))
                .add(frame));

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        JsonNode files = frame.get("files");
        assertTrue(files instanceof ArrayNode, "父图子图节点要给出可下载入口：" + rebuilt);
        assertEquals("guess-number.html", files.get(0).path("fileName").asText());
    }

    /**
     * 回落到父框时同名文件不重复：父框自己已经带的那份优先，骨架只补缺的名字。
     */
    @Test
    @DisplayName("回落到父框时同名文件不重复")
    void bubblesWithoutDuplicatingSameFile() {
        ObjectNode skeleton = skeletonWithFiles();
        ObjectNode rebuilt = JSON.createObjectNode();
        ObjectNode frame = JSON.createObjectNode().put("code", "impl_check")
                .put("type", "SUBGRAPH").put("status", "SUCCESS");
        frame.set("files", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("fileName", "guess-number.html").put("size", 2048L)));
        rebuilt.set("nodes", JSON.createArrayNode().add(frame));

        WorkflowCardRebuilder.carryNodeFiles(skeleton, rebuilt);

        assertEquals(1, frame.path("files").size(), "同名文件只留一行");
        assertEquals(2048L, frame.path("files").get(0).path("size").asLong(),
                "父框自己那份不被旧快照的体积盖掉");
    }

    /** 收尾骨架：父图 SUBGRAPH 节点 + 子图内部两个节点，只有实现节点带文件行。 */
    private static ObjectNode skeletonWithFiles() {
        ObjectNode body = JSON.createObjectNode();
        body.set("nodes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("code", "play_design").put("type", "AGENT")
                        .put("status", "SUCCESS"))
                .add(JSON.createObjectNode().put("code", "impl_check").put("type", "SUBGRAPH")
                        .put("status", "SUCCESS"))
                .add(node("x8f9629e7_impl_agent", 1, "guess-number.html", 11586L)
                        .put("group", "impl_check"))
                .add(JSON.createObjectNode().put("code", "x8f9629e7_check_agent").put("type", "AGENT")
                        .put("status", "SUCCESS").put("group", "impl_check")));
        return body;
    }

    /** 重建后的卡片：同样的节点，但没有任何 files（按库现算算不出文件）。 */
    private static ObjectNode rebuiltWithoutFiles() {
        ObjectNode body = JSON.createObjectNode();
        body.set("nodes", JSON.createArrayNode()
                .add(JSON.createObjectNode().put("code", "play_design").put("type", "AGENT")
                        .put("status", "SUCCESS"))
                .add(JSON.createObjectNode().put("code", "impl_check").put("type", "SUBGRAPH")
                        .put("status", "SUCCESS"))
                .add(JSON.createObjectNode().put("code", "x8f9629e7_impl_agent").put("type", "AGENT")
                        .put("status", "SUCCESS").put("group", "impl_check"))
                .add(JSON.createObjectNode().put("code", "x8f9629e7_check_agent").put("type", "AGENT")
                        .put("status", "SUCCESS").put("group", "impl_check")));
        return body;
    }

    /**
     * 造一个带文件行的卡片节点。
     *
     * @param code     节点编码（子图内部带范围前缀）
     * @param attempt  轮次
     * @param fileName 文件名
     * @param size     体积
     * @return 节点对象
     */
    private static ObjectNode node(String code, int attempt, String fileName, long size) {
        ObjectNode node = JSON.createObjectNode();
        node.put("code", code);
        node.put("type", "AGENT");
        node.put("status", "SUCCESS");
        if (attempt > 1) {
            node.put("attempt", attempt);
        }
        node.set("files", JSON.createArrayNode().add(JSON.createObjectNode()
                .put("fileName", fileName).put("size", size)));
        return node;
    }

    /**
     * 按编码取节点。
     *
     * @param body 卡片 JSON
     * @param code 节点编码
     * @return 节点；没有为 null
     */
    private static JsonNode nodeOf(ObjectNode body, String code) {
        JsonNode nodes = body.get("nodes");
        if (!(nodes instanceof ArrayNode list)) {
            return null;
        }
        for (JsonNode item : list) {
            if (code.equals(item.path("code").asText())) {
                return item;
            }
        }
        return null;
    }
}
