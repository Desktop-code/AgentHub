package com.agenthub.tool.domain.service;

import com.agenthub.tool.domain.model.RemoteTool;
import com.agenthub.tool.domain.model.ToolItem;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把远端 tools/list 与本地该源的工具项按 name 对齐。所属域：tool。
 * <p>
 * 同步是「按远端清单对齐」，不是纯增量：
 * 1. 远端有、本地无 → 新增，status 固定 0（新发现默认停用，需人工启用）；
 * 2. 远端有、本地有 → 只更新说明 / schema 等同步字段，status 保持本地原值；
 * 3. 远端无、本地有 → 进 retired，调用方逻辑删除（与手工删除语义一致，移出可绑清单）；
 * 4. 本地同源同名多行（历史重复叠加）→ 只保留最早一条，其余进 retired，实现同步即消重。
 * <p>
 * 展示名单独处理：本地已有非空值视为人工维护（或既往同步结果），同步不覆盖；
 * 本地为空时才用远端 title 回填，远端也没有则保持空。
 */
public final class ToolSyncMerger {

    /** MCP 新发现的工具默认停用，必须手动启用才进入白名单 */
    public static final int DISCOVERED_STATUS = 0;

    private ToolSyncMerger() {
    }

    /**
     * @param existing 该源下未删除的现有项，调用方按 id 升序传入（保证同名重复时首条最早）
     * @param remote   本次 tools/list，空名字会被丢掉；同名只取第一条
     * @return 待插入、待更新、待下架（含同源同名重复行与远端未再返回的项）
     */
    public static Result merge(List<ToolItem> existing, List<RemoteTool> remote) {
        // 以 name 为唯一键；重复行进 retired，避免同一调用名保留两条可启用项
        Map<String, ToolItem> byName = new LinkedHashMap<>();
        List<ToolItem> retired = new ArrayList<>();
        if (existing != null) {
            for (ToolItem item : existing) {
                if (item.getName() == null || item.getName().isBlank()) {
                    continue;
                }
                ToolItem first = byName.putIfAbsent(item.getName(), item);
                if (first != null && item.getId() != null) {
                    retired.add(item);
                }
            }
        }
        List<ToolItem> created = new ArrayList<>();
        List<ToolItem> updated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (remote != null) {
            for (RemoteTool tool : remote) {
                if (tool.name() == null || tool.name().isBlank() || !seen.add(tool.name())) {
                    continue;
                }
                ToolItem found = byName.get(tool.name());
                if (found == null || found.getId() == null) {
                    ToolItem row = new ToolItem();
                    row.setName(tool.name());
                    row.setTitle(tool.title());
                    row.setDescription(tool.description());
                    row.setInputSchemaJson(tool.inputSchemaJson());
                    row.setStatus(DISCOVERED_STATUS);
                    created.add(row);
                } else if (applyRemoteMetadata(found, tool)) {
                    updated.add(found);
                }
            }
        }
        // 远端本轮没再返回的本地项全部下架，禁止留下僵尸可启用项
        for (Map.Entry<String, ToolItem> entry : byName.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                retired.add(entry.getValue());
            }
        }
        return new Result(created, updated, retired);
    }

    /**
     * 把远端同步字段写到本地项上；status 与已存在的展示名不动。
     *
     * @param local  本地未删除项
     * @param remote 远端同名工具
     * @return 是否发生实际变化（无变化不产生 update 语句）
     */
    private static boolean applyRemoteMetadata(ToolItem local, RemoteTool remote) {
        String oldTitle = local.getTitle();
        String oldDescription = local.getDescription();
        String oldSchema = local.getInputSchemaJson();
        // 展示名：本地为空才回填远端 title；非空视为人工已改，保留
        if (oldTitle == null || oldTitle.isBlank()) {
            local.setTitle(remote.title());
        }
        // 说明和 schema 是同步字段，按远端清单覆盖（远端为空时同样清空，保持与远端一致）
        local.setDescription(remote.description());
        local.setInputSchemaJson(remote.inputSchemaJson());
        return !Objects.equals(oldTitle, local.getTitle())
                || !Objects.equals(oldDescription, local.getDescription())
                || !Objects.equals(oldSchema, local.getInputSchemaJson());
    }

    /**
     * 一次合并的写入计划。retired 由调用方逐条逻辑删除，本类不碰仓储。
     */
    @Getter
    public static final class Result {

        private final List<ToolItem> created;
        private final List<ToolItem> updated;
        private final List<ToolItem> retired;

        /**
         * @param created 新项，status 已是 0
         * @param updated 旧项，status 未被改写，仅同步字段有变化
         * @param retired 需下架的本地项（远端已删 + 同源同名重复行）
         */
        public Result(List<ToolItem> created, List<ToolItem> updated, List<ToolItem> retired) {
            this.created = created;
            this.updated = updated;
            this.retired = retired;
        }
    }
}
