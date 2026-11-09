package com.agenthub.auth.domain.service;

/**
 * 访问快照缓存键。所属域：auth。
 * <p>
 * 键形态（集中在此，禁止调用点拼串）：
 * <ul>
 *   <li>{@code agenthub:acl:ver} —— 全局 ACL 版本（整数，INCR 推进）</li>
 *   <li>{@code agenthub:acl:snapshot:{userId}:v{ver}} —— 该用户的访问快照 JSON</li>
 * </ul>
 * <p>
 * 为什么把版本写进键、而不是「写时精确删所有受影响用户的键」：
 * 角色/菜单变更影响哪些用户需要一次「角色 → 用户」反查，而菜单变更影响全体（超管读全量菜单）。
 * 用全局版本号，写侧只需一次 INCR 就让所有旧键逻辑失效，读侧天然拿到新键；
 * 单用户变更（分配角色 / 停用）仍走精确删，见 {@code AclCacheInvalidator#evictUsers}。
 * <p>
 * 版本键**不设 TTL**：若它自然过期并从 0 重新计数，历史上写过的 {@code v0} 键可能被重新命中。
 * Redis 整体清空时快照键同时消失，因此不会出现「旧值复活」。
 */
public final class AclCacheKeys {

    /** 根前缀：域 + 资源，便于按前缀排查与手工清理 */
    private static final String ROOT = "agenthub:acl";

    private AclCacheKeys() {
    }

    /**
     * @return 全局 ACL 版本键
     */
    public static String version() {
        return ROOT + ":ver";
    }

    /**
     * @param userId  用户主键，可空（归一成 0，读取口已挡下空值）
     * @param version 全局 ACL 版本
     * @return 该用户在该版本下的快照键
     */
    public static String snapshot(Long userId, long version) {
        return ROOT + ":snapshot:" + id(userId) + ":v" + version;
    }

    /**
     * 主键归一：只允许数字主键，避免冒号/空白进键。
     */
    private static String id(Long userId) {
        return userId == null ? "0" : String.valueOf(userId);
    }
}
