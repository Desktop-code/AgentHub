package com.agenthub.runtime.infra.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 截断回灌进模型的工具结果正文。所属域：runtime。
 * 一期：去首尾空白 → 可解析 JSON 则 compact → 按上限硬截并附标记。
 * 只改送给模型的消息副本；obs / TOOL span 仍可保留脱敏全文。
 * 本类是唯一入口，禁止再平行造 Cleaner。
 */
public final class ToolResultClipper {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultClipper() {
    }

    /**
     * @param messages 原始消息，可空
     * @param maxChars 单条工具结果最大字符；≤0 表示不截、也不做 trim/compact
     * @return 可能替换了 ToolResultBlock 的新列表；无需改写时返回原列表
     */
    public static List<Msg> clip(List<Msg> messages, int maxChars) {
        if (messages == null || messages.isEmpty() || maxChars <= 0) {
            return messages;
        }
        boolean changed = false;
        List<Msg> out = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            Msg next = clipOne(msg, maxChars);
            if (next != msg) {
                changed = true;
            }
            out.add(next);
        }
        return changed ? out : messages;
    }

    /**
     * 单段工具结果正文闸门：trim → JSON compact → 硬截断。
     * 截断后总长（含标记）≤ maxChars；标记含截断前 totalChars。
     *
     * @param raw      原始正文，可空
     * @param maxChars 上限；≤0 原样返回
     * @return 可回灌模型的正文；空输入返回空串
     */
    public static String clipText(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        if (maxChars <= 0) {
            return raw;
        }
        // 先去首尾空白；可解析为 JSON 时去掉美化空白，再与上限比较
        String text = compactIfJson(raw.trim());
        if (text.length() <= maxChars) {
            return text;
        }
        int totalChars = text.length();
        String mark = truncationMark(totalChars);
        int room = maxChars - mark.length();
        if (room < 1) {
            // 上限极小时仍保证带截断语义，优先保留标记前缀
            return mark.length() <= maxChars ? mark : mark.substring(0, maxChars);
        }
        return text.substring(0, room) + mark;
    }

    /**
     * 截断标记。含截断前总字符，便于日志与时间线对照。
     *
     * @param totalChars 截断前正文长度
     * @return 例如 {@code \n…[truncated, totalChars=12345]}
     */
    public static String truncationMark(int totalChars) {
        return "\n…[truncated, totalChars=" + totalChars + "]";
    }

    private static Msg clipOne(Msg msg, int maxChars) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
            return msg;
        }
        boolean changed = false;
        List<ContentBlock> blocks = new ArrayList<>(msg.getContent().size());
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock result) {
                ToolResultBlock clipped = clipResult(result, maxChars);
                if (clipped != result) {
                    changed = true;
                }
                blocks.add(clipped);
            } else {
                blocks.add(block);
            }
        }
        return changed ? msg.withContent(blocks) : msg;
    }

    private static ToolResultBlock clipResult(ToolResultBlock result, int maxChars) {
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return result;
        }
        boolean changed = false;
        List<ContentBlock> next = new ArrayList<>(output.size());
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text == null) {
                    next.add(block);
                    continue;
                }
                String clipped = clipText(text, maxChars);
                if (!clipped.equals(text)) {
                    next.add(TextBlock.builder().text(clipped).build());
                    changed = true;
                } else {
                    next.add(block);
                }
            } else {
                next.add(block);
            }
        }
        if (!changed) {
            return result;
        }
        return new ToolResultBlock(result.getId(), result.getName(), next, result.getMetadata(), result.getState());
    }

    /**
     * 整段可解析为 JSON 对象/数组时写成 compact；否则原样返回。
     */
    private static String compactIfJson(String text) {
        if (text.isEmpty()) {
            return text;
        }
        char head = text.charAt(0);
        if (head != '{' && head != '[') {
            return text;
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || (!node.isObject() && !node.isArray())) {
                return text;
            }
            return JSON.writeValueAsString(node);
        } catch (Exception ignored) {
            // 非 JSON 或半截正文：保留 trim 后原文，交给后续硬截
            return text;
        }
    }
}
