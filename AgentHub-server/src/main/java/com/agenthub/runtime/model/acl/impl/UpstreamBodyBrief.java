package com.agenthub.runtime.model.acl.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 上游非 2xx 响应体的脱敏摘要。所属域：runtime（模型出站 ACL 的实现细节）。
 *
 * <p>为什么需要它：只看状态码时，400 可能是「图片过大」「不支持该 mime」「字段不合法」「模型不支持图片」
 * 等完全不同的原因，而这几种在排障处置上毫无共性——不看 body 就只能挨个猜。
 * 但 body 又不能原样落日志：中转常见回显请求片段，里面含 Authorization 与整段 base64。
 *
 * <p>脱敏规则（硬性）：
 * <ol>
 *   <li>能解析成 JSON 时优先取 {@code error.message} / {@code error} / {@code message}，摘要更短也更准；</li>
 *   <li>抹掉 Bearer 凭证、{@code sk-*} 形态 Key、{@code api_key=} 赋值；</li>
 *   <li>连续 40 字符以上的 base64 片段整体替换成占位，防止图片原文回显；</li>
 *   <li>折成单行并截断到 200 字，避免一条日志被响应体刷满。</li>
 * </ol>
 *
 * 线程安全：无状态静态方法；{@link ObjectMapper} 为线程安全实例。
 */
final class UpstreamBodyBrief {

    /** 摘要上限：够看清原因，又不至于把整段响应体搬进日志 */
    private static final int MAX_LENGTH = 200;

    /** 连续 base64 字符：图片/附件回显的主要形态 */
    private static final String BASE64_RUN = "[A-Za-z0-9+/]{40,}={0,2}";

    private static final ObjectMapper JSON = new ObjectMapper();

    private UpstreamBodyBrief() {
    }

    /**
     * 生成脱敏摘要。
     *
     * @param body 响应体原文，可空
     * @return 单行摘要；空体返回空串（调用方据此不再拼冒号）
     */
    static String of(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String picked = preferErrorMessage(body);
        String flat = picked.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        flat = flat.replaceAll("(?i)bearer\\s+\\S+", "Bearer ***");
        flat = flat.replaceAll("(?i)(api[-_]?key|authorization)\\s*[:=]\\s*\\S+", "$1=***");
        flat = flat.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "sk-***");
        flat = flat.replaceAll(BASE64_RUN, "<base64 已省略>");
        flat = flat.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_LENGTH ? flat : flat.substring(0, MAX_LENGTH - 1) + "…";
    }

    /**
     * 从 JSON 响应里挑出最有信息量的一句。解析失败时退回原文（多半是纯文本或半截 SSE）。
     *
     * @param body 响应体
     * @return 待脱敏的候选文本
     */
    private static String preferErrorMessage(String body) {
        String trimmed = body.trim();
        char head = trimmed.isEmpty() ? ' ' : trimmed.charAt(0);
        if (head != '{' && head != '[') {
            return trimmed;
        }
        try {
            JsonNode root = JSON.readTree(trimmed);
            if (root == null) {
                return trimmed;
            }
            JsonNode error = root.get("error");
            if (error != null) {
                // 两种常见形态：{"error":{"message":...}} 与 {"error":"..."}
                if (error.isObject()) {
                    JsonNode message = error.get("message");
                    if (message != null && !message.asText().isBlank()) {
                        return errorText(message.asText(), error);
                    }
                } else if (!error.asText().isBlank()) {
                    return error.asText();
                }
            }
            JsonNode message = root.get("message");
            if (message != null && !message.asText().isBlank()) {
                return message.asText();
            }
            return trimmed;
        } catch (Exception ex) {
            // 非 JSON：按原文处理，交给上层正则脱敏
            return trimmed;
        }
    }

    /**
     * 拼「message（code/type）」：只有 message 时常常缺「哪一类错」，补上 code 更好定位。
     *
     * @param message error.message 原文
     * @param error   error 节点，用于读取 type / code
     * @return 组合后的候选文本
     */
    private static String errorText(String message, JsonNode error) {
        StringBuilder text = new StringBuilder(message);
        for (String field : new String[]{"code", "type"}) {
            JsonNode node = error.get(field);
            if (node != null && !node.asText().isBlank()) {
                text.append(" [").append(node.asText()).append(']');
                break;
            }
        }
        return text.toString();
    }
}
