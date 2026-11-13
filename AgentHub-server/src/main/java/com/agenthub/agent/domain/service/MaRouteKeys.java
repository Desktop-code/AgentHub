package com.agenthub.agent.domain.service;

import java.util.Collection;
import java.util.Locale;

/**
 * 「另一套编排方案」候选的路由键派生。所属域：agent。
 *
 * <p>为什么单独一个工具：默认接待页与多智能体抽屉两条入口都会把方案加进路由池，
 * 路由键必须是同一套规则（方案 code 优先、非法字符剔除、冲突回落 {@code ma_{id}}），
 * 否则同一个方案从两个入口加进去会得到两个键，粘性与换线对不上。
 *
 * <p>不改库、不查库；调用方传入「同方案内已占用的键」。
 */
public final class MaRouteKeys {

    /** route_key 列宽 */
    private static final int KEY_MAX = 64;

    private MaRouteKeys() {
    }

    /**
     * 为方案候选生成同方案内唯一的 route_key。
     *
     * @param maId     目标方案主键；为空时只能用编码
     * @param code     目标方案编码；可空
     * @param usedKeys 同方案内其它候选已占用的键（大小写不敏感）
     * @return 可入库的 route_key
     */
    public static String forTarget(Long maId, String code, Collection<String> usedKeys) {
        return forTarget(maId, code, usedKeys, "ma_");
    }

    /**
     * 为「工作流」候选生成同方案内唯一的 route_key（刀5B）：规则与方案完全一致，
     * 只是冲突回落前缀换成 {@code wf_}，避免把工作流线路显示成方案线路。
     *
     * @param workflowId 目标工作流主键；为空时只能用编码
     * @param code       目标工作流编码；可空
     * @param usedKeys   同方案内其它候选已占用的键（大小写不敏感）
     * @return 可入库的 route_key
     */
    public static String forWorkflow(Long workflowId, String code, Collection<String> usedKeys) {
        return forTarget(workflowId, code, usedKeys, "wf_");
    }

    /**
     * 通用派生：编码优先，冲突 / 非法时回落 {@code prefix + 主键}。
     *
     * @param id       目标主键；为空时用兜底词
     * @param code     目标编码；可空
     * @param usedKeys 已占用的键
     * @param prefix   回落前缀（方案 ma_ / 工作流 wf_）
     * @return 可入库的 route_key
     */
    private static String forTarget(Long id, String code, Collection<String> usedKeys, String prefix) {
        String preferred = normalize(code);
        if (preferred != null && !used(usedKeys, preferred)) {
            return preferred;
        }
        String base = id == null ? prefix + "target" : prefix + id;
        if (!used(usedKeys, base)) {
            return base;
        }
        // 极少见：有人把手填键占成了 prefix{id}；按序拼后缀，保证仍能落库
        int suffix = 2;
        while (used(usedKeys, base + "_" + suffix)) {
            suffix++;
        }
        return base + "_" + suffix;
    }

    /**
     * 规范化：只保留字母、数字与下划线，其余按分隔符丢弃；空结果返回 null。
     *
     * @param raw 方案编码
     * @return 规范化后的键；无法产出时为空
     */
    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder text = new StringBuilder(raw.length());
        for (char c : raw.trim().toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                text.append(c);
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= KEY_MAX ? text.toString() : text.substring(0, KEY_MAX);
    }

    private static boolean used(Collection<String> usedKeys, String key) {
        if (usedKeys == null || usedKeys.isEmpty()) {
            return false;
        }
        String wanted = key.toLowerCase(Locale.ROOT);
        for (String used : usedKeys) {
            if (used != null && used.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }
}
