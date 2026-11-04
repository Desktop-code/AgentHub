package com.agenthub.workflow.infra;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.model.ToolTransport;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.infra.mcp.McpClient;
import com.agenthub.tool.infra.mcp.McpClientFactory;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.service.WorkflowToolInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TOOL 节点执行实现。所属域：workflow 的 infra。
 * <p>只接 MCP 工具源（复用现网 {@link McpClient#callTool}）；BUILTIN 工具需要绕过模型
 * 反射调用 {@code @Tool} 方法，本刀不接线，明确报中文失败并进入「未接线清单」。
 * <p>密钥边界：鉴权头只在本类解密后直接传出站，不写日志、不进节点 output。
 */
@Component
public class McpWorkflowToolInvoker implements WorkflowToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(McpWorkflowToolInvoker.class);

    private final ToolItemRepository items;
    private final ToolSourceRepository sources;
    private final McpClientFactory clients;
    private final SecretStore secretStore;

    /**
     * @param items       工具项目
     * @param sources     工具源（地址 / 传输 / 鉴权密文）
     * @param clients     MCP 客户端工厂
     * @param secretStore 鉴权头解密
     */
    public McpWorkflowToolInvoker(ToolItemRepository items, ToolSourceRepository sources,
                                  McpClientFactory clients, SecretStore secretStore) {
        this.items = items;
        this.sources = sources;
        this.clients = clients;
        this.secretStore = secretStore;
    }

    /** {@inheritDoc} */
    @Override
    public String invoke(Long toolId, JsonNode args) {
        ToolItem item = items.findById(toolId)
                .orElseThrow(() -> new WorkflowNodeFailure("绑定的工具项不存在或已删除"));
        if (!RecordStatus.isEnabled(item.getStatus())) {
            throw new WorkflowNodeFailure("绑定的工具「" + item.getName() + "」已停用");
        }
        ToolSource source = sources.findById(item.getSourceId())
                .orElseThrow(() -> new WorkflowNodeFailure("工具源不存在，无法调用工具「" + item.getName() + "」"));
        if (!RecordStatus.isEnabled(source.getStatus())) {
            throw new WorkflowNodeFailure("工具源「" + source.getName() + "」已停用");
        }
        if (!ToolSourceType.MCP.name().equalsIgnoreCase(source.getType())) {
            throw new WorkflowNodeFailure("工具「" + item.getName() + "」属于 BUILTIN 源，"
                    + "TOOL 节点直调本刀未接线；请改用 AGENT 节点");
        }
        if (source.getBaseUrl() == null || source.getBaseUrl().isBlank()) {
            throw new WorkflowNodeFailure("MCP 源「" + source.getName() + "」未配置地址");
        }
        String transport = source.getTransport() == null || source.getTransport().isBlank()
                ? ToolTransport.SSE.name() : source.getTransport();
        McpClient client = clients.require(transport);
        String auth = null;
        if (source.getAuthHeaderCipher() != null && !source.getAuthHeaderCipher().isBlank()) {
            try {
                auth = secretStore.decrypt(source.getAuthHeaderCipher(),
                        SecretContexts.toolSource(source.getId()));
            } catch (RuntimeException ex) {
                // 只记录类型，不打印密文 / 明文
                log.warn("工作流 TOOL 节点鉴权头解密失败 sourceId={} type={}",
                        source.getId(), ex.getClass().getSimpleName());
                throw new WorkflowNodeFailure("MCP 源「" + source.getName() + "」鉴权头解密失败");
            }
        }
        String arguments = args == null || args.isNull() || args.isEmpty() ? "{}" : args.toString();
        try {
            String result = client.callTool(source.getBaseUrl().trim(), auth, item.getName(), arguments);
            if (result == null || result.isBlank()) {
                throw new WorkflowNodeFailure("工具「" + item.getName() + "」返回为空");
            }
            return result;
        } catch (WorkflowNodeFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("工作流 TOOL 节点调用失败 toolId={} sourceId={} type={}",
                    toolId, source.getId(), ex.getClass().getSimpleName());
            throw new WorkflowNodeFailure("工具「" + item.getName() + "」调用失败："
                    + (ex.getMessage() == null ? "未知错误" : ex.getMessage()), ex);
        }
    }
}
