package com.agenthub.tool.domain.service;

import com.agenthub.tool.domain.model.RemoteTool;
import com.agenthub.tool.domain.model.ToolItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同步合并：以远端 name 为唯一键对齐本源工具项。
 * 新工具停用；同名保留本地 status 与已手改展示名；远端消失与同源重复行一律下架。
 */
class ToolSyncMergerTest {

    /**
     * 同名远端只取第一条。已启用的 echo 不能被同步重新打开或关掉，
     * 本地手改过的展示名不被远端覆盖，本地空展示名用远端 title 回填。
     */
    @Test
    void existingStatusAndEditedTitleAreKeptWhileSchemaIsUpdated() {
        ToolItem echo = item(1L, "echo", 1, "本地改名", "旧说明", "{}");
        ToolSyncMerger.Result result = ToolSyncMerger.merge(List.of(echo), List.of(
                new RemoteTool("echo", "回声", "say", "{\"type\":\"object\"}"),
                new RemoteTool("fresh", "新工具", null, null),
                new RemoteTool("echo", "忽略", null, null)
        ));
        assertEquals(1, result.getCreated().size());
        ToolItem created = result.getCreated().get(0);
        assertEquals("fresh", created.getName());
        assertEquals("新工具", created.getTitle());
        assertEquals(ToolSyncMerger.DISCOVERED_STATUS, created.getStatus());
        // 已存在项：只更新同步字段，status 保持 1
        assertEquals(1, result.getUpdated().size());
        ToolItem updated = result.getUpdated().get(0);
        assertEquals(1, updated.getStatus());
        assertEquals("本地改名", updated.getTitle());
        assertEquals("say", updated.getDescription());
        assertEquals("{\"type\":\"object\"}", updated.getInputSchemaJson());
        assertTrue(result.getRetired().isEmpty());
    }

    /**
     * 本地展示名为空时用远端 title 回填；远端也没有则仍为空。
     */
    @Test
    void blankLocalTitleFallsBackToRemoteTitle() {
        ToolItem blank = item(1L, "a", 0, "", "old", "{}");
        ToolItem stillBlank = item(2L, "b", 0, null, "old", "{}");
        ToolSyncMerger.Result result = ToolSyncMerger.merge(List.of(blank, stillBlank), List.of(
                new RemoteTool("a", "远端标题", "d", "{}"),
                new RemoteTool("b", null, "d", "{}")
        ));
        assertEquals(2, result.getUpdated().size());
        assertEquals("远端标题", result.getUpdated().get(0).getTitle());
        assertNull(result.getUpdated().get(1).getTitle());
    }

    /**
     * 远端已删的项进入下架清单，不是「保留」。
     */
    @Test
    void toolsMissingRemotelyAreRetired() {
        ToolSyncMerger.Result result = ToolSyncMerger.merge(
                List.of(item(1L, "echo", 1, null, null, null), item(2L, "old", 1, null, null, null)),
                List.of(new RemoteTool("echo", "回声", "say", "{}")));
        assertEquals(0, result.getCreated().size());
        assertEquals(1, result.getRetired().size());
        assertEquals(2L, result.getRetired().get(0).getId());
    }

    /**
     * 历史同步叠出的同源同名多行：最早的留下，其余下架，避免再次同步继续叠行。
     */
    @Test
    void duplicateLocalNamesAreRetiredKeepingTheEarliest() {
        ToolItem first = item(10L, "dup", 1, null, null, null);
        ToolItem second = item(11L, "dup", 1, null, null, null);
        ToolSyncMerger.Result result = ToolSyncMerger.merge(List.of(first, second),
                List.of(new RemoteTool("dup", null, null, null)));
        assertEquals(0, result.getCreated().size());
        assertEquals(1, result.getRetired().size());
        assertEquals(11L, result.getRetired().get(0).getId());
        // 首条仍在，且不因「本地空字段 + 远端空字段」产生无意义更新
        assertTrue(result.getUpdated().isEmpty());
    }

    /**
     * 连续同步两次幂等：第二次远端清单不变时不再新增、不再下架。
     */
    @Test
    void secondSyncIsIdempotent() {
        List<RemoteTool> remote = List.of(
                new RemoteTool("echo", "回声", "say", "{}"),
                new RemoteTool("fresh", null, null, null));
        ToolSyncMerger.Result first = ToolSyncMerger.merge(List.of(), remote);
        assertEquals(2, first.getCreated().size());
        // 模拟第一次落库后再次同步
        ToolItem echo = item(1L, "echo", 0, "回声", "say", "{}");
        ToolItem fresh = item(2L, "fresh", 0, null, null, null);
        ToolSyncMerger.Result second = ToolSyncMerger.merge(List.of(echo, fresh), remote);
        assertEquals(0, second.getCreated().size());
        assertEquals(0, second.getUpdated().size());
        assertEquals(0, second.getRetired().size());
    }

    private ToolItem item(Long id, String name, int status, String title, String description, String schema) {
        ToolItem item = new ToolItem();
        item.setId(id);
        item.setName(name);
        item.setStatus(status);
        item.setTitle(title);
        item.setDescription(description);
        item.setInputSchemaJson(schema);
        return item;
    }
}
