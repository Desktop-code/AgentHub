package com.agenthub.memory.infra;

import com.agenthub.memory.domain.model.MemoryPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆与窗口的平台默认和硬顶。所属域：memory。
 * Agent 策略可以更小，不能超过这里的 max。摘要兜底实例只在三级解析都空时使用。
 * 批抽总闸 {@code longExtractBatchEnabled} 默认 false，用于一键关死 session_end/summary 出站。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.memory")
public class MemoryProperties {

    private int shortDefaultTtlSec = 86400;
    private int shortDefaultTtlMaxSec = 604800;
    /**
     * 近轮滑动窗：最多保留多少轮（user + assistant 各算一轮）。
     * <p>
     * 默认从 20 降到 12：每轮都要在每轮对话里重新出站，20 轮往往只是把同一段历史反复计费；
     * 更早的内容已由滚动摘要覆盖（摘要与近轮已去重，不会双份）。
     */
    private int ctxMaxTurns = 12;
    private int ctxMaxTurnsCap = 50;
    /**
     * 近轮滑动窗的字符上限。默认从 32000 降到 16000：
     * 32000 字已接近多数模型的上下文窗口大头，留给系统提示与工具结果的空间会被压到很小。
     */
    private int ctxMaxChars = 16000;
    private int ctxMaxCharsCap = 64000;
    private int ctxIdleTtlSec = 7200;
    private int ctxIdleTtlMaxSec = 86400;
    private int recallMaxChars = 4000;
    /** 空字符串表示没有 YAML 兜底（SUMMARY） */
    private String summaryFallbackInstanceId = "";
    /** MEMORY 抽取环境兜底；空表示无 */
    private String memoryFallbackInstanceId = "";
    private long cleanupDelayMs = 3_600_000L;
    /**
     * 批抽出站总闸（session_end / summary）。默认 false：一键关死出站批抽，不影响 explicit「记住」。
     * 生产止血：保持 false；确认策略与模型可用后再显式打开。
     */
    private boolean longExtractBatchEnabled = false;
    /**
     * 单次 CloseJob 轮次最多收口并批抽的空闲会话数。默认 5，串行处理。
     */
    private int sessionCloseBatchLimit = 5;
    /**
     * 会话级批抽最短正文字符；低于则 skip 且不调模型。
     */
    private int longExtractMinChars = 32;
    /**
     * 两次批抽之间的间隔毫秒（CloseJob 串行节流）。0=不额外 sleep。
     */
    private long longExtractIntervalMs = 200L;
    /**
     * fact_key 同义扩展：别名 → 规范键。与内置表合并，同名覆盖。
     * 例：{@code living_place: residence_city}
     */
    private Map<String, String> factKeyAliases = new LinkedHashMap<>();

    /**
     * 没有策略行时按平台默认。长期记忆与批抽默认关，避免没配置就抽偏好。
     *
     * @param agentId Agent
     * @return 未入库的有效策略
     */
    public MemoryPolicy defaults(Long agentId) {
        MemoryPolicy policy = new MemoryPolicy();
        policy.setAgentId(agentId);
        policy.setEnableShort(1);
        policy.setEnableLong(0);
        policy.setMaxShortItems(20);
        policy.setMaxLongItems(100);
        policy.setCtxMaxTurns(Math.min(ctxMaxTurns, ctxMaxTurnsCap));
        policy.setCtxMaxChars(Math.min(ctxMaxChars, ctxMaxCharsCap));
        policy.setCtxIdleTtlSec(Math.min(ctxIdleTtlSec, ctxIdleTtlMaxSec));
        policy.setShortDefaultTtlSec(Math.min(shortDefaultTtlSec, shortDefaultTtlMaxSec));
        // 批抽默认关；显式记住仍可走 long_extract_on_explicit（不依赖本开关）
        policy.setEnableLongExtract(0);
        policy.setLongExtractOnSessionEnd(1);
        policy.setLongExtractOnExplicit(1);
        policy.setStatus(1);
        return policy;
    }

    /**
     * 运行时读取已保存的策略时压到硬顶，避免库里的旧值超过平台上限。
     *
     * @param policy 库中的策略
     * @return 同一对象
     */
    public MemoryPolicy clamp(MemoryPolicy policy) {
        policy.setCtxMaxTurns(cap(policy.getCtxMaxTurns(), 1, ctxMaxTurnsCap));
        policy.setCtxMaxChars(cap(policy.getCtxMaxChars(), 256, ctxMaxCharsCap));
        policy.setCtxIdleTtlSec(cap(policy.getCtxIdleTtlSec(), 60, ctxIdleTtlMaxSec));
        policy.setShortDefaultTtlSec(cap(policy.getShortDefaultTtlSec(), 60, shortDefaultTtlMaxSec));
        if (policy.getMaxShortItems() == null || policy.getMaxShortItems() < 1) {
            policy.setMaxShortItems(20);
        }
        if (policy.getMaxLongItems() == null || policy.getMaxLongItems() < 1) {
            policy.setMaxLongItems(100);
        }
        return policy;
    }

    /**
     * @return SUMMARY 的 YAML 兜底实例；没配则为 null
     */
    public Long fallbackInstanceId() {
        return parseId(summaryFallbackInstanceId);
    }

    /**
     * @return MEMORY 抽取的 YAML 兜底实例；没配则为 null
     */
    public Long memoryFallbackInstanceId() {
        return parseId(memoryFallbackInstanceId);
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int cap(Integer raw, int min, int max) {
        int value = raw == null ? min : raw;
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
