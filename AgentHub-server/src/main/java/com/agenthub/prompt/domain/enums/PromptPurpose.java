package com.agenthub.prompt.domain.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * L2 用途码。所属域：prompt。
 * 与模型槽 / ModelCapability 生成类用途对齐，禁止另造 INTENT 等脱节名。
 * 本枚举只覆盖可配提示词模板的用途，不含 EMBEDDING / RERANK 等非生成槽。
 */
public enum PromptPurpose {

    /** 主对话 */
    CHAT,
    /** Router 判路由 */
    ROUTER,
    /** 查询改写 */
    REWRITE,
    /** 滚动摘要 */
    SUMMARY,
    /** 记忆抽取 */
    MEMORY;

    /**
     * @return 控制台下拉顺序
     */
    public static List<PromptPurpose> allCanonical() {
        return List.of(values());
    }

    /**
     * @param raw 用途字符串
     * @return 规范枚举
     * @throws IllegalArgumentException 空白或未知
     */
    public static PromptPurpose parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("用途不能为空");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.name().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的用途: " + raw.trim()));
    }

    /**
     * @param raw 可空
     * @return 解析结果
     */
    public static Optional<PromptPurpose> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
