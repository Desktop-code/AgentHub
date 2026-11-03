package com.agenthub.eval.domain.service;

import com.agenthub.agent.domain.model.AgentDef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Agent 快照指纹。所属域：eval 的 domain。纯函数，无 IO。
 * <p>本刀（2b）最小指纹：agentId + updateTime + runtimeConfigJson 的稳定 SHA-256 十六进制前 32 位。
 * 算不出细节时退化为 {@code agent:{id}}，保证 execute 后 hash 非 null（开场【降级】）。
 * 线程：无状态。
 */
public final class EvalAgentSnapshot {

    private EvalAgentSnapshot() {
    }

    /**
     * @param agent Agent 定义，可空
     * @param agentId 请求里的 agentId（agent 为空时兜底）
     * @return 非空指纹字符串
     */
    public static String hash(AgentDef agent, Long agentId) {
        if (agent == null) {
            return "agent:" + (agentId == null ? "0" : agentId);
        }
        Long id = agent.getId() == null ? agentId : agent.getId();
        LocalDateTime updated = agent.getUpdateTime();
        String runtime = agent.getRuntimeConfigJson() == null ? "" : agent.getRuntimeConfigJson();
        String material = "id=" + id
                + "|updated=" + (updated == null ? "" : updated)
                + "|runtime=" + runtime
                + "|mode=" + (agent.getRunMode() == null ? "" : agent.getRunMode())
                + "|promptTpl=" + agent.getPromptTemplateId()
                + "|tplVer=" + agent.getTemplateVersion();
        return sha256Prefix(material, 32);
    }

    private static String sha256Prefix(String material, int hexLen) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(bytes);
            return hex.substring(0, Math.min(hexLen, hex.length()));
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必有 SHA-256；万一没有就退回原料哈希码，仍保证非 null
            return "fb:" + Integer.toHexString(material.hashCode());
        }
    }
}
