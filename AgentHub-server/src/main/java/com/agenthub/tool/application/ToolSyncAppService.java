package com.agenthub.tool.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.api.dto.SourceProbeReq;
import com.agenthub.tool.api.dto.SourceProbeResp;
import com.agenthub.tool.api.dto.SyncResp;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.agenthub.tool.infra.mcp.McpToolView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private final ToolLookup lookup;
    private final ToolReferencePolicy references;
    private final McpClientFactory clients;
    private final SecretStore secretStore;
    private final ToolSyncWriter writer;

    /**
     * @param lookup      按 id 取值
     * @param references  只允许启用中的 MCP（SSE/HTTP）
     * @param clients     按 transport 选客户端
     * @param secretStore 解密可选鉴权头
     * @param writer      事务内的写库阶段（出站成功/失败各一次）
     */
    public ToolSyncAppService(ToolLookup lookup,
                              ToolReferencePolicy references, McpClientFactory clients, SecretStore secretStore,
                              ToolSyncWriter writer) {
        this.lookup = lookup;
        this.references = references;
        this.clients = clients;
        this.secretStore = secretStore;
        this.writer = writer;
    }

    /**
     * 同步入口：事务外完成解密与 tools/list，再交给写库阶段开事务。
     * 远端耗时不占数据库连接；失败仍落 last_sync_* 与操作日志。
     *
     * @param id 工具源 id
     * @return 成功或失败都返回脱敏结果，校验不通过时抛业务异常且不写同步列
     */
    public SyncResp sync(Long id) {
        ToolSource source = lookup.requireSource(id);
        references.assertSyncable(source);
        long started = System.nanoTime();
        List<McpToolView> remote;
        try {
            remote = pull(source);
        } catch (RuntimeException ex) {
            // 出站失败也要记同步状态；写库交给 writer 的独立事务，避免与已断开的出站混在一个长事务里
            return writer.fail(source, started, ex);
        }
        return writer.apply(source, remote, started);
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
     * 解密和出站都放在事务之外：远端可能耗时数秒或挂住连接，不应占着数据库连接等。
     * 失败时异常文案已经去掉明文头；写库阶段由 {@link ToolSyncWriter} 单独开事务。
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

    /** 毫秒耗时：探测与同步的结果都用同一口径。 */
    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
