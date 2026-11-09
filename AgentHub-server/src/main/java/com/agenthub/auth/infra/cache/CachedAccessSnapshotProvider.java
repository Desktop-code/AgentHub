package com.agenthub.auth.infra.cache;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.admin.domain.model.User;
import com.agenthub.auth.domain.model.AccessSnapshot;
import com.agenthub.auth.domain.service.AccessSnapshotProvider;
import com.agenthub.auth.domain.service.AclCacheKeys;
import com.agenthub.auth.domain.service.AclCacheStore;
import com.agenthub.prompt.domain.service.SingleFlight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 访问快照的缓存装饰器。所属域：auth 的 infra。
 * <p>
 * 读取路径：<b>Redis → DB</b>。
 * 键 = {@code agenthub:acl:snapshot:{userId}:v{全局版本}}；写入口推进版本或精确删键后自然切到新键。
 * 未命中用单飞加载，避免冷键/版本切换瞬间并发打穿 {@code sys_user_role} + {@code sys_role_menu}。
 * <p>
 * 与提示词快照缓存的关键差异：
 * <ul>
 *   <li><b>不做负缓存</b>：本口按用户主键读，调用方必然已从库里拿到该用户；「用户不存在」不会走到这里。
 *       空权限是**有效快照**（普通用户没勾任何菜单），照常以 JSON 空数组缓存，不能用哨兵表示；</li>
 *   <li><b>坏值不回空</b>：空权限等于 403。反序列化失败或 {@code userId} 不匹配时必须剔除该键并回库重读，
 *       绝不能像提示词那样把「读不到」当成「确认没有」；</li>
 *   <li><b>停用用户不进缓存</b>：权限必须立即归零，不能靠 TTL 等待。</li>
 * </ul>
 * <p>
 * 降级口径：
 * <ul>
 *   <li>读不到全局版本 / Redis 读写异常 → 直接查库，本次不读也不写缓存；</li>
 *   <li>序列化失败 → 只放弃缓存，本次照常返回 DB 结果；</li>
 *   <li>DB 真失败 → 照常抛出（鉴权失败必须如实失败，不能当成空权限放行或降级成放行）。</li>
 * </ul>
 * <p>
 * 线程安全：无状态（单飞内部是并发 map），可单例复用。
 */
@Repository
@Primary
public class CachedAccessSnapshotProvider implements AccessSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(CachedAccessSnapshotProvider.class);

    private final AccessSnapshotProvider delegate;
    private final AclCacheStore store;
    private final ObjectMapper json;
    private final AclCacheProperties properties;
    private final SingleFlight flight = new SingleFlight();

    /**
     * @param delegate   DB 直读实现（bean 名 {@code dbAccessSnapshotProvider}），真值来源
     * @param store      跨进程共享层
     * @param json       序列化器，复用容器配置
     * @param properties 开关与 TTL
     */
    public CachedAccessSnapshotProvider(@Qualifier("dbAccessSnapshotProvider") AccessSnapshotProvider delegate,
                                        AclCacheStore store, ObjectMapper json, AclCacheProperties properties) {
        this.delegate = delegate;
        this.store = store;
        this.json = json;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public AccessSnapshot snapshot(User user) {
        // 停用用户：权限与菜单必须当场归零，直接走库上的判断，不进缓存
        if (bypass(user)) {
            return delegate.snapshot(user);
        }
        Optional<Long> version = store.currentVersion();
        if (version.isEmpty()) {
            // Redis 不可用：本次全链路退回直读，不产生任何键
            return delegate.snapshot(user);
        }
        String key = AclCacheKeys.snapshot(user.getId(), version.get());
        // 单飞：同键并发只让一个线程穿透到 Redis/DB
        return flight.run(key, () -> load(key, user));
    }

    /**
     * 缓存关闭、用户为空/停用、主键非法时直接走库：不开缓存就不该产生任何键。
     *
     * @param user 当前用户
     * @return true 表示本次跳过缓存
     */
    private boolean bypass(User user) {
        return !properties.isEnabled()
                || user == null
                || user.getId() == null
                || user.getId() <= 0
                || !RecordStatus.isEnabled(user.getStatus());
    }

    /**
     * 读缓存或回库。
     *
     * @param key  缓存键（已含版本）
     * @param user 当前用户
     * @return 该用户的访问快照
     */
    private AccessSnapshot load(String key, User user) {
        Optional<String> cached = store.get(key);
        if (cached.isPresent()) {
            AccessSnapshot hit = deserialize(key, cached.get());
            if (hit != null && user.getId().equals(hit.getUserId())) {
                return hit;
            }
            // 坏值或串了别的用户：先剔除，再回库；绝不把空权限返回给鉴权
            store.evict(key);
            log.warn("访问快照缓存不可用，已回库重读 key={}", key);
        }
        AccessSnapshot fresh = delegate.snapshot(user);
        put(key, fresh);
        return fresh;
    }

    /**
     * 回填。序列化失败只放弃缓存，不影响本次返回。
     *
     * @param key      缓存键
     * @param snapshot DB 读到的快照
     */
    private void put(String key, AccessSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        String packed;
        try {
            packed = json.writeValueAsString(snapshot);
        } catch (Exception ex) {
            log.warn("访问快照缓存序列化失败，本次不缓存 key={} type={}", key, ex.getClass().getSimpleName());
            return;
        }
        store.put(key, packed, Duration.ofSeconds(Math.max(properties.getRedisTtlSec(), 1)));
    }

    /**
     * 反序列化；失败返回 null 交由调用方回库。
     *
     * @param key    缓存键
     * @param packed 缓存里的 JSON
     * @return 快照；坏值返回 null
     */
    private AccessSnapshot deserialize(String key, String packed) {
        if (packed == null || packed.isBlank()) {
            return null;
        }
        try {
            return json.readValue(packed, AccessSnapshot.class);
        } catch (Exception ex) {
            log.warn("访问快照缓存反序列化失败 key={} type={}", key, ex.getClass().getSimpleName());
            return null;
        }
    }
}
