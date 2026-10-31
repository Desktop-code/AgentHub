package com.agenthub.runtime.api.sse;

import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;

import java.util.List;

/**
 * 统一事件 ↔ 现网 SSE event 名的映射表。所属域：runtime（出站层）。
 * 为什么需要它：前端已按旧名字（assistant / reasoning / tool / done / error …）写好了分发逻辑，
 * 定稿要求「不一次砍光旧字段」，所以同一条统一事件要同时兼容旧名与新名两条通道：
 * <b>旧名保持原有载荷形状</b>（前端能立刻继续用），<b>新名以 Unified 为准</b>（后续前端可平滑切过去）。
 * 维护约束：新增统一类型时必须在此登记，未登记的类型按「扩展事件」原样透传，前端按未知事件忽略。
 */
public final class UnifiedSseMapping {

    /**
     * 一条统一事件对应的一组出站帧。
     *
     * @param legacyEvent 旧前端的 event 名；null 表示没有旧等价物，只发新名
     * @param unifiedEvent 新契约的 event 名，取自 {@link UnifiedStreamEventType#code()}
     * @param legacyDataIsString 旧前端该事件的 data 是否是「JSON 字符串」而不是对象。
     *                            前端解析器对 assistant / reasoning / error 按字符串处理，
     *                            对 tool / done 按对象处理，发错形状会导致前端静默丢弃。
     */
    public record Mapping(String legacyEvent, String unifiedEvent, boolean legacyDataIsString) {
    }

    /** 正文增量：旧名 assistant，data 是纯文本字符串 */
    private static final Mapping MESSAGE_DELTA =
            new Mapping("assistant", UnifiedStreamEventType.MESSAGE_DELTA.code(), true);
    /** 思考增量：旧名 reasoning，data 是纯文本字符串 */
    private static final Mapping THINKING_DELTA =
            new Mapping("reasoning", UnifiedStreamEventType.THINKING_DELTA.code(), true);
    /** 工具调用增量：旧前端没有对应事件，只发新名，避免被旧解析器当未知事件丢弃时误判 */
    private static final Mapping TOOL_CALL_DELTA =
            new Mapping(null, UnifiedStreamEventType.TOOL_CALL_DELTA.code(), false);
    /** 工具调用完成：旧前端也没有等价物，工具卡片仍由旧 tool 事件驱动 */
    private static final Mapping TOOL_CALL_COMPLETED =
            new Mapping(null, UnifiedStreamEventType.TOOL_CALL_COMPLETED.code(), false);
    /** 用量：旧前端无等价物 */
    private static final Mapping USAGE =
            new Mapping(null, UnifiedStreamEventType.USAGE.code(), false);
    /** 错误：旧名 error，data 是纯文本字符串 */
    private static final Mapping ERROR =
            new Mapping("error", UnifiedStreamEventType.ERROR.code(), true);
    /** 结束：旧名 done，data 是对象（前端读 status / sessionId / errorSummary） */
    private static final Mapping DONE =
            new Mapping("done", UnifiedStreamEventType.DONE.code(), false);

    /** 全部已知映射，按声明顺序 */
    private static final List<Mapping> ALL =
            List.of(MESSAGE_DELTA, THINKING_DELTA, TOOL_CALL_DELTA, TOOL_CALL_COMPLETED, USAGE, ERROR, DONE);

    private UnifiedSseMapping() {
    }

    /**
     * 按统一类型取映射。
     *
     * @param type 统一事件类型，可空
     * @return 命中时返回映射；为 null 或未登记时返回 null，由调用方按扩展事件处理
     */
    public static Mapping of(UnifiedStreamEventType type) {
        if (type == null) {
            return null;
        }
        for (Mapping mapping : ALL) {
            if (mapping.unifiedEvent().equals(type.code())) {
                return mapping;
            }
        }
        return null;
    }

    /**
     * 统一结束原因 → 旧前端 done 载荷里的 status。
     * 旧前端只认 FAILED 与非 FAILED：FAILED 显示错误态，其余显示完成态。
     * 因此「降级结束」必须映射成 FAILED，否则前端会以为任务正常完成。
     *
     * @param reason 统一结束原因，可空
     * @return FAILED 或 SUCCESS
     */
    public static String legacyStatus(UnifiedFinishReason reason) {
        if (reason == null) {
            // 兜底：没有原因也不当成成功，避免把未知结局伪装成正常完成
            return "FAILED";
        }
        return switch (reason) {
            case STOP, TOOL_CALLS -> "SUCCESS";
            case LENGTH, CONTENT_FILTER, ERROR, TIMEOUT, CANCELLED -> "FAILED";
        };
    }

    /**
     * @param reason 统一结束原因，可空
     * @return 是否属于「降级/异常」结束，供出站层决定是否补 error 提示
     */
    public static boolean isDegraded(UnifiedFinishReason reason) {
        return reason != null && !"SUCCESS".equals(legacyStatus(reason));
    }
}
