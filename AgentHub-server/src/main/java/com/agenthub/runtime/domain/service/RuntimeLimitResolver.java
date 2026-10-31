package com.agenthub.runtime.domain.service;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.infra.agentscope.RuntimeLimitsProperties;
import org.springframework.stereotype.Component;

/**
 * 解析执行生效限制。所属域：runtime。
 * 空覆盖跟 YAML 默认；生效再与硬顶取 min。保存侧另有 ≤ 硬顶校验。
 */
@Component
public class RuntimeLimitResolver {

    private final RuntimeLimitsProperties properties;

    /**
     * @param properties 平台默认与硬顶
     */
    public RuntimeLimitResolver(RuntimeLimitsProperties properties) {
        this.properties = properties;
    }

    /**
     * @param agent 可空；空则全用平台默认
     * @return 生效值
     */
    public EffectiveLimits resolve(AgentDef agent) {
        RuntimeLimitsProperties p = properties;
        return new EffectiveLimits(
                pickLong(agent == null ? null : agent.getTimeoutMs(), p.getTimeoutMs(), 1000L),
                (int) pickLong(agent == null ? null : toLong(agent.getMaxRounds()), p.getMaxRounds(), 1L),
                pickLong(agent == null ? null : agent.getToolTimeoutMs(), p.getToolTimeoutMs(), 1000L),
                (int) pickLong(agent == null ? null : toLong(agent.getToolMaxRetries()), p.getToolMaxRetries(), 0L),
                (int) pickLong(agent == null ? null : toLong(agent.getMaxToolCalls()), p.getMaxToolCalls(), 1L),
                pickLong(agent == null ? null : agent.getLlmTimeoutMs(), p.getLlmTimeoutMs(), 1000L),
                (int) pickLong(agent == null ? null : toLong(agent.getMaxLlmCalls()), p.getMaxLlmCalls(), 1L),
                (int) pickLong(agent == null ? null : toLong(agent.getConsecutiveFailAbort()),
                        p.getConsecutiveFailAbort(), 1L),
                (int) pickLong(agent == null ? null : toLong(agent.getToolResultMaxChars()),
                        p.getToolResultMaxChars(), 256L)
        );
    }

    /**
     * 给配置页占位：平台默认与硬顶。
     *
     * @return 当前 YAML 绑定
     */
    public RuntimeLimitsProperties properties() {
        return properties;
    }

    private static long pickLong(Long agentValue, RuntimeLimitsProperties.Bound bound, long floor) {
        long base = agentValue == null ? bound.getDefault() : agentValue;
        long capped = Math.min(base, bound.getMax());
        return Math.max(floor, capped);
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}
