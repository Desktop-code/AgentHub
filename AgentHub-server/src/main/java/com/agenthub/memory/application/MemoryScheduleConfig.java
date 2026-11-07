package com.agenthub.memory.application;

import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期记忆的定时清理。所属域：memory 的 application。
 * <p>
 * 放在 application 而不是 infra：这一步是「清库 + 联动删向量」的编排，要同时用到仓储与
 * {@link MemoryVectorHook}，属于用例层；infra 只提供仓储/Milvus 实现，不反向依赖 application。
 * <p>
 * 读路径也会跳过 expired_time，这里把库里的过期行逻辑删除。首次执行推迟，避免启动时打库。
 * <p>
 * 刀8：MySQL 清理成功后<b>同一出口</b>联删向量点（经 {@code MemoryVectorHook#afterRemoved}）。
 * 挂载点刻意放在这里而不是另起一个「只扫 Milvus」的任务：MySQL 是唯一真源，
 * 只有它判定「这行已过期」才是权威结论；再开一套扫 Milvus 的任务必然出现两套过期口径。
 * <p>
 * 限流：单轮最多处理 {@code expired-purge-batch-limit}（默认 2000）行，避免一次删几十万行
 * 长时间持锁、并把海量主键整批塞给向量删除。未清完的下轮继续（按 id 升序，进度单调）。
 */
@Component
@EnableScheduling
public class MemoryScheduleConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryScheduleConfig.class);

    private final MemoryItemRepository items;
    private final MemoryVectorHook vectors;
    private final MemoryProperties properties;

    /**
     * @param items      条目仓储
     * @param vectors    过期清理行的向量联动删除（异步投递，失败只打标不回滚）
     * @param properties 单轮清理条数上限
     */
    public MemoryScheduleConfig(MemoryItemRepository items, MemoryVectorHook vectors,
                                MemoryProperties properties) {
        this.items = items;
        this.vectors = vectors;
        this.properties = properties;
    }

    /**
     * 按 expired_time 清理。失败只影响这一轮，下一轮再试。
     * <p>
     * 清理出的是 MySQL 逻辑删除；返回的 LONG 主键立即交给向量联删，
     * 否则向量点会一直留在库里（只靠检索 filter 挡着，与「已过期」语义不一致）。
     */
    @Scheduled(fixedDelayString = "${agenthub.memory.cleanup-delay-ms:3600000}",
            initialDelayString = "${agenthub.memory.cleanup-delay-ms:3600000}")
    public void purgeExpired() {
        int limit = Math.max(properties.getExpiredPurgeBatchLimit(), 1);
        List<Long> purged;
        try {
            purged = items.purgeExpired(LocalDateTime.now(), limit);
        } catch (RuntimeException e) {
            // 库侧失败：整轮放弃，下一轮再试。不触碰向量（MySQL 未成功就不能先删点）
            log.warn("[MEMORY] 过期清理失败，本轮放弃 batchLimit={} type={} msg={}",
                    limit, e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        if (purged.isEmpty()) {
            return;
        }
        // 先落日志再投递：联删在池线程里跑，出问题时这行是唯一的「该批曾被处理」证据
        log.info("[MEMORY] 过期清理完成 purgedLong={} batchLimit={}", purged.size(), limit);
        vectors.afterRemoved(purged);
    }
}
