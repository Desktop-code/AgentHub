package com.agenthub.memory.infra.persistence;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryVectorSyncStatus;
import com.agenthub.memory.infra.persistence.entity.MemoryItemEntity;
import com.agenthub.memory.infra.persistence.mapper.MemoryItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓储层的向量同步状态位契约。所属域：memory 的 infra。
 * <p>
 * 钉死两条硬约束：
 * <ol>
 *   <li><b>入库</b>：只有 LONG 从 PENDING 起步；SHORT / AGENT 直接落 SYNCED
 *       （它们永远不该有记忆向量，停在 PENDING 只会制造「未同步」的假象）；</li>
 *   <li><b>修改</b>：域对象没带状态位时不得覆盖库里的值（MyBatis-Plus NOT_NULL 跳过 null），
 *       状态位只由 {@code markVectorSync} 显式改。</li>
 * </ol>
 * mapper 全部 mock，不连库、不产生任何 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MemoryItemRepositoryVectorStatusTest {

    @Mock
    private MemoryItemMapper mapper;

    private MemoryItemRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new MemoryItemRepositoryImpl(mapper);
    }

    @Test
    void longInsertStartsAsPending() {
        repository.insert(item("LONG"), 1L);

        MemoryItemEntity saved = capturedInsert();
        // LONG 要等提交后的异步双写，入库时只能标 PENDING
        assertEquals(MemoryVectorSyncStatus.PENDING.name(), saved.getVectorSyncStatus());
        assertEquals(0, saved.getVectorSyncFailCount());
        assertNull(saved.getVectorSyncError());
    }

    @Test
    void shortInsertStartsAsSynced() {
        repository.insert(item("SHORT"), 1L);

        MemoryItemEntity saved = capturedInsert();
        // SHORT 走 Redis/MySQL 双写，与记忆向量无关：不该出现 PENDING
        assertEquals(MemoryVectorSyncStatus.SYNCED.name(), saved.getVectorSyncStatus());
        assertEquals(0, saved.getVectorSyncFailCount());
    }

    @Test
    void agentInsertStartsAsSynced() {
        repository.insert(item("AGENT"), 1L);

        MemoryItemEntity saved = capturedInsert();
        // AGENT 记忆不参与向量召回，同样直接终态
        assertEquals(MemoryVectorSyncStatus.SYNCED.name(), saved.getVectorSyncStatus());
    }

    @Test
    void updateDoesNotOverwriteStatusUnlessExplicitlySet() {
        MemoryItem draft = item("LONG");
        draft.setId(11L);
        draft.setVectorSyncStatus(null);

        repository.update(draft, 1L);

        MemoryItemEntity updated = capturedUpdate();
        // null = 这次不碰状态列；否则异步写入的结论会被后来的编辑覆盖掉
        assertNull(updated.getVectorSyncStatus());
        assertNull(updated.getVectorSyncFailCount());
    }

    @Test
    void markVectorSyncDelegatesToScopedSql() {
        when(mapper.updateVectorSync(any(), anyString(), anyInt(), any())).thenReturn(1);

        int changed = repository.markVectorSync(java.util.Arrays.asList(11L, 11L, null, 0L),
                MemoryVectorSyncStatus.SYNCED, -5, null);

        assertEquals(1, changed);
        // 去重 + 过滤非法 id 后才交给 SQL；负面失败次数压回 0，由 SQL 侧再硬锁 scope='LONG'
        verify(mapper).updateVectorSync(eq(List.of(11L)), eq("SYNCED"), eq(0), eq(null));
    }

    @Test
    void markVectorSyncIgnoresEmptyOrNullIds() {
        assertEquals(0, repository.markVectorSync(List.of(), MemoryVectorSyncStatus.SYNCED, 0, null));
        assertEquals(0, repository.markVectorSync(null, MemoryVectorSyncStatus.SYNCED, 0, null));

        verify(mapper, org.mockito.Mockito.never())
                .updateVectorSync(any(), anyString(), anyInt(), any());
    }

    private MemoryItemEntity capturedInsert() {
        ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private MemoryItemEntity capturedUpdate() {
        ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }

    private static MemoryItem item(String scope) {
        MemoryItem item = new MemoryItem();
        item.setAgentId(1L);
        item.setUserId(2L);
        item.setScope(scope);
        item.setContent("用户住广州");
        item.setContentHash("hash-1");
        item.setImportance(40);
        item.setStatus(1);
        return item;
    }
}
