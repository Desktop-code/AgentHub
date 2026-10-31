package com.agenthub.prompt.domain.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 提示词缓存的外层（跨进程）读写口。所属域：prompt。
 * <p>
 * 调用方：{@code CachedPromptSnapshotProvider}（读）与缓存失效器（删）。
 * 实现方：Redis（{@code RedisPromptCacheStore}）；测试用内存实现。
 * <p>
 * 约束：
 * 1. **任何异常都必须由实现吞掉并降级为 miss**——缓存抖动不允许阻断对话，
 *    允许的降级正是「缓存层失败但 DB 读成功」。
 * 2. 值为 JSON 字符串，序列化格式由调用方决定，本口不理解内容。
 * 3. 不得在此存明文密钥或完整对话；这里只放 L1/L2 配置快照。
 */
public interface PromptCacheStore {

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
}
