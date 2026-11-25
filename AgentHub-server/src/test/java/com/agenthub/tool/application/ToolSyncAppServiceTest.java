package com.agenthub.tool.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.api.dto.SyncResp;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.agenthub.tool.infra.mcp.McpToolView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 同步用例：按远端 name 对齐本源工具项。所属域：tool 的 application。
 * 关注写库动作：新名字插入、已存在只更新、远端已删与同源重复行逻辑删除，且连续同步不叠行。
 */
@ExtendWith(MockitoExtension.class)
class ToolSyncAppServiceTest {

    @Mock
    private ToolSourceRepository sources;
    @Mock
    private ToolItemRepository items;
    @Mock
    private ToolLookup lookup;
    @Mock
    private ToolReferencePolicy references;
    @Mock
    private McpClientFactory clients;
    @Mock
    private McpClient mcpClient;
    @Mock
    private SecretStore secretStore;
    @Mock
    private OperatorContext operatorContext;
    @Mock
    private OperLogWriter operLogWriter;

    /**
     * 首次同步：远端两个工具、本地已有其中一个且已启用 → 只插一条，已启用项不被改回停用。
     */
    @Test
    void firstSyncInsertsMissingAndKeepsLocalStatus() {
        stubSource();
        ToolItem echo = item(1L, "echo", 1);
        when(items.listBySourceId(7L)).thenReturn(List.of(echo));
        when(mcpClient.listTools("http://mcp", null)).thenReturn(List.of(
                new McpToolView("echo", "回声", "say", "{}"),
                new McpToolView("fresh", null, null, null)));

        SyncResp resp = service().sync(7L);

        assertEquals(1, resp.getCreated());
        assertEquals(1, resp.getUpdated());
        assertEquals(0, resp.getRetired());
        // 已存在项 status 保持 1，未被同步改成停用
        assertEquals(1, echo.getStatus());
        verify(items, times(1)).insert(any(ToolItem.class), eq(9L));
        verify(items, never()).logicalDelete(anyLong(), anyLong());
    }

    /**
     * 远端去掉 echo 后再次同步：echo 逻辑删除下架，不再产出任何插入。
     */
    @Test
    void removedRemoteToolIsRetiredOnNextSync() {
        stubSource();
        ToolItem echo = item(1L, "echo", 1);
        ToolItem fresh = item(2L, "fresh", 0);
        when(items.listBySourceId(7L)).thenReturn(List.of(echo, fresh));
        when(mcpClient.listTools("http://mcp", null)).thenReturn(List.of(
                new McpToolView("fresh", null, null, null)));

        SyncResp resp = service().sync(7L);

        assertEquals(0, resp.getCreated());
        assertEquals(1, resp.getRetired());
        verify(items, never()).insert(any(ToolItem.class), anyLong());
        verify(items, times(1)).logicalDelete(eq(1L), eq(9L));
    }

    /**
     * 历史重复行（同源同名两条）在同步时收敛为一条：多余行下架，同一 name 不再插入第二条。
     */
    @Test
    void duplicateRowsAreCollapsedWithoutSecondInsert() {
        stubSource();
        when(items.listBySourceId(7L)).thenReturn(List.of(item(1L, "dup", 1), item(2L, "dup", 1)));
        when(mcpClient.listTools("http://mcp", null)).thenReturn(List.of(
                new McpToolView("dup", null, null, null)));

        SyncResp resp = service().sync(7L);

        assertEquals(0, resp.getCreated());
        assertEquals(1, resp.getRetired());
        verify(items, never()).insert(any(ToolItem.class), anyLong());
        verify(items, times(1)).logicalDelete(eq(2L), eq(9L));
    }

    /**
     * 连续两次同步同一远端清单：第二次没有新增、没有下架，只保留库内两条。
     */
    @Test
    void secondSyncOnSameRemoteDoesNotDuplicate() {
        stubSource();
        List<McpToolView> remote = List.of(
                new McpToolView("echo", "回声", "say", "{}"),
                new McpToolView("fresh", null, null, null));
        when(mcpClient.listTools("http://mcp", null)).thenReturn(remote);

        // 第一次：空库 → 插两条
        when(items.listBySourceId(7L)).thenReturn(List.of());
        SyncResp first = service().sync(7L);
        assertEquals(2, first.getCreated());

        // 第二次：库里已是第一次的结果（echo 的展示名首次插入时就用远端 title 回填）
        List<ToolItem> stored = new ArrayList<>();
        stored.add(item(1L, "echo", 0));
        stored.add(item(2L, "fresh", 0));
        stored.get(0).setTitle("回声");
        stored.get(0).setDescription("say");
        stored.get(0).setInputSchemaJson("{}");
        when(items.listBySourceId(7L)).thenReturn(stored);
        SyncResp second = service().sync(7L);

        assertEquals(0, second.getCreated());
        assertEquals(0, second.getUpdated());
        assertEquals(0, second.getRetired());
    }

    private ToolSyncAppService service() {
        // 写库阶段（事务边界）已拆成独立协作者；用例只做 wiring，断言仍盯着 repository / operLog 的调用
        ToolSyncWriter writer = new ToolSyncWriter(sources, items, operatorContext, operLogWriter,
                AgentDictCacheInvalidator.NOOP);
        return new ToolSyncAppService(lookup, references, clients, secretStore, writer);
    }

    /**
     * 固定一条启用中的 MCP 源，并让操作人为 9。
     */
    private void stubSource() {
        ToolSource source = new ToolSource();
        source.setId(7L);
        source.setName("测试源");
        source.setType("MCP");
        source.setTransport("SSE");
        source.setBaseUrl("http://mcp");
        source.setStatus(1);
        when(lookup.requireSource(7L)).thenReturn(source);
        when(clients.require("SSE")).thenReturn(mcpClient);
        when(operatorContext.requireUserId()).thenReturn(9L);
    }

    private ToolItem item(Long id, String name, int status) {
        ToolItem item = new ToolItem();
        item.setId(id);
        item.setSourceId(7L);
        item.setName(name);
        item.setStatus(status);
        return item;
    }
}
