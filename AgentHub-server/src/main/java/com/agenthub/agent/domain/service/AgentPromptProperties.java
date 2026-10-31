package com.agenthub.agent.domain.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 系统提示词治理参数。所属域：agent。
 * 对应 agenthub.agent。前缀文案在 classpath 资源里，不写入 agent_def。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.agent")
public class AgentPromptProperties {

    /** 关闭后试跑不再强制前缀。默认开启，避免用户段把平台约束盖掉 */
    private boolean promptPrefixEnabled = true;

    /** 平台前缀资源。只允许改这一处文案路径 */
    private String promptPrefixPath = "classpath:prompt/platform-prefix-zh.txt";

    /** ROUTER 在前缀后追加的一句，仍不落库 */
    private String promptRouterExtraPath = "classpath:prompt/platform-prefix-router-zh.txt";

    /**
     * @return ROUTER 追加句资源路径；空白时返回空串（由调用方退回内置默认）
     */
    public String getRouterExtraPathOrDefault() {
        return promptRouterExtraPath == null ? "" : promptRouterExtraPath.trim();
    }

    /**
     * 用户业务段上限，按 Java char（String.length，UTF-16 码元）计数。
     * 大于 {@link com.agenthub.agent.application.AgentChecks#PROMPT_MAX} 时仍按绝对上限拒绝。
     */
    private int promptMaxChars = 12_000;

    /** true 时「覆盖系统规则 / 外连诱导」从告警升为拒绝。默认只告警，保存仍成功 */
    private boolean promptWarnAsError = false;
}
