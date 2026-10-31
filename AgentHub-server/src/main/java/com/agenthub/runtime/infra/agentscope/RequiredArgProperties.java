package com.agenthub.runtime.infra.agentscope;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具必填参数闸门的开关与实体字段口径。所属域：runtime。对应 agenthub.runtime.tool-args。
 * 默认开启，符合「缺必填不得脑补」的产品要求；仅在排障时允许临时关闭。
 */
@Data
@ConfigurationProperties(prefix = "agenthub.runtime.tool-args")
public class RequiredArgProperties {

    /** 是否启用硬拦。关闭后回到「只靠提示词」的旧行为，不建议长期关闭 */
    private boolean enabled = true;

    /**
     * 需要校验「取值有无依据」的参数名（小写下划线）。缺省为城市/地点/时区类；
     * 值来自本轮提问、会话上下文或已注入长期记忆时才放行，否则按编造拦下。
     */
    private Set<String> entityParamNames = new LinkedHashSet<>();

    /** 需要校验依据的规范事实键。与 {@link #entityParamNames} 取并集 */
    private Set<String> entityFactKeys = new LinkedHashSet<>();
}
