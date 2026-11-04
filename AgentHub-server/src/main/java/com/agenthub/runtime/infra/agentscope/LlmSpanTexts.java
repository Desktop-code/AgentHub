package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 步骤入参/出参的可读化。所属域：runtime 的 infra。
 * 入参只保留本轮增量（用户话 / 工具回灌），系统提示只记「平台前缀：已应用」；
 * 出参丢掉 {@code __fragment__} 与未完成的流式 tool_call 碎片。
 */
public final class LlmSpanTexts {

    /** 与 {@code PromptAssembler} 的业务段标记一致 */
    static final String BUSINESS_MARK = "【业务提示词】";

    /**
     * 业务段之后可能紧跟的段头。业务要点必须以这些标记为右边界，
     * 否则会把记忆 / Skill / 工具目录一起当成业务提示截进右栏。
     */
    private static final String[] NEXT_SECTION_MARKS = {
            "【工作记忆】", "【用户偏好】", "## Skill ", "## 强制 Skill ", "## 工具目录"
    };

    /** AgentScope 流式假工具名，禁止写入 output_text */
    static final String FRAGMENT_NAME = "__fragment__";

    /** 业务提示要点最长字符，避免右栏再刷长文 */
    private static final int BUSINESS_TIP_LIMIT = 80;

    private LlmSpanTexts() {
    }

