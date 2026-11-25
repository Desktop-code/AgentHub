package com.agenthub.memory.application;

import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 过期清理挂载点（刀8）：MySQL 清理成功后必须把 LONG 主键交给向量联删。
 * <p>
 * 全部 mock：不打库、不连 Milvus。断言重点是「同一出口」与「库侧失败不联删」。
 */
@ExtendWith(MockitoExtension.class)
class MemoryScheduleConfigTest {

    @Mock
    private MemoryItemRepository items;
    @Mock
    private MemoryVectorHook vectors;

    private MemoryProperties properties;
    private MemoryScheduleConfig config;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setExpiredPurgeBatchLimit(2000);
        config = new MemoryScheduleConfig(items, vectors, properties);
    }

    @Test
    void purgedLongIdsAreHandedToVectorDeletion() {
        when(items.purgeExpired(any(LocalDateTime.class), anyInt())).thenReturn(List.of(7L, 8L));

        config.purgeExpired();

        // 单轮条数上限来自配置，禁止一次扫全表
        verify(items).purgeExpired(any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq(2000));
        // 同一出口：MySQL 清理完成后立刻投递向量联删（异步，不堵主线程）
        verify(vectors).afterRemoved(List.of(7L, 8L));
    }

    @Test
    void nothingPurgedMeansNoVectorCall() {
        when(items.purgeExpired(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        config.purgeExpired();

        verifyNoInteractions(vectors);
    }

    @Test
    void repositoryFailureSkipsVectorDeletionForThisRound() {
        when(items.purgeExpired(any(LocalDateTime.class), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        // 库侧失败不能让定时任务抛出去打挂调度线程；向量也不能先删（MySQL 未成功）
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> config.purgeExpired());
        verify(vectors, never()).afterRemoved(any());
    }

    @Test
    void batchLimitFallsBackToPositiveValue() {
        properties.setExpiredPurgeBatchLimit(0);
        when(items.purgeExpired(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        config.purgeExpired();

        verify(items).purgeExpired(any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq(1));
    }
}
