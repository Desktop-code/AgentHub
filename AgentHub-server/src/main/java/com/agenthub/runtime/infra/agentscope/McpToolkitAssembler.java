package com.agenthub.runtime.infra.agentscope;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.runtime.domain.model.McpToolSpec;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.ToolEnablement;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.model.ToolTransport;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把绑定且启用的 MCP 工具注册进 AgentScope Toolkit。所属域：runtime 的 infra。
 * 同步成功 ≠ 本轮已挂可调用句柄：这里必须按 spec 建 {@link McpAgentTool}，失败抛明确错误。
 * 与装配侧同一判据 {@link ToolEnablement}：源停用则不注册，避免模型看到 schema 却无句柄。
 */
@Component
public class McpToolkitAssembler {

    private static final Logger log = LoggerFactory.getLogger(McpToolkitAssembler.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ToolSourceRepository sources;
    private final McpClientFactory clients;
    private final SecretStore secretStore;
    private final ObjectMapper json;

    /**
     * @param sources     取 base_url / 传输 / 鉴权密文
     * @param clients     按传输选 SSE / Streamable HTTP 客户端
     * @param secretStore 解密鉴权头
     * @param json        解析 input_schema_json
     */
    public McpToolkitAssembler(ToolSourceRepository sources, McpClientFactory clients,
                               SecretStore secretStore, ObjectMapper json) {
        this.sources = sources;
        this.clients = clients;
        this.secretStore = secretStore;
        this.json = json;
    }

    /**
     * 在已有 Toolkit 上追加 MCP 可调用句柄。
     * 与装配侧同一判据：源停在装配前仍可能被改，这里再校验一次；不可调用就直接不注册（不抛错）。
     *
     * @param toolkit 通常已含 BUILTIN
     * @param specs   预算内暴露的 MCP 绑定；可空
     * @throws TrialFailure 源存在但读不出来、传输不可用，或注册后引擎看不到该工具
     */
    public void register(Toolkit toolkit, List<McpToolSpec> specs) {
        if (toolkit == null || specs == null || specs.isEmpty()) {
            return;
        }
        // 同一源下多个工具只解析一次；空值表示该源本轮不可注册，用 containsKey 区分「已解析为空」
        Map<Long, Optional<SourceHandle>> handles = new HashMap<>();
        for (McpToolSpec spec : specs) {
            String name = spec == null || spec.getName() == null ? "" : spec.getName().trim();
            if (name.isEmpty()) {
                throw new TrialFailure("MCP 工具调用名为空，无法注册");
            }
            if (spec.getSourceId() == null) {
                throw new TrialFailure("MCP 工具「" + name + "」缺少工具源，无法注册可调用句柄");
            }
            Optional<SourceHandle> handle = handles.computeIfAbsent(spec.getSourceId(), this::openHandle);
            // 源已停用或已删除：本轮不注册句柄，模型侧也不该有这个 schema
            if (handle.isEmpty()) {
                log.info("MCP 源不可用，跳过注册 tool={} sourceId={}", name, spec.getSourceId());
                continue;
            }
            SourceHandle source = handle.get();
            Map<String, Object> parameters = parametersOf(spec.getInputSchemaJson());
            toolkit.registerAgentTool(new McpAgentTool(
                    name,
                    spec.getDescription(),
                    parameters,
                    source.baseUrl(),
                    source.authHeader(),
                    source.client(),
                    json));
            if (toolkit.getTool(name) == null) {
                throw new TrialFailure("MCP 工具「" + name + "」注册后不可调用");
            }
        }
    }

    /**
     * 装配阶段校验源可用并解密鉴权；真正 tools/call 延迟到模型发起时。
     *
     * @param sourceId 工具源 id
     * @return 源不可用或已停用时为空，调用方据此跳过注册
     * @throws TrialFailure 源存在但类型不是 MCP、缺地址、传输不可用或鉴权头解密失败
     */
    private Optional<SourceHandle> openHandle(Long sourceId) {
        Optional<ToolSource> found = sources.findById(sourceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ToolSource source = found.get();
        // 源停用：不进 schema 也不执行，返回空让调用方跳过，不当作装配失败
        if (!ToolEnablement.sourceOn(source.getStatus())) {
            return Optional.empty();
        }
        if (!ToolSourceType.MCP.name().equalsIgnoreCase(source.getType())) {
            throw new TrialFailure("工具源「" + source.getName() + "」不是 MCP，无法注册");
        }
        if (source.getBaseUrl() == null || source.getBaseUrl().isBlank()) {
            throw new TrialFailure("MCP 源「" + source.getName() + "」未配置地址，无法注册可调用句柄");
        }
        String transport = source.getTransport() == null || source.getTransport().isBlank()
                ? ToolTransport.SSE.name()
                : source.getTransport();
        McpClient client;
        try {
            client = clients.require(transport);
        } catch (RuntimeException ex) {
            // MCP 传输不可用：带栈打，才能看出是协议未注册还是客户端构造失败
            log.warn("MCP 传输不可用 sourceId={} transport={} type={} cause={}",
                    sourceId, transport, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            throw new TrialFailure("MCP 源「" + source.getName() + "」传输不可用：" + safe(ex.getMessage()),
                    LimitHitReasons.ERROR, ex);
        }
        String auth = null;
        if (source.getAuthHeaderCipher() != null && !source.getAuthHeaderCipher().isBlank()) {
            try {
                // AAD 绑源 id，与写入侧一致
                auth = secretStore.decrypt(source.getAuthHeaderCipher(), SecretContexts.toolSource(source.getId()));
            } catch (RuntimeException ex) {
                // 只记类型与脱敏 cause：绝不打印密文或解密后的明文鉴权头
                log.warn("MCP 鉴权头解密失败 sourceId={} type={} cause={}",
                        sourceId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
                throw new TrialFailure("MCP 源「" + source.getName() + "」鉴权头解密失败，无法注册可调用句柄",
                        LimitHitReasons.ERROR, ex);
            }
        }
        return Optional.of(new SourceHandle(source.getBaseUrl().trim(), auth, client));
    }

    /**
     * 空/非法 schema 用最小 object 占位，保证 Toolkit 仍能暴露可调用工具。
     */
    private Map<String, Object> parametersOf(String schemaJson) {
        if (schemaJson != null && !schemaJson.isBlank()) {
            try {
                Map<String, Object> parsed = json.readValue(schemaJson, MAP);
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ex) {
                log.warn("MCP input_schema_json 非法，改用占位 schema type={}", ex.getClass().getSimpleName());
            }
        }
        Map<String, Object> empty = new HashMap<>();
        empty.put("type", "object");
        empty.put("properties", Map.of());
        return empty;
    }

    private static String safe(String message) {
        return message == null || message.isBlank() ? "未知原因" : message;
    }

    /**
     * @param baseUrl    MCP 入口（SSE 或 Streamable HTTP）
     * @param authHeader 已解密头，可空
     * @param client     出站客户端
     */
    private record SourceHandle(String baseUrl, String authHeader, McpClient client) {
    }
}
