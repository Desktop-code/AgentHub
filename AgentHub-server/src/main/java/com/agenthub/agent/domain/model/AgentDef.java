package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 定义。所属域：agent。对应 agent_def。
 * 这里不保存模型 Key；出站密钥仍在模型域。
 */
@Data
public class AgentDef {

    private Long id;
    private String name;
    private String code;
    private String description;
    /** REACT 或 ROUTER */
    private String runMode;
    /** 用户业务段。平台前缀不在此字段，试跑时由组装器加在前面 */
    private String systemPrompt;
    /** CHAT 引用的 L2 模板 id；空=按用途取当前生效版 */
    private Long promptTemplateId;
    /** 钉死的 L2 版本号；平台发新版不静默升级 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON：seg_key → 文案 */
    private String promptOverrideJson;
    /** L3 追加区（append-only，不可覆盖锁定段） */
    private String promptExtra;
    /** 运行限制覆盖，均可空；空=跟 agenthub.runtime.limits 默认 */
    private Long timeoutMs;
    private Integer maxRounds;
    private Long toolTimeoutMs;
    private Integer toolMaxRetries;
    private Integer maxToolCalls;
    private Long llmTimeoutMs;
    private Integer maxLlmCalls;
    private Integer consecutiveFailAbort;
    private Integer toolResultMaxChars;
    /**
     * 运行配置 JSON（多意图 / 终答模式等）。可空；ROUTER 空则运行时用默认。
     */
    private String runtimeConfigJson;
    private Integer status;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
