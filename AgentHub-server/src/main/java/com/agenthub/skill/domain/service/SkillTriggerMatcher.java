package com.agenthub.skill.domain.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 触发关键词的解析、归一与命中判断。所属域：skill。
 *
 * <p>库内 {@code skill_def.trigger_keywords} 只存一条逗号分隔字符串，前端用 Tag 数组编辑，
 * 因此 split（串 → 数组）与 join（数组 → 串）必须有唯一口径：本类。
 * DTO 装配（SkillAssembler）与运行时装配（TrialPromptFactory 硬触发）都走这里，禁止各处自写 split。
 *
 * <p>命中规则：用户本轮输入包含任一非空关键词即视为命中，比较时忽略大小写；
 * 不做分词、不做同义词、不做模糊匹配，避免误触发把无关正文灌进上下文。
 * 关键词本身是运营手填的短标签（如「脱敏」「摘要」），命中即由系统强制注入，不交给模型判断。
 */
public final class SkillTriggerMatcher {

    /** 库内分隔符：英文逗号 */
    public static final String SEPARATOR = ",";

    /** 单个关键词最长字符，超出在入参收口处拒绝 */
    public static final int KEYWORD_MAX = 32;

    /** 关键词个数上限；与 VARCHAR(255) 的存储成本匹配 */
    public static final int KEYWORD_COUNT_MAX = 20;

    private SkillTriggerMatcher() {
    }

    /**
     * 把库内逗号串拆成 Tag 数组。全角逗号也当分隔，避免运营手输「，」后整条关键词失效。
     *
     * @param raw 库内原文，可空
     * @return 去空白、去空项、按首次出现去重后的关键词；原文为空时返回空列表（不返回 null）
     */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return normalize(List.of(raw.replace('，', ',').split(SEPARATOR)));
    }

    /**
     * 把前端 Tag 数组合并成库内逗号串。
     *
     * @param tags 前端提交的关键词，可空
     * @return 库内存储值；没有有效关键词时返回 null（库里存 NULL 而不是空串）
     */
    public static String join(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> parts = normalize(tags);
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    /**
     * 判断本轮用户输入是否命中任一关键词。
     *
     * @param userQuery     用户本轮原文，可空
     * @param rawKeywords   库内逗号串，可空
     * @return true 表示需要强制注入该 Skill 正文；输入或关键词为空一律 false
     */
    public static boolean matches(String userQuery, String rawKeywords) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        String query = userQuery.toLowerCase(Locale.ROOT);
        for (String keyword : split(rawKeywords)) {
            // 忽略大小写包含即可；关键词已在 split 里去过空白，不会出现空串
            if (query.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去空白、去空项、忽略大小写去重，保留首次出现顺序。
     *
     * @param parts 原始片段（可能含空串或首尾空白）
     * @return 归一后的关键词，永不为 null
     */
    private static List<String> normalize(Iterable<String> parts) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            // 大小写不同视为同一个 Tag，避免出现「OCR」「ocr」两条重复项
            if (seen.add(text.toLowerCase(Locale.ROOT))) {
                out.add(text);
            }
        }
        return out;
    }
}
