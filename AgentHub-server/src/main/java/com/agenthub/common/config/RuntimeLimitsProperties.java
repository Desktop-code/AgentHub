package com.agenthub.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台运行限制硬顶与默认。所属域：common（配置类，无业务逻辑）。
 * 对应 agenthub.runtime.limits；Agent 可空覆盖，生效取 min(Agent或默认, max)。
 * <p>
 * 位置说明：本类只被 agent（校验 Agent 覆盖值）与 runtime（解析生效值）读取。
 * 放在 common/config 是为了让 agent 不再依赖 runtime.infra，消除包级双向依赖；
 * 前缀与 YAML 键 {@code agenthub.runtime.limits} 保持不变，仍由 RuntimeInfrastructure 的
 * {@code @EnableConfigurationProperties} 注册。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agenthub.runtime.limits")
public class RuntimeLimitsProperties {

    /** 整次墙钟超时（毫秒） */
    private Bound timeoutMs = Bound.of(120_000L, 300_000L);

    /** ReAct 最大推理轮次 */
    private Bound maxRounds = Bound.of(8L, 32L);

    /** 单次工具执行超时（毫秒） */
    private Bound toolTimeoutMs = Bound.of(30_000L, 120_000L);

    /** 单次工具失败后的额外重试次数（不含首次） */
    private Bound toolMaxRetries = Bound.of(0L, 3L);

    /** 单次试跑内工具调用次数上限 */
    private Bound maxToolCalls = Bound.of(32L, 64L);

    /** 单次 LLM 调用超时（毫秒） */
    private Bound llmTimeoutMs = Bound.of(60_000L, 120_000L);

    /** 单次试跑内 LLM 调用次数上限 */
    private Bound maxLlmCalls = Bound.of(32L, 64L);

    /** 连续工具失败达到该次数后中止 */
    private Bound consecutiveFailAbort = Bound.of(3L, 10L);

    /** 回灌进模型的工具结果最大字符数 */
    private Bound toolResultMaxChars = Bound.of(8_000L, 32_000L);

    /**
     * 单项默认值 + 硬顶。YAML 键为 default / max。
     */
    @Getter
    @Setter
    public static class Bound {

        /** 平台默认（Agent 该列为空时用） */
        private long defaultValue;

        /** 平台硬顶；Agent 覆盖不得超过 */
        private long max;

        /**
         * @param defaultValue 默认
         * @param max          硬顶
         * @return 绑定对象
         */
        public static Bound of(long defaultValue, long max) {
            Bound bound = new Bound();
            bound.defaultValue = defaultValue;
            bound.max = max;
            return bound;
        }

        /**
         * YAML 键名 {@code default} 映射到此属性。
         *
         * @return 默认值
         */
        public long getDefault() {
            return defaultValue;
        }

        /**
         * @param defaultValue YAML default
         */
        public void setDefault(long defaultValue) {
            this.defaultValue = defaultValue;
        }
    }
}
