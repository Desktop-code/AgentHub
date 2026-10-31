package com.agenthub.skill.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.skill.domain.service.SkillTriggerMatcher;

import java.util.List;

/**
 * Skill 入参收口。所属域：skill。
 * 注解挡住空值和超长；这里负责去空白、默认版本、启停取值和触发关键词归一。
 * contentMd 上限按字符数计 100 万，低于库 MEDIUMTEXT（约 16MB），避免单次请求过大。
 */
public final class SkillChecks {

    /** 1 正常，与基座 RecordStatus 一致，本包不依赖 admin */
    public static final int ENABLED = 1;
    /** 0 停用 */
    public static final int DISABLED = 0;
    /** 正文应用层上限，单位是 Java char，不是字节 */
    public static final int CONTENT_MAX = 1_000_000;
    /** trigger_keywords 列宽 VARCHAR(255)，合并成逗号串后不得超过 */
    public static final int KEYWORDS_COLUMN_MAX = 255;

    private SkillChecks() {
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
     * @return 去掉首尾空白的文本
     */
    public static String requiredText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * @param raw   可空文本
     * @param max   最大长度
     * @param label 字段中文名
     * @return 空白变成 null
     */
    public static String optionalText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * 空值记为 "1"。不改写非空版本号，后续版本表再演进。
     *
     * @param raw 版本字符串，可空
     * @return 入库值
     */
    public static String version(String raw) {
        if (raw == null || raw.isBlank()) {
            return "1";
        }
        String text = raw.trim();
        if (text.length() > 32) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版本最长 32");
        }
        return text;
    }

    /**
     * 空白正文存 null。不 trim 中间内容，只拒绝超长。
     *
     * @param raw Markdown，可空
     * @return 可入库的正文
     */
    public static String content(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > CONTENT_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文最长 " + CONTENT_MAX + " 个字符");
        }
        return raw;
    }

    /**
     * 触发关键词收口：Tag 数组 → 库内逗号串。
     *
     * <p>先去空白、去空项、忽略大小写去重（口径在 {@link SkillTriggerMatcher}），
     * 再拦个数与单项长度，最后 join。校验失败直接 400，避免脏数据进库后运行时误触发。
     * 空数组返回 null：库里存 NULL，不存空串，便于「无关键词」判空统一。
     *
     * @param tags 前端提交的 Tag 数组，可空
     * @return 库内存储值，无有效关键词时 null
     * @throws BizException 个数超限、单项超长，或合并后总长超过列宽
     */
    public static String keywords(List<String> tags) {
        String joined = SkillTriggerMatcher.join(tags);
        if (joined == null) {
            return null;
        }
        List<String> parts = SkillTriggerMatcher.split(joined);
        if (parts.size() > SkillTriggerMatcher.KEYWORD_COUNT_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "触发关键词最多 " + SkillTriggerMatcher.KEYWORD_COUNT_MAX + " 个");
        }
        for (String part : parts) {
            if (part.length() > SkillTriggerMatcher.KEYWORD_MAX) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "单个触发关键词最长 " + SkillTriggerMatcher.KEYWORD_MAX);
            }
        }
        // 列宽 255：按 Java char 估算；中文关键词在 utf8mb4 下字节更多，故这里按字符从严
        if (joined.length() > KEYWORDS_COLUMN_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "触发关键词合计最长 " + KEYWORDS_COLUMN_MAX);
        }
        return joined;
    }

    /**
     * 新建未传状态时按正常。
     *
     * @param raw 请求里的状态，可空
     * @return 1 或 0
     */
    public static int initialStatus(Integer raw) {
        if (raw == null) {
            return ENABLED;
        }
        return exactStatus(raw);
    }

    /**
     * 列表筛选。未传表示不按启停过滤。
     *
     * @param raw 状态，可空
     * @return 1、0 或 null
     */
    public static Integer optionalStatus(Integer raw) {
        if (raw == null) {
            return null;
        }
        return exactStatus(raw);
    }

    /**
     * 启停接口必须显式给出 1 或 0。
     *
     * @param raw 目标状态
     * @return 入库值
     */
    public static int exactStatus(Integer raw) {
        if (raw == null || (raw != ENABLED && raw != DISABLED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 1 正常或 0 停用");
        }
        return raw;
    }
}
