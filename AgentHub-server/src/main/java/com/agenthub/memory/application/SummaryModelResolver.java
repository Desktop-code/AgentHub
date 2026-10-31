package com.agenthub.memory.application;

import com.agenthub.agent.application.PurposeModelResolver;
import com.agenthub.agent.domain.model.PurposeResolveResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.model.domain.model.ModelCapability;
import org.springframework.stereotype.Component;

/**
 * 摘要与记忆抽取模型解析。所属域：memory。
 * SUMMARY 只做滚动摘要 / 窗口压缩；MEMORY 只做 LONG / 显式记住抽取。
 * 二者互不借用；MEMORY 未配时可降 CHAT，不降 SUMMARY。
 */
@Component
public class SummaryModelResolver {

    private final PurposeModelResolver purposes;
    private final MemoryProperties properties;

    /**
     * @param purposes   用途槽解析
     * @param properties YAML 兜底（SUMMARY / MEMORY 分键）
     */
    public SummaryModelResolver(PurposeModelResolver purposes, MemoryProperties properties) {
        this.purposes = purposes;
        this.properties = properties;
    }

    /**
     * 滚动摘要模型：策略首选 → SUMMARY 槽 → 平台 SUMMARY → YAML → 可降 CHAT。
     *
     * @param policy  策略，可空字段
     * @param agentId 当前 Agent
     * @return 可用实例 id
     * @throws BizException 链上全无可用实例
     */
    public long requireSummary(MemoryPolicy policy, Long agentId) {
        Long preferred = policy == null ? null : policy.getSummaryModelInstanceId();
        return purposes.resolve(agentId, ModelCapability.SUMMARY, preferred,
                        properties.fallbackInstanceId(), true)
                .map(PurposeResolveResult::getInstanceId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有可用的摘要模型"));
    }

    /**
     * 记忆抽取模型：策略首选 → MEMORY 槽 → 平台 MEMORY → YAML → 可降 CHAT。
     * 禁止默认走 SUMMARY。
     *
     * @param policy  策略
     * @param agentId Agent
     * @return 可用实例 id
     * @throws BizException 没有可用实例
     */
    public long requireExtract(MemoryPolicy policy, Long agentId) {
        Long preferred = policy == null ? null : policy.getLongExtractModelInstanceId();
        return purposes.resolve(agentId, ModelCapability.MEMORY, preferred,
                        properties.memoryFallbackInstanceId(), true)
                .map(PurposeResolveResult::getInstanceId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有可用的记忆抽取模型"));
    }

    /**
     * 带观测字段的摘要解析（试跑 / 调试用）。
     *
     * @param policy  策略
     * @param agentId Agent
     * @return 解析结果
     */
    public PurposeResolveResult resolveSummaryDetail(MemoryPolicy policy, Long agentId) {
        Long preferred = policy == null ? null : policy.getSummaryModelInstanceId();
        return purposes.resolve(agentId, ModelCapability.SUMMARY, preferred,
                        properties.fallbackInstanceId(), true)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有可用的摘要模型"));
    }

    /**
     * 带观测字段的抽取解析。
     *
     * @param policy  策略
     * @param agentId Agent
     * @return 解析结果
     */
    public PurposeResolveResult resolveExtractDetail(MemoryPolicy policy, Long agentId) {
        Long preferred = policy == null ? null : policy.getLongExtractModelInstanceId();
        return purposes.resolve(agentId, ModelCapability.MEMORY, preferred,
                        properties.memoryFallbackInstanceId(), true)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有可用的记忆抽取模型"));
    }
}
