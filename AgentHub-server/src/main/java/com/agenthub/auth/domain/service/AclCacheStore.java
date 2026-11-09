package com.agenthub.auth.domain.service;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * 访问快照缓存的跨进程读写口。所属域：auth。
 * <p>
 * 调用方：{@code CachedAccessSnapshotProvider}（读/回填）与 {@code AclCacheInvalidatorImpl}（版本推进/删键）。
 * 实现方：Redis（{@code RedisAclCacheStore}）；测试用内存实现。
 * <p>
 * 约束（与 prompt / runtime 的缓存口同口径）：
 * 1. **任何异常都必须由实现吞掉并降级**——Redis 抖动不允许让鉴权返回 5xx；
 *    允许的降级是「读不到版本 → 直接查库」；
 * 2. 版本推进失败只记日志，靠 TTL 兜底，不把异常抛回写接口；
 * 3. 只存角色/权限/菜单快照，**禁止**存 token、密码哈希或密钥。
 */
public interface AclCacheStore {

    /**
     * 读全局 ACL 版本。
     *
     * @return 版本号；键不存在视为 0；Redis 不可用时为空（调用方必须降级为直读库）
     */
    Optional<Long> currentVersion();

    /**
     * 推进全局 ACL 版本，使所有旧快照键逻辑失效。
     * 版本键不设 TTL，避免计数回退后旧键复活。
     *
     * @return 新版本号；失败返回 -1（只记日志，不抛异常）
     */
    long bumpVersion();

    /**
     * @param key 缓存键
     * @return 值；不存在或读失败时为空
     */
    Optional<String> get(String key);

    /**
     * @param key   缓存键
     * @param value 序列化后的快照
     * @param ttl   存活时间；<=0 时不写
     */
    void put(String key, String value, Duration ttl);

    /**
     * @param key 缓存键
     */
    void evict(String key);

    /**
     * 批量删除。删失败同样只记日志，由 TTL 兜底。
     *
     * @param keys 缓存键；可空
     */
    void evictAll(Collection<String> keys);
}
