package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一对话请求。所属域：runtime（模型出站 ACL）。
 * 业务（ReAct、Router、计费埋点）只组装本结构，具体模型名与密钥由 Adapter / Gateway 侧解析，
 * 本类刻意不带 provider 与 apiKey 字段，避免凭证随协议结构扩散到业务层。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedChatRequest {

    /** 完整消息序列，末条通常是本轮 user 或 tool 结果 */
    private List<UnifiedMessage> messages;

    /** 可用工具定义；为空表示本轮不允许调工具，Adapter 需按厂商语义省略工具段 */
    private List<UnifiedToolDef> tools;

    /** 采样温度；null 表示用上游默认值，不要擅自塞 0 */
    private Double temperature;

    /** 最大输出 token；null 表示用上游默认值 */
    private Integer maxTokens;

    /** 是否流式；true 走 chatStream，false 走 chat，两端口径需一致 */
    private boolean stream;

    /**
     * 厂商 / 业务扩展位：如 thinking 开关、缓存策略、traceId。
     * 键名以「域前缀.语义」形式约定（例如 runtime.traceId），未知键由 Adapter 忽略。
     * 使用 LinkedHashMap 默认实现，保证透传顺序稳定、便于日志比对。
     */
    @Builder.Default
    private Map<String, Object> extensions = new LinkedHashMap<>();
}
