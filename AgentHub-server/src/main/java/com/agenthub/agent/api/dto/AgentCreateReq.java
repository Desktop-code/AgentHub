package com.agenthub.agent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建 Agent。所属域：agent。未传运行模式时由应用层默认 REACT，启停固定为正常。
 */
@Data
public class AgentCreateReq {

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String name;

    @NotBlank(message = "不能为空")
    @Size(max = 64, message = "最长 64")
    private String code;

    @Size(max = 1000, message = "最长 1000")
    private String description;

    @Size(max = 32, message = "最长 32")
    private String runMode;

    /** 用户业务段。按 Java char 计数，超过绝对上限直接 400 */
    @Size(max = 12000, message = "超过长度，最长 12000")
    private String systemPrompt;

    /** L3 提示词挂接（CHAT → agent_def 四列）；可空表示新建时不挂模板 */
    private PromptBindingReq prompt;

    @Size(max = 500, message = "最长 500")
    private String remark;

    /** 运行限制，均可空；空=跟平台 YAML 默认；有值须 ≤ 平台硬顶 */
    private Long timeoutMs;
    private Integer maxRounds;
    private Long toolTimeoutMs;
    private Integer toolMaxRetries;
    private Integer maxToolCalls;
    private Long llmTimeoutMs;
    private Integer maxLlmCalls;
    private Integer consecutiveFailAbort;
    private Integer toolResultMaxChars;

    /** Router 等运行配置；可空，空则按模式默认写入 */
    private AgentRuntimeConfigReq runtimeConfig;
}
