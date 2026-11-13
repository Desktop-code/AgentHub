package com.agenthub.agent.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 预置形象键、固定色板与自定义头像 URL 前缀。所属域：agent。
 * <p>
 * 与用户头像彻底分开：用户头像落 {@code /uploads/avatars/}，
 * Agent 自定义形象落 {@code /uploads/agent-avatars/}，两套互不读取。
 * 只允许白名单键 / 色值与本站上传前缀，禁止外链与任意色值。
 */
public final class AgentLookPalette {

    /** 主题紫，未选颜色时的默认 */
    public static final String DEFAULT_COLOR = "#635BFF";

    /** Agent 自定义头像的对外访问前缀，与静态映射一致 */
    public static final String AVATAR_URL_PREFIX = "/uploads/agent-avatars/";

    /** 平台预置形象键（正式命名，禁止 demo） */
    public static final Set<String> ICONS = Set.of(
            "look-guide",
            "look-analyst",
            "look-support",
            "look-coder",
            "look-ops",
            "look-research",
            "look-butler",
            "look-scholar"
    );

    /** 固定色板（含主题紫）；对外回显用规范大小写 */
    private static final Map<String, String> COLOR_BY_UPPER = new LinkedHashMap<>();

    static {
        for (String color : new String[]{
                "#635BFF", "#3B82F6", "#10B981", "#F59E0B",
                "#EF4444", "#8B5CF6", "#06B6D4", "#64748B"
        }) {
            COLOR_BY_UPPER.put(color.toUpperCase(Locale.ROOT), color);
        }
    }

    /** 色板只读集合，供单测与文档对齐 */
    public static final Set<String> COLORS = Set.copyOf(COLOR_BY_UPPER.values());

    private AgentLookPalette() {
    }

    /**
     * 规范化形象键。空串视为未选（存 null）；非法键拒绝。
     *
     * @param raw 请求值，可空
     * @return 白名单键或 null
     * @throws BizException 不在白名单
     */
    public static String normalizeIcon(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!ICONS.contains(key)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的 Agent 形象");
        }
        return key;
    }

    /**
     * 规范化色值。空串视为未选；非法色拒绝。
     *
     * @param raw 请求值，可空
     * @return 白名单色或 null
     * @throws BizException 不在色板
     */
    public static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String matched = COLOR_BY_UPPER.get(raw.trim().toUpperCase(Locale.ROOT));
        if (matched == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择平台色板中的颜色");
        }
        return matched;
    }

    /**
     * 校验自定义头像 URL 必须是本站上传结果。
     * <p>只认 {@code /uploads/agent-avatars/} 前缀，杜绝外链与路径穿越。
     *
     * @param raw 请求 URL
     * @return 规范化后的相对 URL
     * @throws BizException 非本站上传或疑似穿越
     */
    public static String normalizeAvatarUrl(String raw) {
        String url = raw == null ? "" : raw.trim().replace('\\', '/');
        if (!url.startsWith(AVATAR_URL_PREFIX)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先通过上传按钮上传 Agent 形象");
        }
        // 只允许前缀 + 「数字/文件名」，出现 .. 或协议头一律拒绝
        String rest = url.substring(AVATAR_URL_PREFIX.length());
        if (rest.isEmpty() || rest.contains("..") || rest.contains("://") || rest.contains(" ")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "形象地址不合法");
        }
        return AVATAR_URL_PREFIX + rest;
    }
}
