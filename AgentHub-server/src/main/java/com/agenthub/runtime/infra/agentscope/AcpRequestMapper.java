package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.model.acl.UnifiedChatRequest;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedToolDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 消息 → 统一对话请求的映射器。所属域：runtime 的 infra。
 * 为什么单独成类：这一层只做「引擎结构 → Unified*」的翻译，最容易写错的是
 * <b>TOOL 消息要拆成一条一个 tool_result</b>（统一层靠 toolCallId 对齐），把它与流式翻帧混在一起难以复核。
 * 边界：只出现 AgentScope 与 Unified* 类型，厂商字段名一概不出现。
 * 线程：无状态可复用。
 */
final class AcpRequestMapper {

    /** base64 图片源缺少 media_type 时的兜底 MIME */
    private static final String DEFAULT_IMAGE_MIME = "image/png";

    private final ObjectMapper json;

    /**
     * @param json 序列化器，用于把引擎的 Map 参数序列化成统一层要的 JSON 字符串
     */
    AcpRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 组装统一请求。stream 固定 true：本桥只服务流式引擎。
     *
     * @param messages         引擎上下文，可空
     * @param tools            工具 schema，可空
     * @param options          生成参数，可空
     * @param allowTemperature 是否允许把温度带给厂商；不采样用途（嵌入 / 重排 / 语音 / 护栏）传 false，
     *                         此时即使引擎给了温度也不出站，避免厂商对无意义参数报错
     * @return 统一请求
     */
    UnifiedChatRequest toRequest(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options,
                                 boolean allowTemperature) {
        List<UnifiedMessage> converted = new ArrayList<>();
        if (messages != null) {
            for (Msg message : messages) {
                appendMessage(converted, message);
            }
        }
        return UnifiedChatRequest.builder()
                .messages(converted)
                .tools(toToolDefs(tools))
                // 不采样用途：temperature 留 null，由 Adapter 省略该字段（不传 0，避免被厂商当成显式采样）
                .temperature(allowTemperature && options != null ? options.getTemperature() : null)
                .maxTokens(maxTokensOf(options))
                .stream(true)
                .build();
    }

    /**
     * 单条引擎消息转统一消息。TOOL 角色可能一次带回多个结果，需拆分。
     *
     * @param out     输出列表
     * @param message 引擎消息，可空
     */
    private void appendMessage(List<UnifiedMessage> out, Msg message) {
        if (message == null || message.getRole() == null) {
            return;
        }
        if (message.getRole() == MsgRole.TOOL) {
            appendToolResults(out, message);
            return;
        }
        boolean assistant = message.getRole() == MsgRole.ASSISTANT;
        out.add(UnifiedMessage.builder()
                // 统一层只认小写角色名（system / user / assistant）
                .role(message.getRole().name().toLowerCase())
                .content(textAndThinking(message))
                // 图片只在入参里出现：出参永远是纯文本，因此这里只从引擎消息上取图
                .imageUrls(imageUrlsOf(message))
                .toolCalls(assistant ? toToolCalls(message) : List.of())
                .build());
    }

