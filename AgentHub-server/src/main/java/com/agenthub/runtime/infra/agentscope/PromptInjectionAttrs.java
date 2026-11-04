package com.agenthub.runtime.infra.agentscope;

import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.PromptInjectionStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把本轮分段注入统计拍平成 obs attrs 的扁平键。所属域：runtime 的 infra。
 * <p>
 * 为什么要这一层：obs 是横切域，只认「键 → 值」，不认识 runtime 的统计模型；
 * 让 {@code ObsAttrs} 直接收 {@link PromptInjectionStats} 会形成 obs → runtime 的反向依赖。
 * 这里由 runtime 主动把统计翻成 Map，依赖方向保持 runtime → obs。
 * <p>
 * 字段选择（面板只看这组就够判断「钱花在哪」）：
 * <ul>
 *   <li>{@code prompt_chars} / {@code prompt_tokens_est}：全量合计，用于横向比较改前改后；</li>
 *   <li>{@code protected_chars} / {@code soft_chars}：保护区与可压区，回答「红线占多少」；</li>
 *   <li>{@code memory_chars} / {@code skill_chars} / {@code catalog_chars} / {@code schema_chars}
 *       / {@code user_chars}：回答「哪一段最贵」；</li>
 *   <li>{@code forced_skill_clipped}：强制规则是否被硬上限截过，这类截断必须可见；</li>
 *   <li>{@code prompt_breakdown}：一行人话文本，前端「查看扩展信息」直接可读。</li>
 * </ul>
 * <p>
 * token 是出站前估算（{@code TokenEstimator}），与 span 上的 usage 精确值分开：
 * 估算用于判断占比，usage 用于计费，两者不可互相替代。
 * <p>
 * 线程安全：无状态，静态纯函数。
 */
final class PromptInjectionAttrs {

    private PromptInjectionAttrs() {
    }

    /**
     * 把统计并入已有 attrs。
     *
     * @param existing 已有 attrs（可能已含 brief / cause），可空
     * @param stats    本轮分段统计，可空
     * @return 合并后的 JSON；stats 为空时原样返回 existing
     */
    static String merge(String existing, PromptInjectionStats stats) {
        if (stats == null) {
            return existing;
        }
        return ObsAttrs.withFields(existing, fieldsOf(stats));
    }

    /**
     * @param stats 分段统计，非空
     * @return 扁平键值；顺序即面板展示顺序
     */
    static Map<String, Object> fieldsOf(PromptInjectionStats stats) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tools_injected", stats.getToolsInjected());
        fields.put("skills_injected", stats.getSkillsInjected());
        fields.put("skills_hard_triggered", stats.getSkillsHardTriggered());
        fields.put("forced_skill_clipped", stats.getForcedSkillsClipped());
        fields.put("prompt_chars", stats.totalChars());
        fields.put("prompt_tokens_est", stats.totalTokens());
        fields.put("protected_chars", stats.getProtectedChars());
        fields.put("protected_tokens_est", stats.getProtectedTokens());
        fields.put("soft_chars", stats.getSystemSoftChars());
        fields.put("memory_chars", stats.getMemoryChars());
        fields.put("skill_chars", stats.getSkillChars());
        fields.put("catalog_chars", stats.getToolCatalogChars());
        fields.put("schema_chars", stats.getToolSchemaChars());
        fields.put("user_chars", stats.getUserChars());
        // 用户侧细分：P1 要回答「摘要 / 近轮 / 短期 / 本轮各占多少」
        fields.put("user_summary_chars", stats.getUserSummaryChars());
        fields.put("user_recent_chars", stats.getUserRecentChars());
        fields.put("user_short_chars", stats.getUserShortChars());
        fields.put("user_current_chars", stats.getUserCurrentChars());
        // 工具结果句柄化：条数与「原本有多少字」，用来证明这一轮省在哪
        fields.put("tool_results_offloaded", stats.getToolResultsOffloaded());
        fields.put("tool_results_offloaded_chars", stats.getToolResultsOffloadedChars());
        fields.put("truncated", stats.isTruncated());
        fields.put("prompt_breakdown", stats.breakdown());
        return fields;
    }
}
