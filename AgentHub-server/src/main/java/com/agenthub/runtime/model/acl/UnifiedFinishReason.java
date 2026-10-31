package com.agenthub.runtime.model.acl;

/**
 * 统一结束原因。所属域：runtime（模型出站 ACL）。
 * 厂商各自的结束标识（文本型结束原因 / 无标识 EOF）由 Adapter 映射到本枚举，
 * 业务侧（Trace、计费、SSE done 兜底）只看本枚举，不解析厂商字段。
 * 取值写入流式 done 事件与非流式响应；新增取值属协议变更，需同步前端事件映射表。
 */
public enum UnifiedFinishReason {

    /** 模型自然说完：上游正常 stop / end_turn，且没有待执行的工具调用 */
    STOP("stop"),
    /** 模型发起工具调用，需由 Agent 执行后回灌继续；本轮不算完成 */
    TOOL_CALLS("tool_calls"),
    /** 触达 max_tokens 等长度上限被截断，正文可能不完整 */
    LENGTH("length"),
    /** 内容被上游安全策略拦截，正文不可信 */
    CONTENT_FILTER("content_filter"),
    /** 上游返回错误，没有可用的完整结果 */
    ERROR("error"),
    /** 读超时 / 整体墙钟超时触发收口，属降级结束 */
    TIMEOUT("timeout"),
    /** 客户端主动取消（断开 SSE 或关闭会话） */
    CANCELLED("cancelled");

    private final String code;

    UnifiedFinishReason(String code) {
        this.code = code;
    }

    /**
     * @return 协议对外（SSE done 帧、日志摘要）使用的稳定字符串值
     */
    public String code() {
        return code;
    }
}
