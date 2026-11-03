package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent_def。所属域：agent。列与 sql/schema-delta-agent.sql 一致，不另建列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_def")
public class AgentDefEntity extends AgentAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private String description;
    private String runMode;
    /**
     * 是否允许被编排挂为子专家。1=允许，0=禁止。
     * <p>ROUTER 恒为 0（编排根不可作子），服务端保存时强制写 0；列非空，见 schema-delta-allow-orchestrated.sql。
     */
    private Integer allowOrchestrated;
    /** 用户业务段。不另加列，平台前缀不入库 */
    private String systemPrompt;
    /** CHAT 引用的 L2 模板主键；L3 挂载点，见 schema-delta-prompt.sql */
    private Long promptTemplateId;
    /** 钉死的 L2 版本号；平台发新版不静默升级 */
    private Integer templateVersion;
    /** L3 soft 覆盖 JSON：seg_key → 文案 */
    private String promptOverrideJson;
    /** L3 追加区（append-only，不可覆盖锁定段） */
    private String promptExtra;
    /** 整次墙钟超时覆盖，毫秒；空=跟平台 YAML */
    private Long timeoutMs;
    /** ReAct 最大轮次覆盖；空=跟平台 */
    private Integer maxRounds;
    /** 单次工具超时覆盖，毫秒；空=跟平台 */
    private Long toolTimeoutMs;
    /** 工具额外重试次数覆盖；空=跟平台 */
    private Integer toolMaxRetries;
    /** 单次试跑工具调用次数上限覆盖；空=跟平台 */
    private Integer maxToolCalls;
    /** 单次 LLM 超时覆盖，毫秒；空=跟平台 */
    private Long llmTimeoutMs;
    /** 单次试跑 LLM 调用次数上限覆盖；空=跟平台 */
    private Integer maxLlmCalls;
    /** 连续工具失败中止阈值覆盖；空=跟平台 */
    private Integer consecutiveFailAbort;
    /** 回灌模型的工具结果字符上限覆盖；空=跟平台 */
    private Integer toolResultMaxChars;
    /** 运行配置 JSON；可空。列见 schema-delta-agent-runtime-config.sql */
    private String runtimeConfigJson;
    private Integer status;
    private String remark;

    @TableLogic
    private Integer deleted;
}
