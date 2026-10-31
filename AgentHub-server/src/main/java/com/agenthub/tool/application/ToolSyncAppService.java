package com.agenthub.tool.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.api.dto.SourceProbeReq;
import com.agenthub.tool.api.dto.SourceProbeResp;
import com.agenthub.tool.api.dto.SyncResp;
import com.agenthub.tool.domain.model.RemoteTool;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import com.agenthub.tool.domain.service.ToolSyncMerger;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.agenthub.tool.infra.mcp.McpToolView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 同步（SSE / Streamable HTTP）。所属域：tool。
 * 同步语义是「按远端 tools/list 以 name 对齐本源工具项」，不是纯增量：
 * 新名字插入（默认停用）；同名更新说明 / schema（保留本地 status 与已手改的展示名）；
 * 远端已删的同名项、以及同源同名重复行逻辑删除下架，移出可绑清单。
 * 远端失败只更新 last_sync_*，不改已有工具项。日志不写鉴权头、密文和主密钥。
 */
@Slf4j
@Service
public class ToolSyncAppService {

    private final ToolSourceRepository sources;
    private final ToolItemRepository items;
    private final ToolLookup lookup;
    private final ToolReferencePolicy references;
    private final McpClientFactory clients;
    private final SecretStore secretStore;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param sources         回写同步结果
     * @param items           upsert 工具项
     * @param lookup          按 id 取值
     * @param references      只允许启用中的 MCP（SSE/HTTP）
     * @param clients         按 transport 选客户端
     * @param secretStore     解密可选鉴权头
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志，失败摘要不含头
     */
    public ToolSyncAppService(ToolSourceRepository sources, ToolItemRepository items, ToolLookup lookup,
                              ToolReferencePolicy references, McpClientFactory clients, SecretStore secretStore,
                              OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.sources = sources;
        this.items = items;
        this.lookup = lookup;
        this.references = references;
        this.clients = clients;
        this.secretStore = secretStore;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param id 工具源 id
     * @return 成功或失败都返回脱敏结果，校验不通过时抛业务异常且不写同步列
     */
    @Transactional
    public SyncResp sync(Long id) {
        ToolSource source = lookup.requireSource(id);
        references.assertSyncable(source);
        long started = System.nanoTime();
        List<McpToolView> remote;
        try {
            remote = pull(source);
        } catch (RuntimeException ex) {
            return fail(source, started, ex);
        }
        return apply(source, remote, started);
    }

    /**
     * 弹窗内测 MCP 可达与 tools/list，不写库、不改工具项。
     * 按 req.transport 选 SSE 或 Streamable HTTP；填了鉴权头则用明文；编辑且头留空时靠 sourceId 解密库存密文。
     *
     * @param req 地址与可选鉴权 / 源 id / 传输
     * @return 成功或失败都脱敏；地址不合法时抛业务异常
     */
    public SourceProbeResp probe(SourceProbeReq req) {
        String transport = ToolChecks.transport(req.getTransport());
        String baseUrl = resolveProbeBaseUrl(req);
        String plain = resolveProbeAuth(req);
        long started = System.nanoTime();
        try {
            McpClient client = clients.require(transport);
            List<McpToolView> remote = client.listTools(baseUrl, plain);
            int count = remote.size();
            return SourceProbeResp.ok(elapsed(started), count, "可达，发现 " + count + " 个工具");
        } catch (RuntimeException ex) {
            log.warn("MCP 探测失败 reason={}", ex.getClass().getSimpleName());
            return SourceProbeResp.fail(elapsed(started), SyncText.safe(ex.getMessage(), plain));
        }
    }

    /**
     * 优先用请求里的 baseUrl；编辑未改地址时可回落到已存源。
     */
    private String resolveProbeBaseUrl(SourceProbeReq req) {
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            return ToolChecks.baseUrl(req.getBaseUrl());
        }
        if (req.getSourceId() != null) {
            return ToolChecks.baseUrl(lookup.requireSource(req.getSourceId()).getBaseUrl());
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "请填写 MCP 地址");
    }

    /**
     * 请求带了鉴权头则用明文；否则在编辑场景解密库存头。
     */
    private String resolveProbeAuth(SourceProbeReq req) {
        if (req.getAuthHeader() != null && !req.getAuthHeader().isBlank()) {
            return ToolChecks.optionalHeader(req.getAuthHeader());
        }
        if (req.getSourceId() == null) {
            return null;
        }
        ToolSource source = lookup.requireSource(req.getSourceId());
        if (source.getAuthHeaderCipher() == null || source.getAuthHeaderCipher().isBlank()) {
            return null;
        }
        // AAD 绑源 id，与其他源互换密文会解不开
        return secretStore.decrypt(source.getAuthHeaderCipher(), SecretContexts.toolSource(source.getId()));
    }

    /**
     * 解密和出站放在写入之前。失败时异常文案已经去掉明文头。
     */
    private List<McpToolView> pull(ToolSource source) {
        String plain = null;
        try {
            if (source.getAuthHeaderCipher() != null && !source.getAuthHeaderCipher().isBlank()) {
                plain = secretStore.decrypt(source.getAuthHeaderCipher(), SecretContexts.toolSource(source.getId()));
            }
            McpClient client = clients.require(source.getTransport());
            return client.listTools(source.getBaseUrl(), plain);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(SyncText.safe(ex.getMessage(), plain));
        }
    }

    /**
     * 按远端清单对齐本源工具项：先插入新名字，再更新同名同步字段，最后逻辑删除需下架的项。
     * 只处理 source 作用域内的数据，不触碰其他源与 BUILTIN 种子。
     *
     * @param source  已校验可同步的 MCP 源
     * @param remote  本次 tools/list
     * @param started 计时起点
     * @return 创建 / 更新 / 下架计数
     */
    private SyncResp apply(ToolSource source, List<McpToolView> remote, long started) {
        List<RemoteTool> tools = remote.stream()
                .map(view -> new RemoteTool(view.name(), view.title(), view.description(), view.inputSchemaJson()))
                .toList();
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
     */
    private SyncResp fail(ToolSource source, long started, RuntimeException ex) {
        String message = SyncText.safe(ex.getMessage(), null);
        log.warn("MCP 同步失败 sourceId={} reason={}", source.getId(), ex.getClass().getSimpleName());
        Long operatorId = operatorContext.requireUserId();
        sources.recordSync(source.getId(), LocalDateTime.now(), 0, message, operatorId);
        operLogWriter.write("tool", "sync", "tool_source", String.valueOf(source.getId()),
                "同步工具源失败 " + source.getName());
        return SyncResp.fail(elapsed(started), message);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
