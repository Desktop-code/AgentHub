package com.agenthub.memory.infra;

import com.agenthub.memory.domain.repository.MemoryItemRepository;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 过期记忆的定时清理。所属域：memory。
 * 读路径也会跳过 expired_time，这里把库里的过期行逻辑删除。首次执行推迟，避免启动时打库。
 */
@Component
@EnableScheduling
public class MemoryScheduleConfig {

    private final MemoryItemRepository items;

    /**
     * @param items 条目仓储
     */
    public MemoryScheduleConfig(MemoryItemRepository items) {
        this.items = items;
    }

    /**
     * 按 expired_time 清理。失败只影响这一轮，下一轮再试。
     */
    @Scheduled(fixedDelayString = "${agenthub.memory.cleanup-delay-ms:3600000}",
            initialDelayString = "${agenthub.memory.cleanup-delay-ms:3600000}")
    public void purgeExpired() {
        items.purgeExpired(LocalDateTime.now());
    }
}
