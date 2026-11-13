package com.agenthub.runtime.infra.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 截断回灌进模型的工具结果正文。所属域：runtime。
 * <p>
 * 一期：去首尾空白 → 可解析 JSON 则 compact → 按上限硬截并附标记。
 * 若 compact 后是「对象数组」（典型如 {@code searchKnowledgeBase} 的分条命中），
 * 优先按<b>条</b>保留成可引用的分段摘要（命中条数不变、JSON 仍完整），
 * 只有连一条都放不下时才退回硬截——避免把 JSON 切成半截让模型误判「结果坏了」再搜一遍。
 * 只改送给模型的消息副本；obs / TOOL span 仍可保留脱敏全文。
 * 本类是唯一入口，禁止再平行造 Cleaner。
 */
public final class ToolResultClipper {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 压缩单条 content 时至少留出的正文字符，小于此值说明预算不够，放弃压缩 */
    private static final int MIN_SHRINK_CHARS = 40;

    /** 数组省略提示的预留长度（含引号与逗号），用于估算预算 */
    private static final int OMIT_NOTE_RESERVE = 40;

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
     * 单段工具结果正文闸门：trim → JSON compact → 分条摘要 → 硬截断。
     * <p>
     * 分条摘要只对「JSON 对象数组」生效（检索命中、日志分条等）：按条保留完整条目，
     * 最后一条允许压短 content，尾部用一条字符串元素说明省略条数。这样模型仍能引用
     * 前几条完整片段，而不是拿到半截 JSON 后误以为结果损坏、再去重搜。
     * <p>
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
        // 分条数组优先给「能直接引用的片段」，比硬切一半更适合检索类结果
        String excerpt = clipJsonArray(text, maxChars);
        if (excerpt != null) {
            return excerpt;
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
     * JSON 对象数组的分条摘要：整条保留，放不下的那一条压短 content，尾部注明省略条数。
     * <p>
     * 为什么只处理数组：检索命中、分页列表都是「一条一个对象」，按条取用最贴近专家的引用方式；
     * 单个大对象（配置、网页正文）没有条的概念，硬截仍是合理选择。
     *
     * @param text     已 compact 的正文
     * @param maxChars 上限
     * @return 长度 ≤ maxChars 的摘要；不是数组 / 一条都放不下时 null（交回硬截）
     */
    private static String clipJsonArray(String text, int maxChars) {
        char head = text.isEmpty() ? 0 : text.charAt(0);
        if (head != '[') {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception ignored) {
            // 半截 JSON 或非 JSON：按普通正文硬截
            return null;
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            return null;
        }
        List<JsonNode> kept = new ArrayList<>();
        int used = 2;
        for (JsonNode item : root) {
            String serialized = serialize(item);
            int sep = kept.isEmpty() ? 0 : 1;
            // 还要给「省略 N 条」留位置；能整条塞下就整条保留，保真度最高
            if (used + sep + serialized.length() + (root.size() > 1 ? OMIT_NOTE_RESERVE : 0) <= maxChars) {
                kept.add(item);
                used += sep + serialized.length();
                continue;
            }
            // 整条放不下：压短这一条的 content，让专家至少拿到一条可用片段
            int room = maxChars - used - sep - OMIT_NOTE_RESERVE;
            JsonNode shrunk = shrink(item, room);
            if (shrunk != null) {
                kept.add(shrunk);
            }
            break;
        }
        if (kept.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(serialize(kept.get(i)));
        }
        int omitted = root.size() - kept.size();
        if (omitted > 0) {
            // 用字符串元素承载省略提示：数组仍是合法 JSON，模型也不会把它当成一条命中
            sb.append(',').append(serialize(JSON.getNodeFactory().textNode(
                    "…（其余 " + omitted + " 条已省略，如需更多可缩小 topK 或换更精确的检索词）")));
        }
        sb.append(']');
        String out = sb.toString();
        // 尾条压缩后仍可能超限：放弃分条，让硬截保证上限
        return out.length() <= maxChars ? out : null;
    }

    /**
     * 压短单条对象的 {@code content} 字段，其余字段（标题、来源、分数）保持原样。
     *
     * @param item 单条命中
     * @param room 允许这条占用的字符预算
     * @return 压缩后的条目；预算不够或不是含 content 的对象时 null
     */
    private static JsonNode shrink(JsonNode item, int room) {
        if (item == null || !item.isObject() || !item.hasNonNull("content")) {
            return null;
        }
        if (!item.get("content").isTextual()) {
            return null;
        }
        String content = item.get("content").asText();
        ObjectNode copy = item.deepCopy();
        copy.put("content", "");
        // content 置空后剩下的骨架长度就是固定开销，预算减去它才是正文可用空间
        int skeleton = serialize(copy).length() + 20;
        int avail = room - skeleton;
        if (avail < MIN_SHRINK_CHARS || content.length() <= avail) {
            return null;
        }
        copy.put("content", content.substring(0, avail) + "…（后略）");
        return copy;
    }

    /**
     * 序列化单个节点；失败返回空串（调用方按「塞不下」处理）。
     *
     * @param node 节点
     * @return JSON 字符串
     */
    private static String serialize(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception ignored) {
            return "";
        }
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
