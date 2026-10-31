package com.agenthub.agent.domain.service;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 子路由 route_key / sort 的默认生成规则。所属域：agent。
 * 主路径不强迫用户手填；同父下 key 须唯一、稳定。
 * 子 code 先规范化（去空格、非法字符压成 _）再作候选，避免「MCP Test Agent」直接落到 child_。
 */
public final class AgentRouteDefaults {

    /** 同父下第一条子路由的默认排序 */
    public static final int SORT_FIRST = 10;
    /** 后续排序步长：max(sort) + SORT_STEP */
    public static final int SORT_STEP = 10;

    /** 规范化后可用作 route_key：字母数字下划线，1～64 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private AgentRouteDefaults() {
    }

    /**
     * 将子 Agent code 规范成可作 route_key 的短码。
     * 规则：去首尾空白 → 删除所有空格 → 其余非 [A-Za-z0-9_] 压成单个 _ → 截断 ≤64；
     * 结果为空或非法则返回 null（调用方再降级 child_{id}）。
     *
     * @param code 子 Agent 业务编码，可空
     * @return 规范化短码，或 null
     */
    public static String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        // 先去首尾空白，再删光中间空格（含连续空格），保留其它字符交给后续过滤。
        String noSpace = code.trim().replace(" ", "");
        if (noSpace.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(noSpace.length(), 64));
        boolean lastUnderscore = false;
        for (int i = 0; i < noSpace.length(); i++) {
            char c = noSpace.charAt(i);
            if (isKeyChar(c)) {
                sb.append(c);
                lastUnderscore = c == '_';
            } else if (!lastUnderscore) {
                // 连续非法字符只落一个 _，避免 MCP--Test 变成 MCP__Test。
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String normalized = sb.toString();
        // 首尾若因前后非法字符产生多余 _，去掉以免观感奇怪。
        while (!normalized.isEmpty() && normalized.charAt(0) == '_') {
            normalized = normalized.substring(1);
        }
        while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '_') {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        if (normalized.isEmpty() || !KEY_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    /**
     * 规范化后是否可作为 route_key 候选。
     *
     * @param code 子 Agent 业务编码，可空
     * @return true 表示规范化结果非空且合法
     */
    public static boolean isValidKeyCandidate(String code) {
        return normalizeCode(code) != null;
    }

    /**
     * 空或非法 code 时的兜底键：child_{childAgentId}。
     *
     * @param childAgentId 子 Agent 主键
     * @return 稳定短码
     */
    public static String fallbackKey(Long childAgentId) {
        return "child_" + childAgentId;
    }

    /**
     * 是否为系统兜底键（空键或 child_{id}[/_{routeId}]），可在懒补时按规范化 code 升级。
     * 手改过的高级 key（如 QAAgent）返回 false，禁止覆盖。
     *
     * @param routeKey     当前 route_key
     * @param childAgentId 子 Agent 主键
     * @param routeId      路由行主键，可空
     * @return true 表示允许按 code 刷新
     */
    public static boolean isSystemFallbackKey(String routeKey, Long childAgentId, Long routeId) {
        if (needsGeneratedKey(routeKey)) {
            return true;
        }
        if (childAgentId == null) {
            return false;
        }
        String fallback = fallbackKey(childAgentId);
        if (fallback.equals(routeKey)) {
            return true;
        }
        return routeId != null && (fallback + "_" + routeId).equals(routeKey);
    }

    /**
     * 为新建或补全空 / child_* 键选一个同父下未占用的键。
     * 优先规范化后的子 Agent code；冲突或空则 child_{id}；仍冲突且有 routeId 时再拼 _{routeId}。
     *
     * @param childCode      子 Agent code，可空
     * @param childAgentId   子 Agent 主键
     * @param routeId        已有行主键；新建传 null
     * @param usedKeys       同父下其它行已占用的非空 route_key（不含自身）
     * @return 可入库的 route_key
     */
    public static String resolveKey(String childCode, Long childAgentId, Long routeId, Collection<String> usedKeys) {
        String preferred = normalizeCode(childCode);
        if (preferred != null && !containsIgnoreCase(usedKeys, preferred)) {
            return preferred;
        }
        String fallback = fallbackKey(childAgentId);
        if (!containsIgnoreCase(usedKeys, fallback)) {
            return fallback;
        }
        // 极罕见：有人把手填 key 占成 child_{id}；有主键时再拼一层保证可落库。
        if (routeId != null) {
            String withId = fallback + "_" + routeId;
            if (!containsIgnoreCase(usedKeys, withId)) {
                return withId;
            }
        }
        return fallback;
    }

    /**
     * 同父下下一条排序：无子则 10，否则 max + 10。
     *
     * @param maxSort 同父当前最大 sort_order；无子传 null
     * @return 新排序值
     */
    public static int nextSort(Integer maxSort) {
        if (maxSort == null) {
            return SORT_FIRST;
        }
        return maxSort + SORT_STEP;
    }

    /**
     * 空白视为未填，交给生成规则。
     *
     * @param raw 请求里的 route_key
     * @return true 表示需要系统生成
     */
    public static boolean needsGeneratedKey(String raw) {
        return raw == null || raw.isBlank();
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private static boolean containsIgnoreCase(Collection<String> usedKeys, String candidate) {
        if (usedKeys == null || usedKeys.isEmpty() || candidate == null) {
            return false;
        }
        String needle = candidate.toLowerCase(Locale.ROOT);
        for (String used : usedKeys) {
            if (used != null && needle.equals(used.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
