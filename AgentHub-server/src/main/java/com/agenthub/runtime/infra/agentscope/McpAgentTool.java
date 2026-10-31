package com.agenthub.runtime.infra.agentscope;

import com.agenthub.common.text.UserFacingText;
import com.agenthub.tool.infra.mcp.McpCallException;
import com.agenthub.tool.infra.mcp.McpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 把单个 MCP 绑定项挂成 AgentScope 可调用工具。所属域：runtime 的 infra。
 * 每次 call 新建 SSE 会话并发 {@code tools/call}；失败返回错误块，不假装成功。
 * <p>
 * 失败文案的边界：错误块会同时进模型上下文与用户可见时间线，
 * 因此只回「服务暂时不可用」一类短句；远端地址、HTTP 状态码、异常类名只进日志。
 */
public final class McpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final String baseUrl;
    private final String authHeader;
    private final McpClient client;
    private final ObjectMapper json;

    /**
     * @param name        调用名（与 tool_item.name / 远端 name 一致）
     * @param description 一句话
     * @param parameters  已解析的 JSON Schema map；不可为 null
     * @param baseUrl     MCP SSE 入口
     * @param authHeader  已解密鉴权头，可空
     * @param client      出站客户端
     * @param json        序列化实参
     */
    public McpAgentTool(String name, String description, Map<String, Object> parameters,
                        String baseUrl, String authHeader, McpClient client, ObjectMapper json) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(parameters);
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
        this.client = client;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return name;
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return description;
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 出站 tools/call；鉴权/远端失败写成错误结果，供时间线与模型看见明确原因。
     *
     * @param param 引擎入参
     * @return 成功正文或错误块
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                String args = toArgsJson(param == null ? null : param.getInput());
                String output = client.callTool(baseUrl, authHeader, name, args);
                String text = output == null ? "" : output;
                return new ToolResultBlock(name, name,
                        List.of(TextBlock.builder().text(text).build()),
                        null, ToolResultState.SUCCESS);
            } catch (McpCallException ex) {
                // 远端报文/状态码只进日志：错误块会进模型上下文，等于把部署细节交给它复述
                log.warn("[Agent][mcp] tools/call 失败 tool={} err={}", name, ex.getMessage(), ex);
                return ToolResultBlock.error(name, "工具「" + name + "」暂时不可用，请稍后再试。");
            } catch (RuntimeException ex) {
                log.warn("[Agent][mcp] tools/call 异常 tool={} type={}", name,
                        ex.getClass().getSimpleName(), ex);
                return ToolResultBlock.error(name, UserFacingText.SERVICE_UNAVAILABLE);
            }
        });
    }

    private String toArgsJson(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
