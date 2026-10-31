package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.infra.agentscope.RuntimeLimitsProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行限制保存校验。所属域：agent。
 * 空表示跟平台；有值必须落在 [下限, 平台硬顶]。
 */
@Component
public class AgentLimitValidator {

    private final RuntimeLimitsProperties limits;

    /**
     * @param limits 平台默认与硬顶
     */
    public AgentLimitValidator(RuntimeLimitsProperties limits) {
        this.limits = limits;
    }

    /**
     * 把请求里的可空覆盖写入定义，并按平台硬顶校验。
     *
     * @param agent                   目标定义
     * @param timeoutMs               墙钟
     * @param maxRounds               轮次
     * @param toolTimeoutMs           工具超时
     * @param toolMaxRetries          工具重试
     * @param maxToolCalls            工具次数
     * @param llmTimeoutMs            LLM 超时
     * @param maxLlmCalls             LLM 次数
     * @param consecutiveFailAbort    连续失败
     * @param toolResultMaxChars      工具结果截断
     */
    public void apply(AgentDef agent, Long timeoutMs, Integer maxRounds, Long toolTimeoutMs,
                      Integer toolMaxRetries, Integer maxToolCalls, Long llmTimeoutMs,
                      Integer maxLlmCalls, Integer consecutiveFailAbort, Integer toolResultMaxChars) {
        agent.setTimeoutMs(checkLong("timeoutMs", timeoutMs, limits.getTimeoutMs(), 1000L));
        agent.setMaxRounds(checkInt("maxRounds", maxRounds, limits.getMaxRounds(), 1));
        agent.setToolTimeoutMs(checkLong("toolTimeoutMs", toolTimeoutMs, limits.getToolTimeoutMs(), 1000L));
        agent.setToolMaxRetries(checkInt("toolMaxRetries", toolMaxRetries, limits.getToolMaxRetries(), 0));
        agent.setMaxToolCalls(checkInt("maxToolCalls", maxToolCalls, limits.getMaxToolCalls(), 1));
        agent.setLlmTimeoutMs(checkLong("llmTimeoutMs", llmTimeoutMs, limits.getLlmTimeoutMs(), 1000L));
        agent.setMaxLlmCalls(checkInt("maxLlmCalls", maxLlmCalls, limits.getMaxLlmCalls(), 1));
        agent.setConsecutiveFailAbort(checkInt("consecutiveFailAbort", consecutiveFailAbort,
                limits.getConsecutiveFailAbort(), 1));
        agent.setToolResultMaxChars(checkInt("toolResultMaxChars", toolResultMaxChars,
                limits.getToolResultMaxChars(), 256));
    }

    private Long checkLong(String field, Long raw, RuntimeLimitsProperties.Bound bound, long floor) {
        if (raw == null) {
            return null;
        }
        if (raw < floor || raw > bound.getMax()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    field + " 超出平台硬顶：允许 " + floor + "～" + bound.getMax() + "（当前 " + raw + "）");
        }
        return raw;
    }

    private Integer checkInt(String field, Integer raw, RuntimeLimitsProperties.Bound bound, int floor) {
        if (raw == null) {
            return null;
        }
        long max = bound.getMax();
        if (raw < floor || raw > max) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    field + " 超出平台硬顶：允许 " + floor + "～" + max + "（当前 " + raw + "）");
        }
        return raw;
    }
}
