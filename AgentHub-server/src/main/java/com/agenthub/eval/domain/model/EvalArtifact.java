package com.agenthub.eval.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单 case 试跑产物摘要。所属域：eval 的 domain。
 * <p>对齐定稿 §5.5 的<strong>最小子集</strong>：本刀只从现网 trial 门面结果抽取，
 * 不落盘完整 events.jsonl（完整导出属后续 ArtifactExporter 刀）。
 * <p>敏感字段：禁止携带 apiKey / Token；final_answer 由调用方按需截断。
 */
@Data
public class EvalArtifact {

    /** 现网试跑会话 id（字符串化，便于与 eval_result.session_id 对齐） */
    private String sessionId;
    /** 观测 traceId；缺省可空（埋点失败不因此把成功打成 ERROR） */
    private String traceId;
    /** 门面返回的本轮状态：SUCCESS / FAILED / CANCELLED 等 */
    private String status;
    /** 终答预览（已截断） */
    private String finalAnswer;
    /** 工具调用摘要，可空列表 */
    private List<ToolCallSummary> toolCalls = new ArrayList<>();
    /** 用量摘要，可空 */
    private UsageSummary usage;
    /** 是否命中问答缓存；缺省 false（评测通道默认 bypass） */
    private Boolean cacheHit = Boolean.FALSE;
    /** 墙钟耗时（毫秒） */
    private Integer latencyMs;
    /** 结束原因（有则填，如 stop / timeout） */
    private String finishReason;
    /** 基础设施/业务失败摘要；成功时为空 */
    private String errorSummary;
    /**
     * 提示词分段字符数（如 user_total / recent / short）；可空。
     * RuleScorer 的 prompt_chars_max 读此字段；缺省且断言存在 → 该条 fail。
     */
    private java.util.Map<String, Integer> promptBreakdown;

    /**
     * 工具调用一行摘要。所属域：eval。
     */
    @Data
    public static class ToolCallSummary {
        /** 工具名 */
        private String name;
        /** 是否视为成功（本刀按事件存在即 true，细判属刀3） */
        private Boolean success;
    }

    /**
     * Token 用量摘要。所属域：eval。
     */
    @Data
    public static class UsageSummary {
        private Integer prompt;
        private Integer completion;
        private Integer total;
    }
}
