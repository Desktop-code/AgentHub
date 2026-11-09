package com.agenthub.spi.cache;

import java.util.Collection;
import java.util.List;

/**
 * 访问快照（权限码 + 菜单树）缓存失效口。所属域：spi（跨域契约，只放接口）。
 * <p>
 * 调用方：admin 域的写入口（角色编辑/删除/授权菜单、菜单增删改、用户分配角色/停用/删除）。
 * 实现方：auth 域的 infra（{@code AclCacheInvalidatorImpl}），由它在**事务提交之后**推进版本或删键。
 * <p>
 * 为什么放在 spi：缓存由 auth 读，写却在 admin 域；让 admin 只依赖一个窄接口，
 * 避免 admin 反向依赖 auth（admin 是更底层的域），也避免两边各自维护一套键规则。
 * <p>
 * 硬约束（实现方必须做到）：
 * 1. **必须 afterCommit**：事务内推进版本/删键时，提交前的并发读者仍可能把旧快照写回新版本键；
 * 2. 入参为 null / 空集合视为无操作；
 * 3. 失败只记日志，靠 TTL 兜底，**禁止**把异常抛回写接口；
 * 4. 本接口只影响权限/菜单快照，不涉及会话、数据范围与其它缓存。
 */
public interface AclCacheInvalidator {

    /**
     * 空实现：缓存关闭或不需要失效的场景（单测）使用。
     */
    AclCacheInvalidator NOOP = new AclCacheInvalidator() {
        @Override
        public void evictUsers(Collection<Long> userIds) {
        }

        @Override
        public void evictAll() {
        }
    };

    /**
     * 精确失效若干用户：只删这些用户在**当前版本**下的快照键。
     * 用于「只影响单个用户」的写入口（分配角色、停用/删除用户）。
     *
     * @param userIds 用户主键；可空
     */
    void evictUsers(Collection<Long> userIds);

    /**
     * 整体失效：推进全局版本，使所有用户的旧快照键逻辑失效。
     * 用于无法枚举受影响用户的写入口（角色菜单授权、角色启停、菜单增删改）。
     */
    void evictAll();

    /**
     * 单用户失效的便捷方法。
     *
     * @param userId 用户主键；可空
     */
    default void evictUser(Long userId) {
        evictUsers(userId == null ? List.of() : List.of(userId));
    }
}
