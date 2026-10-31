package com.agenthub.runtime.infra.agentscope;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 试跑落库与历史参数。所属域：runtime。对应 agenthub.runtime.trial。
 * 轮次/超时已收拢到 agenthub.runtime.limits，见 {@link RuntimeLimitsProperties}。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.runtime.trial")
public class TrialProperties {

    /** content_text 单条字符上限。超出截断并注明已截断 */
    private int contentLimitChars = 32768;

    /** 历史会话条数上限。只查当前用户和指定 Agent，按 started_at 倒序 */
    private int historyLimit = 20;
}
