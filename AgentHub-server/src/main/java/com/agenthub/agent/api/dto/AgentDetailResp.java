package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 详情。所属域：agent。计数字段只说明绑了多少，明细走各自的列表接口。
 * systemPrompt 只是用户业务段；platformPrefix 只读，不入库。
 */
@Data
public class AgentDetailResp {

    private Long id;
    private String name;
    private String code;
    private String description;
    /** 形象配置；null 表示未配置，前端用名称首字 + 默认色 */
    private AgentAvatarConfigDto avatarConfig;
    private String runMode;
    /**
     * 是否允许被编排挂为子专家。ROUTER 恒为 false（编排根不可作子），
     * 其它模式由配置页开关决定；前端据此禁用/回显。
     */
    private Boolean allowOrchestrated;
    /** 用户业务段 */
    private String systemPrompt;
    /**
     * L3 提示词挂接回显。未挂模板时 templateId 为空、override 为空 Map；
     * {@code supported=false} 表示该用途没有提示词模板维度（当前 CHAT 恒为 true）。
     */
    private PromptBindingResp prompt;
    /** 当前运行模式对应的平台前缀，配置页展示，不能编辑 */
    private String platformPrefix;
    /** 保存时的告警。查询详情为空列表，不表示历史告警 */
    private List<String> warnings;
    /** 运行限制覆盖，空表示跟平台 */
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
     * 运行配置。ROUTER：multiIntentEnabled / answerMode / maxRouteChildren。
     * 库空时仍返回默认对象，便于表单绑定。
     */
    private AgentRuntimeConfigResp runtimeConfig;
    private Integer status;
    private String remark;
    private Integer modelBindCount;
    private Integer toolBindCount;
    private Integer skillBindCount;
    private Integer routeCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
