package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子图范围的节点 / 边隔离与展示编码换算。所属域：workflow 的 application。
 *
 * <p>职责：子图与父图可能取同名节点 id / code（例如两边都有 {@code answer}）。
 * 引擎按节点 id 存运行行，投影按 code 分组，直接复用原值会互相覆盖。
 * 这里给每层子图加一个稳定前缀（父链路 + 子图节点 id#轮次 的短哈希），
 * 让所有运行行与视图键唯一；原编码另存一份用于展示。
 *
 * <p>关键约束：
 * <ul>
 *   <li>前缀只影响内部 id / code 与运行行，不写回发布快照，也不改变用户的映射路径模型；</li>
 *   <li>同一父链路 + 同一子图节点 + 同一轮次，前缀必须稳定，刷新 / 回放才能按同一键还原；</li>
 *   <li>展示编码去掉前缀即可还原，不另造一套编码表。</li>
 * </ul>
 */
public final class WorkflowScopeGraphs {

    private static final int HASH_LEN = 8;

    /**
     * 运行行 node_id / node_code 的列宽（{@code varchar(64)}）。
     *
     * <p>范围前缀固定 10 个字符；节点编码在画布上最多允许 64 个字符，直接拼接会超列宽。
     * 这里先按列宽收口，尾部补短哈希，避免数据库截断后同一层里两个长编码撞名。
     */
    private static final int MAX_SYNTHETIC_LEN = 64;

    private WorkflowScopeGraphs() {
    }