    /**
     * 组装本轮 LLM 入参：平台前缀标记 +（首轮）业务提示要点 + 自上次助手回复之后的新消息。
     *
     * @param messages 交给模型的完整消息列表，可空
     * @return 打码前的入参正文；无可写内容时为 null
     */
    public static String buildInput(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("[SYSTEM]\n平台前缀：已应用");
        PromptInjectionStats injection = PromptInjectionHolder.get();
        if (injection != null) {
            // 一行给全「改了之后到底省在哪」：保护区 / 可压区 / 记忆 / Skill / 目录 / schema / 用户
            text.append("\ntools_injected=").append(injection.getToolsInjected())
                    .append(" skills_injected=").append(injection.getSkillsInjected())
                    .append(" skills_hard_triggered=").append(injection.getSkillsHardTriggered())
                    .append(" chars=").append(injection.totalChars())
                    .append(" tokens_est=").append(injection.totalTokens())
                    .append(" truncated=").append(injection.isTruncated());
            String breakdown = injection.breakdown();
            if (breakdown != null && !breakdown.isBlank()) {
                text.append("\n分段：").append(breakdown);
            }
        }
        boolean firstRound = !hasAssistant(messages);
        if (firstRound) {
            String tip = businessTip(messages);
            if (tip != null) {
                text.append("\n业务提示要点：").append(tip);
            }
        }
        int from = deltaStart(messages);
        for (int i = from; i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message == null || message.getRole() == MsgRole.SYSTEM) {
                continue;
            }
            String piece = formatDeltaMessage(message);
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            text.append('\n').append(piece);
        }
        return text.toString();
    }

    /**
     * 合并流式输出：文本分片追加；真实 tool_call 按 id 覆盖；丢弃 fragment。
     *
     * @param textOut   已累积的文本
     * @param toolCalls 完整 tool_call 展示串（按 id）
     * @return 终态 output_text；皆空时为 null
     */
    public static String mergeOutput(StringBuilder textOut, Map<String, String> toolCalls) {
        StringBuilder merged = new StringBuilder();
        if (textOut != null && textOut.length() > 0) {
            merged.append(textOut);
        }
        if (toolCalls != null) {
            for (String piece : toolCalls.values()) {
                if (piece == null || piece.isBlank()) {
                    continue;
                }
                if (merged.length() > 0) {
                    merged.append('\n');
                }
                merged.append(piece);
            }
        }
        return merged.length() == 0 ? null : merged.toString();
    }

    /**
     * 把一帧响应里的正文/工具写入缓冲。fragment 与空名工具调用直接跳过。
     *
     * @param content   本帧 content blocks
     * @param textOut   文本累积
     * @param toolCalls 按 callId 覆盖的完整 tool_call
     */
    public static void captureOutput(List<ContentBlock> content, StringBuilder textOut, Map<String, String> toolCalls) {
        if (content == null || content.isEmpty()) {
            return;
        }
        for (ContentBlock block : content) {
            if (block instanceof TextBlock textBlock) {
                String piece = textBlock.getText();
                if (piece != null && !piece.isEmpty()) {
                    textOut.append(piece);
                }
            } else if (block instanceof ToolUseBlock toolUse) {
                if (isFragment(toolUse)) {
                    continue;
                }
                String id = toolUse.getId() == null || toolUse.getId().isBlank()
                        ? "call-" + toolCalls.size()
                        : toolUse.getId();
                toolCalls.put(id, formatToolUse(toolUse));
            }
        }
    }

    /**
     * @param toolUse 工具调用块
     * @return 是否为流式碎片或假名，不应入库
     */
    public static boolean isFragment(ToolUseBlock toolUse) {
        if (toolUse == null) {
            return true;
        }
        String name = toolUse.getName();
        if (name == null || name.isBlank()) {
            return true;
        }
        if (FRAGMENT_NAME.equalsIgnoreCase(name.trim())) {
            return true;
        }
        // 名称本身带 fragment 也视为碎片
        return name.toLowerCase().contains("fragment");
    }

    /**
     * 根据终态输出给人话摘要；看不出就不编。
     *
     * @param outputText 已过滤的输出
     * @return 如「决定调用 queryWeather」「生成终答」；无法判断时为 null
     */
    public static String briefOf(String outputText) {
        if (outputText == null || outputText.isBlank()) {
            return null;
        }
        List<String> tools = new ArrayList<>();
        for (String line : outputText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("tool_call ") && trimmed.contains(" name=")) {
                String name = after(trimmed, " name=");
                if (name != null) {
                    int space = name.indexOf(' ');
                    tools.add(space > 0 ? name.substring(0, space) : name);
                }
            }
        }
        if (!tools.isEmpty()) {
            return "决定调用 " + String.join("、", tools);
        }
        String plain = outputText.trim();
        if (plain.isEmpty()) {
            return null;
        }
        return "生成终答";
    }

    static String formatToolUse(ToolUseBlock toolUse) {
        String name = toolUse.getName() == null ? "tool" : toolUse.getName();
        String id = toolUse.getId() == null ? "" : toolUse.getId();
        Object input = toolUse.getInput();
        return "tool_call id=" + id + " name=" + name + " input=" + String.valueOf(input);
    }

    private static boolean hasAssistant(List<Msg> messages) {
        for (Msg message : messages) {
            if (message != null && message.getRole() == MsgRole.ASSISTANT) {
                return true;
            }
        }
        return false;
    }

    /**
     * 增量起点：上次 ASSISTANT 之后；若没有助手消息，则从最后一条 USER 起。
     */
    private static int deltaStart(List<Msg> messages) {
        int lastAssistant = -1;
        for (int i = 0; i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() == MsgRole.ASSISTANT) {
                lastAssistant = i;
            }
        }
        if (lastAssistant >= 0) {
            return lastAssistant + 1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() == MsgRole.USER) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 首轮业务提示要点：取【业务提示词】标记之后到下一个段落标记之前的内容。
     * <p>
     * 业务段现在收口在 L3 追加区之后，其后紧接记忆 / Skill / 工具目录，
     * 因此不能「一直取到正文结尾」，否则右栏的业务要点会把偏好和工具清单一起带出来。
     *
     * @param messages 交给模型的完整消息列表
     * @return 压成一行的要点；无业务段时 null
     */
    private static String businessTip(List<Msg> messages) {
        for (Msg message : messages) {
            if (message == null || message.getRole() != MsgRole.SYSTEM) {
                continue;
            }
            String body = message.getTextContent();
            if (body == null) {
                continue;
            }
            int mark = body.indexOf(BUSINESS_MARK);
            if (mark < 0) {
                continue;
            }
            String business = body.substring(mark + BUSINESS_MARK.length());
            // 截到下一个段落标记：记忆 / Skill / 工具目录都不是「业务提示」的组成部分
            for (String next : NEXT_SECTION_MARKS) {
                int at = business.indexOf(next);
                if (at >= 0) {
                    business = business.substring(0, at);
                }
            }
            business = business.trim();
            if (business.isEmpty()) {
                return null;
            }
            String oneLine = business.replace('\n', ' ').trim();
            if (oneLine.length() <= BUSINESS_TIP_LIMIT) {
                return oneLine;
            }
            return oneLine.substring(0, BUSINESS_TIP_LIMIT) + "…";
        }
        return null;
    }

    private static String formatDeltaMessage(Msg message) {
        MsgRole role = message.getRole();
        String label = role == null ? "MSG" : role.name();
        if (role == MsgRole.TOOL) {
            String toolBody = toolResultText(message);
            if (toolBody != null) {
                return "[" + label + "]\n" + toolBody;
            }
        }
        String body = message.getTextContent();
        if (body != null && !body.isEmpty()) {
            return "[" + label + "]\n" + body;
        }
        String tools = toolUsesOf(message);
        if (tools != null) {
            return "[" + label + "]\n" + tools;
        }
        return null;
    }

    private static String toolResultText(Msg message) {
        if (message.getContent() == null || message.getContent().isEmpty()) {
            String plain = message.getTextContent();
            return plain == null || plain.isBlank() ? null : plain;
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock result) {
                String name = result.getName() == null ? "tool" : result.getName();
                String id = result.getId() == null ? "" : result.getId();
                String body = flattenBlocks(result.getOutput());
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("tool_result id=").append(id).append(" name=").append(name);
                if (body != null && !body.isBlank()) {
                    text.append('\n').append(body);
                }
            } else if (block instanceof TextBlock textBlock) {
                String piece = textBlock.getText();
                if (piece != null && !piece.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(piece);
                }
            }
        }
        if (text.length() > 0) {
            return text.toString();
        }
        String plain = message.getTextContent();
        return plain == null || plain.isBlank() ? null : plain;
    }

    private static String flattenBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                String piece = textBlock.getText();
                if (piece != null && !piece.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(piece);
                }
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static String toolUsesOf(Msg message) {
        if (message.getContent() == null || message.getContent().isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolUseBlock toolUse) {
                if (isFragment(toolUse)) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(formatToolUse(toolUse));
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static String after(String line, String marker) {
        int at = line.indexOf(marker);
        if (at < 0) {
            return null;
        }
        return line.substring(at + marker.length()).trim();
    }

    /**
     * 测试与调用方可用来新建空 map。
     *
     * @return 保序 map
     */
    public static Map<String, String> newToolCallMap() {
        return new LinkedHashMap<>();
    }
}
