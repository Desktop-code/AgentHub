package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 发布依赖指纹。所属域：workflow 的 domain。
 * <p>算法：收集图中所有 AGENT.config.agentId 与 TOOL.config.toolId，按
 * {@code 类型|节点code|目标id} 生成一行，整体排序后用换行拼成规范串，
 * 再做 SHA-256，取小写十六进制 64 位。
 * <p>无依赖时对固定串 {@code WORKFLOW|NO_DEPS} 做哈希，保证结果稳定且非空。
 * <p>为什么按节点 code 排序而不是按数组顺序：画布拖动/序列化顺序变化不应让指纹漂移。
 */
public final class WorkflowFingerprint {

    /** 无依赖时的固定规范串，改动它等于变更指纹算法 */
    private static final String NO_DEPS = "WORKFLOW|NO_DEPS";

    private WorkflowFingerprint() {
    }

    /**
     * 计算图依赖指纹。
     *
     * @param graph 图；null 按无依赖处理
     * @return 64 位小写十六进制 SHA-256
     */
    public static String of(WorkflowGraph graph) {
        List<String> parts = new ArrayList<>();
        if (graph != null && graph.getNodes() != null) {
            for (WorkflowNode node : graph.getNodes()) {
                if (node == null) {
                    continue;
                }
                WorkflowNodeType type = WorkflowNodeType.parse(node.getType());
                String code = node.getCode() == null ? "" : node.getCode().trim();
                if (type == WorkflowNodeType.AGENT) {
                    Long agentId = WorkflowNodeConfig.longValue(node.getConfig(), "agentId");
                    if (agentId != null) {
                        parts.add("AGENT|" + code + "|" + agentId);
                    }
                } else if (type == WorkflowNodeType.TOOL) {
                    Long toolId = WorkflowNodeConfig.longValue(node.getConfig(), "toolId");
                    if (toolId != null) {
                        parts.add("TOOL|" + code + "|" + toolId);
                    }
                }
            }
        }
        Collections.sort(parts);
        String canonical = parts.isEmpty() ? NO_DEPS : String.join("\n", parts);
        return sha256Hex(canonical);
    }

    /**
     * SHA-256 十六进制。
     *
     * @param text 明文
     * @return 小写 64 位十六进制
     */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 17 必然内置 SHA-256；兜底抛 IllegalStateException，避免返回伪造指纹
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }
}
