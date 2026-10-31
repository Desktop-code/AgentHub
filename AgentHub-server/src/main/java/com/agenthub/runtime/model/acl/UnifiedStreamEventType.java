package com.agenthub.runtime.model.acl;

/**
 * 统一流式事件类型。所属域：runtime（模型出站 ACL）。
 * 这些 code 是对外 SSE 帧的稳定契约（前端按 type 分发），改名即协议变更，需同步前端映射表。
 * 未知扩展 type（如 vendor.thinking）不在本枚举内，前端与观测侧按「忽略不报错」处理。
 */
public enum UnifiedStreamEventType {

    /** 正文增量。content 为本次新增片段，非全量 */
    MESSAGE_DELTA("message.delta"),
    /** 思考 / 推理增量（可选能力，模型不支持则不产生） */
    THINKING_DELTA("thinking.delta"),
    /** 工具调用名或参数增量；toolCallDelta 里 id 可只在首片出现 */
    TOOL_CALL_DELTA("tool_call.delta"),
    /** 单次工具调用参数已齐，toolCallDelta 携带完整可执行参数 */
    TOOL_CALL_COMPLETED("tool_call.completed"),
    /** 用量上报，可出现在流中或结束前；载荷为 UnifiedUsage */
    USAGE("usage"),
    /** 错误说明。收到后仍必须等 done，前端不得据此直接结束 Loading */
    ERROR("error"),
    /** 结束事件，必发：正常结束、降级结束、超时、取消都要发一次 */
    DONE("done");

    private final String code;

    UnifiedStreamEventType(String code) {
        this.code = code;
    }

    /**
     * @return SSE 帧 type 字段使用的稳定字符串
     */
    public String code() {
        return code;
    }
}
