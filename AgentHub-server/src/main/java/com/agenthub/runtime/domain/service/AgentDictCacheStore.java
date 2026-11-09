package com.agenthub.runtime.domain.service;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * 运行期字典缓存的跨进程读写口。所属域：runtime。
 * <p>
 * 调用方：{@code CachedAgentDictionary}（读/回填）与 {@code AgentDictCacheInvalidatorImpl}（删）。
 * 实现方：Redis（{@code RedisAgentDictCacheStore}）；测试用内存实现。
 * <p>
 * 约束（与 prompt 域 {@code PromptCacheStore} 同口径）：
 * 1. **任何异常都必须由实现吞掉并降级为 miss**——缓存抖动不允许阻断运行；
 *    允许的降级正是「缓存层失败但 DB 读成功」；
 * 2. 值为 JSON 字符串，本口不理解内容；
 * 3. 只允许存字典快照，**禁止**存凭据密文、对话正文与记忆正文。
 */
public interface AgentDictCacheStore {

    /**
     * @param key 缓存键
     * @return 值；不存在或读失败时为空
     */
    Optional<String> get(String key);

    /**
     * @param key   缓存键
     * @param value 序列化后的值
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