    /**
     * 取消息里的图片，统一成「URL 或 data URI」字符串交给 ACL。
     * 为什么在这里拍平而不是让 Adapter 认识 AgentScope 类型：防腐层的意义就是
     * 厂商与引擎结构都不外溢——Adapter 只该看到 {@code List<String>}。
     * 支持的形态：
     * <ul>
     *   <li>{@link URLSource}：http(s) 地址原样透传（本地 file:// 也照传，由上游决定是否可读）；</li>
     *   <li>{@link Base64Source}：还原成 {@code data:{mime};base64,{data}}，各厂商都认这种写法；</li>
     *   <li>{@link DataBlock}：按 source 走上面两条（新代码推荐的通用多媒体容器）。</li>
     * </ul>
     *
     * @param message 引擎消息
     * @return 图片列表；没有图片时返回空列表（不返回 null，调用方不必判空）
     */
    private List<String> imageUrlsOf(Msg message) {
        List<ContentBlock> blocks = message.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock image) {
                appendSource(urls, image.getSource());
            } else if (block instanceof DataBlock data) {
                appendSource(urls, data.getSource());
            }
        }
        return urls;
    }

    /**
     * 把一个媒体 source 拍平成 URL 字符串。无法识别的 source 直接跳过：
     * 宁可少一张图，也不要让整轮对话因为一张坏图失败。
     *
     * @param out    输出列表
     * @param source 图片源，可空
     */
    private void appendSource(List<String> out, Source source) {
        if (source instanceof URLSource url && url.getUrl() != null && !url.getUrl().isBlank()) {
            out.add(url.getUrl().trim());
            return;
        }
        if (source instanceof Base64Source base64
                && base64.getData() != null && !base64.getData().isBlank()) {
            // media_type 缺失时退化成 png：多数厂商只按内容解码，声明错也只是宽容度问题
            String mime = base64.getMediaType() == null || base64.getMediaType().isBlank()
                    ? DEFAULT_IMAGE_MIME : base64.getMediaType().trim();
            out.add("data:" + mime + ";base64," + base64.getData().trim());
        }
    }

    /**
     * 把一条 TOOL 消息里的每个 tool_result 拆成独立统一消息。
     * 统一层的 tool 消息以 toolCallId 对齐调用，一条消息只能承载一个结果。
     */
    private void appendToolResults(List<UnifiedMessage> out, Msg message) {
        List<ToolResultBlock> results = message.getContentBlocks(ToolResultBlock.class);
        if (results.isEmpty()) {
            // 没有结构化结果时退化成「整条消息文本」，避免上下文凭空少一段
            out.add(UnifiedMessage.builder().role("tool").content(textAndThinking(message)).build());
            return;
        }
        for (ToolResultBlock result : results) {
            out.add(UnifiedMessage.tool(result.getId(), joinText(result.getOutput())));
        }
    }

    /**
     * 取消息里的正文与思考。统一层没有独立思考字段，这里把思考并入正文，
     * 保证多轮工具对话时模型仍能看到上一轮推理痕迹。
     *
     * @param message 引擎消息
     * @return 拼接后的正文；空时返回 null
     */
    private String textAndThinking(Msg message) {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                builder.append(text.getText());
            } else if (block instanceof ThinkingBlock thinking && thinking.getThinking() != null) {
                builder.append(thinking.getThinking());
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * @return 内容块里的纯文本拼接；无文本返回空串
     */
    private String joinText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text && text.getText() != null) {
                builder.append(text.getText());
            }
        }
        return builder.toString();
    }

    /**
     * 助手消息里的工具调用块 → 统一工具调用。参数按统一层约定保持原始 JSON 字符串。
     */
    private List<UnifiedToolCall> toToolCalls(Msg message) {
        List<UnifiedToolCall> calls = new ArrayList<>();
        for (ToolUseBlock use : message.getContentBlocks(ToolUseBlock.class)) {
            calls.add(UnifiedToolCall.builder()
                    .id(use.getId())
                    .name(use.getName())
                    // 引擎同时存了 Map 与原始 content；优先用后者，避免二次序列化改动键序
                    .arguments(use.getContent() != null && !use.getContent().isBlank()
                            ? use.getContent() : writeMap(use.getInput()))
                    .build());
        }
        return calls;
    }

    /**
     * 引擎工具 schema → 统一工具定义。参数 Map 序列化成字符串，由 Adapter 自行解析。
     */
    private List<UnifiedToolDef> toToolDefs(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<UnifiedToolDef> defs = new ArrayList<>(tools.size());
        for (ToolSchema tool : tools) {
            defs.add(UnifiedToolDef.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .parametersJsonSchema(writeMap(tool.getParameters()))
                    .build());
        }
        return defs;
    }

    /**
     * @return Map 的 JSON 文本；空或序列化失败时给 "{}"，避免一条坏数据让整轮不可用
     */
    private String writeMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * @return 输出上限；引擎分开给了 maxTokens 与 maxCompletionTokens，取先出现的那个
     */
    private Integer maxTokensOf(GenerateOptions options) {
        if (options == null) {
            return null;
        }
        return options.getMaxTokens() != null ? options.getMaxTokens() : options.getMaxCompletionTokens();
    }

    /**
     * @return 空的参数容器；供调用方在解析失败时兜底使用
     */
    static Map<String, Object> emptyArguments() {
        return new HashMap<>();
    }
}
