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
    /**
     * 是否允许被编排挂为子专家。1=允许，0=禁止，可空（旧数据/未迁移时按模式兜底）。
     * <p>ROUTER 恒为禁止；其它模式由配置页开关决定，默认允许。
     */
    private Integer allowOrchestrated;
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
    /**
     * 逻辑删除标记：0 未删，1 已删。
     * 普通 findById 恒为未删；仅 findByIdIncludingDeleted 可能带回 1，供用量历史拼名。
     */
    private Integer deleted;
    private String remark;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;

    /**
     * 是否允许被编排挂为子专家（运行期/保存校验统一走这里）。
     * <p>ROUTER 恒为 false：编排根不能作子，与 {@code AgentRoutePolicy} 的「子必须 REACT」同口径。
     * <p>列可空时非 ROUTER 视为允许：兼容加列前读到的旧对象，避免存量 REACT 子被误判成私有。
     *
     * @return true 表示可以被编排根选为候选
     */
    public boolean allowOrchestratedOn() {
        if (AgentRunMode.ROUTER.name().equals(runMode)) {
            return false;
        }
        return allowOrchestrated == null || allowOrchestrated != 0;
    }
}
