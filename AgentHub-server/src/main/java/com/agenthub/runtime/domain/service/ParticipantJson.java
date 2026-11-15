package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ParticipantView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 参与者身份事件正文的序列化。所属域：runtime，纯逻辑。
 *
 * <p>为什么单独成类：同一份形状要同时喂三个消费者——SSE 出站帧、内存快照、
 * agent_run_event.content_text 落库回放。字段名就是前端契约，散落各处手拼迟早漂移。
 *
 * <p>形状：
 * <pre>
 * {"id":"gw-12","name":"企业综合台","kind":"gateway","routeKey":"policy","depth":0,"begin":true}
 * </pre>
 * {@code begin=true} 表示这一步真正开始（前端据此新建块）；{@code false} 只表示
 * 「后续增量归属该参与者」，不新建块。
 */
public final class ParticipantJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ParticipantJson() {
    }

    /**
     * 序列化一条身份事件。
     *
     * @param participant 参与者身份；空则返回 null
     * @param begin       true＝该步开始
     * @return JSON 串；入参为空时 null
     */
    public static String payload(ParticipantView participant, boolean begin) {
        if (participant == null || participant.id() == null || participant.id().isBlank()) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("id", participant.id());
        node.put("name", participant.name());
        node.put("kind", participant.kind());
        if (participant.routeKey() != null && !participant.routeKey().isBlank()) {
            node.put("routeKey", participant.routeKey());
        }
        node.put("depth", participant.depth());
        node.put("begin", begin);
        return node.toString();
    }

    /**
     * 反序列化一条身份事件正文；失败返回 null（回放时按旧数据继续，不让一轮历史整段消失）。
     *
     * @param raw 事件正文
     * @return 身份；解析失败时 null
     */
    public static ParticipantView parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            String id = node.path("id").asText("");
            if (id.isBlank()) {
                return null;
            }
            return new ParticipantView(id, node.path("name").asText(""),
                    node.path("kind").asText(ParticipantView.KIND_AGENT),
                    node.path("routeKey").asText(null),
                    node.path("depth").asInt(0));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 该身份事件是否为「开始」帧。
     *
     * @param raw 事件正文
     * @return true 表示 begin=true；解析失败时为 false
     */
    public static boolean isBegin(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return JSON.readTree(raw).path("begin").asBoolean(false);
        } catch (Exception ex) {
            return false;
        }
    }
}
