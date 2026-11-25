package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.McpToolSpec;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 绑定且启用的 MCP 必须注册成 Toolkit 可调用句柄；源停用则不注册，避免 schema 与句柄不一致。
 */
@ExtendWith(MockitoExtension.class)
class McpToolkitAssemblerTest {

    @Mock
    private ToolSourceRepository sources;
    @Mock
    private McpClientFactory clients;
    @Mock
    private SecretStore secretStore;
    @Mock
    private McpClient mcpClient;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 小 schema MCP 注册后能在 Toolkit 按名取到。
     */
    @Test
    void registersBoundMcpAsCallableAgentTool() {
        when(sources.findById(20L)).thenReturn(Optional.of(mcpSource(20L)));
        when(clients.require("SSE")).thenReturn(mcpClient);
        McpToolkitAssembler assembler = new McpToolkitAssembler(sources, clients, secretStore, json);
        Toolkit toolkit = new Toolkit();
        McpToolSpec spec = new McpToolSpec();
        spec.setName("system_user_list");
        spec.setDescription("用户列表");
        spec.setInputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
        spec.setSourceId(20L);

        assembler.register(toolkit, List.of(spec));

        assertNotNull(toolkit.getTool("system_user_list"));
    }

    /**
     * 源停用时既不进 schema 也不注册句柄：必须静默跳过，不能抛错打断整轮试跑。
     * 装配侧已把它排除，这里是执行前的二次校验。
     */
    @Test
    void skipsRegistrationWhenSourceDisabled() {
        ToolSource source = mcpSource(20L);
        source.setStatus(0);
        when(sources.findById(20L)).thenReturn(Optional.of(source));
        McpToolkitAssembler assembler = new McpToolkitAssembler(sources, clients, secretStore, json);
        Toolkit toolkit = new Toolkit();
        McpToolSpec spec = new McpToolSpec();
        spec.setName("system_user_list");
        spec.setSourceId(20L);

        assertDoesNotThrow(() -> assembler.register(toolkit, List.of(spec)));
        assertNull(toolkit.getTool("system_user_list"));
    }

    /**
     * 源启用但缺地址仍属配置错误：保留可展示的装配失败，不静默跳过。
     */
    @Test
    void failsClearlyWhenEnabledSourceHasNoBaseUrl() {
        ToolSource source = mcpSource(20L);
        source.setBaseUrl(" ");
        when(sources.findById(20L)).thenReturn(Optional.of(source));
        McpToolkitAssembler assembler = new McpToolkitAssembler(sources, clients, secretStore, json);
        Toolkit toolkit = new Toolkit();
        McpToolSpec spec = new McpToolSpec();
        spec.setName("system_user_list");
        spec.setSourceId(20L);

        TrialFailure failure = assertThrows(TrialFailure.class, () -> assembler.register(toolkit, List.of(spec)));

        assertTrue(failure.getMessage().contains("未配置地址"));
    }

    private static ToolSource mcpSource(Long id) {
        ToolSource source = new ToolSource();
        source.setId(id);
        source.setName("RUOYI_MCP");
        source.setType("MCP");
        source.setTransport("SSE");
        source.setBaseUrl("http://127.0.0.1/sse");
        source.setStatus(1);
        return source;
    }
}
