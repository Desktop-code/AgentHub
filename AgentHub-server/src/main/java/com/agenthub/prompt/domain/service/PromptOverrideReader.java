package com.agenthub.prompt.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L3 覆盖 JSON 解析器。所属域：prompt。
 * <p>
 * 输入形如 <code>{"role_tone":"你是电商售后顾问…"}</code>，键是 L2 的 {@code seg_key}，
 * 值是覆盖文案。解析只做结构与类型校验，不判断「该段是否 soft」——那由 Assembler
 * 对照模板快照处理（模板可能已升级，运行期遇到失效键应忽略并留痕，而不是让线上对话失败）。
 * <p>
 * 约束：值必须是字符串；数字/对象一律视为配置错误。解析失败阻断出站，
 * 不能静默当作「无覆盖」——那会让业务以为改过的语气生效了，实际没有。
 */
public final class PromptOverrideReader {

    private static final TypeReference<LinkedHashMap<String, Object>> RAW = new TypeReference<>() {
    };

    private PromptOverrideReader() {
    }

    /**
     * @param overrideJson L3 覆盖 JSON；空白表示无覆盖
     * @param json         Jackson 实例，复用容器配置
     * @return 有序的 seg_key → 文案；无覆盖时为空 map
     * @throws BizException JSON 非法或值不是字符串
     */
    public static Map<String, String> read(String overrideJson, ObjectMapper json) {
        if (overrideJson == null || overrideJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = json.readValue(overrideJson, RAW);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.PROMPT_UNAVAILABLE, "L3 提示词覆盖 JSON 无法解析");
        }
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!(value instanceof String text)) {
                throw new BizException(ErrorCode.PROMPT_UNAVAILABLE,
                        "L3 提示词覆盖的值必须是文本：" + key);
            }
            // 空白值按「未覆盖」处理，避免用空串把 soft 段整段抹掉
            if (text.isBlank()) {
                continue;
            }
            values.put(key, text);
        }
        return values;
    }
}
