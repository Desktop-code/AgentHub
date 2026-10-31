package com.agenthub.prompt.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.domain.enums.PromptPublishStatus;
import com.agenthub.prompt.domain.enums.PromptPurpose;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 提示词入参收口。所属域：prompt。
 * 注解挡空值和超长；这里负责去空白、默认 locale、用途与段落一致性。
 */
public final class PromptChecks {

    /** 默认语言，与架构定稿一致 */
    public static final String DEFAULT_LOCALE = "zh-CN";
    /** L1 / 段落正文应用层上限（char），低于 MEDIUMTEXT */
    public static final int CONTENT_MAX = 1_000_000;

    private PromptChecks() {
    }

    /**
     * @param raw 语言；空白回落 zh-CN
     * @return 规范 locale
     */
    public static String locale(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String text = raw.trim();
        if (text.length() > 16) {
            throw new BizException(ErrorCode.BAD_REQUEST, "locale 最长 16");
        }
        return text;
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
     * @return trim 后文本
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
     * @param raw   可空
     * @param max   最大长度
     * @param label 字段名
     * @return 空白变 null
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
     * 正文允许中间空白，仅拒绝 null/空白整段与超长。
     *
     * @param raw   正文
     * @param label 字段名
     * @return 原文（不去中间空白）
     */
    public static String requiredContent(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        if (raw.length() > CONTENT_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "过长");
        }
        return raw;
    }

    /**
     * @param raw 用途码
     * @return 规范大写用途
     */
    public static String purpose(String raw) {
        try {
            return PromptPurpose.parse(raw).name();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, ex.getMessage());
        }
    }

    /**
     * template_key：大写字母数字下划线。
     *
     * @param raw 业务键
     * @return 规范键
     */
    public static String templateKey(String raw) {
        String key = requiredText(raw, 64, "templateKey");
        String upper = key.toUpperCase(Locale.ROOT);
        if (!upper.matches("^[A-Z][A-Z0-9_]*$")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "templateKey 须为字母开头的大写字母/数字/下划线");
        }
        return upper;
    }

    /**
     * @param flag 0/1；null 用默认
     * @param def  默认
     * @param label 字段名
     * @return 0 或 1
     */
    public static int flag01(Integer flag, int def, String label) {
        if (flag == null) {
            return def;
        }
        if (flag != 0 && flag != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "只能是 0 或 1");
        }
        return flag;
    }

    /**
     * 校验段落列表：seg_key 唯一、必填字段齐全。
     *
     * @param segments 段落；可空表示无段
     * @param keyFn    取 segKey
     * @param <T>      段 DTO 类型
     */
    public static <T> void assertSegments(List<T> segments, Function<T, String> keyFn) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>();
        for (T segment : segments) {
            String key = keyFn.apply(segment);
            if (key == null || key.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "segKey 不能为空");
            }
            String normalized = key.trim();
            if (!keys.add(normalized)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "segKey 重复: " + normalized);
            }
        }
    }

    /**
     * @param status 当前状态
     * @throws BizException 非草稿
     */
    public static void requireDraft(Integer status) {
        if (status == null || status != PromptPublishStatus.DRAFT.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅草稿可编辑或发布");
        }
    }

    /**
     * @param status 当前状态
     * @throws BizException 非草稿（发布专用文案）
     */
    public static void requireDraftForPublish(Integer status) {
        if (status == null || status != PromptPublishStatus.DRAFT.getCode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能发布草稿状态的版本");
        }
    }
}
