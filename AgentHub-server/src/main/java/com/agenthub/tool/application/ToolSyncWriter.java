package com.agenthub.tool.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.tool.api.dto.SyncResp;
import com.agenthub.tool.domain.model.RemoteTool;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolSyncMerger;
import com.agenthub.tool.infra.mcp.McpToolView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 同步的「写库阶段」。所属域：tool。
 *
 * <p>存在意义是把出站调用与数据库事务切开：{@link ToolSyncAppService#sync} 先在**事务外**
 * 完成解密与 {@code tools/list}（可能耗时数秒、可能挂住连接），拿到远端清单后才进本类开事务，
 * 一次性完成工具项 upsert、下架与同步列回写。这样长事务不会占着连接等远端。
 *
 * <p>事务语义与原实现一致：成功路径（insert/update/logicalDelete/recordSync/操作日志）同一事务；
 * 失败路径（recordSync 记失败 + 操作日志）同一事务，均整体提交或整体回滚。
 *
 * <p>约束：本类只做入库与日志，不做网络请求、不读请求上下文、不改远端失败文案。
 */
@Slf4j
@Service
public class ToolSyncWriter {

    private final ToolSourceRepository sources;
    private final ToolItemRepository items;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param sources         回写 last_sync_* 同步列
     * @param items           upsert 工具项
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志（不含鉴权头与密文）
     */
    public ToolSyncWriter(ToolSourceRepository sources, ToolItemRepository items,
                          OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.sources = sources;
        this.items = items;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 按远端清单对齐本源工具项：先插入新名字，再更新同名同步字段，最后逻辑删除需下架的项。
     * 只处理 source 作用域内的数据，不触碰其他源与 BUILTIN 种子。
     *
     * @param source  已校验可同步的 MCP 源
     * @param remote  本次 tools/list（事务外取得）
     * @param started 计时起点（同步整体耗时从出站前开始算）
     * @return 创建 / 更新 / 下架计数
     */
    @Transactional
    public SyncResp apply(ToolSource source, List<McpToolView> remote, long started) {
        List<RemoteTool> tools = remote.stream()
                .map(view -> new RemoteTool(view.name(), view.title(), view.description(), view.inputSchemaJson()))
                .toList();
        // 对账在事务内做：以「读到的库内状态」为基准，避免并发同步把同一 name 插两行
        ToolSyncMerger.Result merged = ToolSyncMerger.merge(items.listBySourceId(source.getId()), tools);
        Long operatorId = operatorContext.requireUserId();
        for (ToolItem created : merged.getCreated()) {
            created.setSourceId(source.getId());
            items.insert(created, operatorId);
        }
        for (ToolItem updated : merged.getUpdated()) {
            items.updateMetadata(updated, operatorId);
        }
        // 下架 = 逻辑删除：与手工删除同一语义，findById / page 自动过滤，绑定侧也无法再启用
        for (ToolItem retired : merged.getRetired()) {
            if (retired.getId() == null) {
                continue;
            }
            items.logicalDelete(retired.getId(), operatorId);
        }
        String message = "ok; created=" + merged.getCreated().size()
                + " updated=" + merged.getUpdated().size()
                + " retired=" + merged.getRetired().size();
        sources.recordSync(source.getId(), LocalDateTime.now(), 1, SyncText.clip(message, 255), operatorId);
        operLogWriter.write("tool", "sync", "tool_source", String.valueOf(source.getId()),
                "同步工具源 " + source.getName() + " " + message);
        return SyncResp.ok(elapsed(started), merged.getCreated().size(), merged.getUpdated().size(),
                merged.getRetired().size(), message);
    }

    /**
     * 远端失败仍记下摘要，方便卡片展示。不把异常对象打进日志，避免消息里残留地址。
     *
     * @param source  同步失败的源（已校验）
     * @param started 计时起点
     * @param ex      出站阶段异常（消息已脱敏）
     * @return 面向控制台的失败结果
     */
    @Transactional
    public SyncResp fail(ToolSource source, long started, RuntimeException ex) {
        String message = SyncText.safe(ex.getMessage(), null);
        log.warn("MCP 同步失败 sourceId={} reason={}", source.getId(), ex.getClass().getSimpleName());
        Long operatorId = operatorContext.requireUserId();
        sources.recordSync(source.getId(), LocalDateTime.now(), 0, message, operatorId);
        operLogWriter.write("tool", "sync", "tool_source", String.valueOf(source.getId()),
                "同步工具源失败 " + source.getName());
        return SyncResp.fail(elapsed(started), message);
    }

    /** 毫秒耗时：与原实现同一算法，保证返回给前端的 elapsed 口径不变。 */
    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
