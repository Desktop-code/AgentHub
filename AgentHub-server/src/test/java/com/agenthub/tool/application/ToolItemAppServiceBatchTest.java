package com.agenthub.tool.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import com.agenthub.tool.api.dto.ItemBatchStatusResp;
import com.agenthub.tool.api.dto.ItemStatusReq;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 源内批量启停工具项。所属域：tool 的 application。
 * 关注三点：只按 sourceId 圈定、内置源拒绝、状态值只收 1 / 0。
 */
@ExtendWith(MockitoExtension.class)
class ToolItemAppServiceBatchTest {

    @Mock
    private ToolItemRepository items;
    @Mock
    private ToolSourceRepository sources;
    @Mock
    private ToolLookup lookup;
    @Mock
    private ToolReferencePolicy references;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;

    /**
     * MCP 源：按 sourceId 批量置启用，回填实际更新行数并写操作日志。
     */
    @Test
    void enablesAllItemsOfMcpSource() {
        when(lookup.requireSource(7L)).thenReturn(source(7L, "MCP"));
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(items.updateStatusBySourceId(7L, 1, 9L)).thenReturn(3);

        ItemBatchStatusResp resp = service().changeAllStatus(7L, status(1));

        assertEquals(7L, resp.getSourceId());
        assertEquals(1, resp.getStatus());
        assertEquals(3, resp.getUpdated());
        verify(items).updateStatusBySourceId(7L, 1, 9L);
        // 批量不走单条更新，也不会误删或改 schema
        verify(items, never()).updateStatus(anyLong(), anyInt(), anyLong());
        verify(items, never()).logicalDelete(anyLong(), anyLong());
    }

    /**
     * 全部停用同样只作用于该源。
     */
    @Test
    void disablesAllItemsOfMcpSource() {
        when(lookup.requireSource(7L)).thenReturn(source(7L, "MCP"));
        when(operatorContext.requireUserId()).thenReturn(9L);
        when(items.updateStatusBySourceId(7L, 0, 9L)).thenReturn(5);

        ItemBatchStatusResp resp = service().changeAllStatus(7L, status(0));

        assertEquals(0, resp.getStatus());
        assertEquals(5, resp.getUpdated());
    }

    /**
     * 内置源批量启停会绕开只读约束，必须拒绝且不写库。
     */
    @Test
    void rejectsBuiltinSource() {
        when(lookup.requireSource(1L)).thenReturn(source(1L, "BUILTIN"));

        assertThrows(BizException.class, () -> service().changeAllStatus(1L, status(1)));
        verify(items, never()).updateStatusBySourceId(anyLong(), anyInt(), anyLong());
    }

    /**
     * 状态只接受 1 / 0，其它值在写库前就被拒绝。
     */
    @Test
    void rejectsIllegalStatus() {
        when(lookup.requireSource(7L)).thenReturn(source(7L, "MCP"));

        assertThrows(BizException.class, () -> service().changeAllStatus(7L, status(2)));
        verify(items, never()).updateStatusBySourceId(anyLong(), anyInt(), anyLong());
    }

    private ToolItemAppService service() {
        return new ToolItemAppService(items, sources, lookup, references, operatorContext, operLogWriter,
                AgentDictCacheInvalidator.NOOP);
    }

    private ToolSource source(Long id, String type) {
        ToolSource source = new ToolSource();
        source.setId(id);
        source.setName("源" + id);
        source.setType(type);
        source.setStatus(1);
        return source;
    }

    private ItemStatusReq status(Integer value) {
        ItemStatusReq req = new ItemStatusReq();
        req.setStatus(value);
        return req;
    }
}