    /**
     * 由范围键生成稳定前缀：{@code x + 8 位十六进制 + "_"}。
     *
     * @param key 范围键；空值按空串处理
     * @return 前缀；始终非空（空键也会得到 {@code x00000000_}）
     */
    public static String prefixOf(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < HASH_LEN / 2; index += 1) {
                hex.append(String.format("%02x", bytes[index]));
            }
            return "x" + hex + "_";
        } catch (NoSuchAlgorithmException ex) {
            // JDK 17 必有 SHA-256；真缺失时用范围键本身兜底，宁可键长也不静默撞名
            return "x" + Integer.toHexString((key == null ? "" : key).hashCode()) + "_";
        }
    }

    /**
     * 给范围里的原始句柄加前缀。
     *
     * @param prefix 范围前缀
     * @param raw    原始 id / code；可空
     * @return 加前缀后的值；空值原样返回空串
     */
    public static String synthetic(String prefix, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        String value = (prefix == null ? "" : prefix) + raw;
        if (value.length() <= MAX_SYNTHETIC_LEN) {
            return value;
        }
        /*
         * 超列宽：保留「前缀 + 原值前段 + ~ + 8 位短哈希」。
         * 前缀已保证跨范围唯一，短哈希只负责同一层内长编码截断后不互相覆盖。
         */
        String safePrefix = prefix == null ? "" : prefix;
        String digest = shortHex(raw);
        int keep = Math.max(0, MAX_SYNTHETIC_LEN - safePrefix.length() - digest.length() - 1);
        return safePrefix + raw.substring(0, Math.min(raw.length(), keep)) + "~" + digest;
    }

    /**
     * 原始值的 8 位短哈希：只用于长编码收口，不作为安全摘要。
     *
     * @param raw 原始 id / code
     * @return 8 位十六进制；算法不可用时退回 hashCode
     */
    private static String shortHex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < HASH_LEN / 2; index += 1) {
                hex.append(String.format("%02x", bytes[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * 去掉范围前缀，还原用户原始编码。
     *
     * @param prefix 范围前缀
     * @param value  可能带前缀的 id / code / 文案
     * @return 还原后的值；没有前缀时原样返回
     */
    public static String unprefix(String prefix, String value) {
        if (value == null || value.isEmpty() || prefix == null || prefix.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.replace(prefix, "");
    }

    /**
     * 复制一张图并给节点 id / code 加范围前缀；边 id 不变，source / target 改成前缀后的 id。
     *
     * <p>节点展示名保持用户原文；没有名字的节点用原始 code 兜底，避免步骤卡只剩一串前缀。
     *
     * @param raw    发布快照里的图
     * @param prefix 范围前缀
     * @return 新的图；不修改入参
     */
    public static WorkflowGraph transform(WorkflowGraph raw, String prefix) {
        WorkflowGraph graph = new WorkflowGraph();
        if (raw == null) {
            return graph;
        }
        graph.setRunMinutes(raw.getRunMinutes());
        graph.setNodeMinutes(raw.getNodeMinutes());
        Map<String, String> rawIdBySynthetic = new LinkedHashMap<>();
        List<WorkflowNode> nodes = new ArrayList<>();
        for (WorkflowNode rawNode : raw.getNodes() == null ? List.<WorkflowNode>of() : raw.getNodes()) {
            if (rawNode == null) {
                continue;
            }
            String rawId = rawNode.getId();
            WorkflowNode node = copyNode(rawNode);
            node.setId(synthetic(prefix, rawId));
            node.setCode(synthetic(prefix, rawNode.getCode()));
            if (node.getName() == null || node.getName().isBlank()) {
                node.setName(rawNode.getCode() == null || rawNode.getCode().isBlank()
                        ? rawId : rawNode.getCode());
            }
            nodes.add(node);
            if (rawId != null) {
                rawIdBySynthetic.put(node.getId(), rawId);
            }
        }
        graph.setNodes(nodes);
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge rawEdge : raw.getEdges() == null ? List.<WorkflowEdge>of() : raw.getEdges()) {
            if (rawEdge == null) {
                continue;
            }
            WorkflowEdge edge = new WorkflowEdge();
            edge.setId(rawEdge.getId());
            edge.setSource(synthetic(prefix, rawEdge.getSource()));
            edge.setTarget(synthetic(prefix, rawEdge.getTarget()));
            edge.setType(rawEdge.getType());
            edge.setLabel(rawEdge.getLabel());
            edge.setConfig(rawEdge.getConfig());
            edges.add(edge);
        }
        graph.setEdges(edges);
        return graph;
    }

    /**
     * 节点 id → 原始 id / code 的映射（原图口径）。
     *
     * <p>投影层只需要「前缀后的 id → 原始 code」；由调用方按同一前缀自行生成。
     *
     * @param raw    原图
     * @param prefix 范围前缀
     * @return 前缀后的节点 id → 原始 code
     */
    public static Map<String, String> displayCodes(WorkflowGraph raw, String prefix) {
        Map<String, String> codes = new LinkedHashMap<>();
        if (raw == null) {
            return codes;
        }
        for (WorkflowNode node : raw.getNodes() == null ? List.<WorkflowNode>of() : raw.getNodes()) {
            if (node == null || node.getId() == null) {
                continue;
            }
            codes.put(synthetic(prefix, node.getId()),
                    node.getCode() == null ? "" : node.getCode());
        }
        return codes;
    }

    /**
     * 替换节点展示名（用于把父图 SUBGRAPH 节点的名字换成「被调工作流名 · vN」）。
     *
     * @param node 原节点
     * @param name 新展示名；空值原样保留
     * @return 节点副本；不改入参
     */
    public static WorkflowNode withName(WorkflowNode node, String name) {
        WorkflowNode copy = copyNode(node);
        if (name != null && !name.isBlank()) {
            copy.setName(name.trim());
        }
        return copy;
    }

    private static WorkflowNode copyNode(WorkflowNode source) {
        WorkflowNode node = new WorkflowNode();
        node.setId(source.getId());
        node.setCode(source.getCode());
        node.setType(source.getType());
        node.setName(source.getName());
        node.setConfig(source.getConfig());
        node.setUiX(source.getUiX());
        node.setUiY(source.getUiY());
        return node;
    }
}
