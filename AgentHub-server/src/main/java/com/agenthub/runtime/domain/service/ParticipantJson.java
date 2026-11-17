package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ParticipantView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参与者身份事件正文的序列化。所属域：runtime，纯逻辑。
 *
 * <p>为什么单独成类：同一份形状要同时喂三个消费者——SSE 出站帧、内存快照、
 * agent_run_event.content_text 落库回放。字段名就是前端契约，散落各处手拼迟早漂移。
 *
 * <p>出站与落库分开：{@link #frame} 是对象，SSE 写入器只序列化这一次，前端一次
 * {@code JSON.parse} 就得到 begin。{@link #payload} 把同一份对象收成落库正文。
 * 先收成字符串再交给写入器，会再包一层引号，前端解析结果是字符串，begin 整帧被丢掉。
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
     * 身份帧的对象载荷，给 SSE 出站用。写入器再序列化一次即可。
     *
     * <p>不要先 {@link #payload} 收成字符串再交出站：写入器对字符串会再编码一次。
     *
     * @param participant 参与者身份；空 id 时返回空 Map（调用方据此跳过）
     * @param begin       true＝该步开始
     * @return 载荷对象；无法识别时为空 Map
     */
    public static Map<String, Object> frame(ParticipantView participant, boolean begin) {
        if (!identifiable(participant)) {
            return Map.of();
        }
        Map<String, Object> body = identity(participant);
        body.put("begin", begin);
        return body;
    }

    /**
     * 终态帧的对象载荷。begin 固定为 false，只收口已有块。
     *
     * @param participant 参与者身份；空 id 时返回空 Map
     * @param state       done / failed / aborted；空则退回纯归属帧
     * @param note        终态说明；可空
     * @return 载荷对象；无法识别时为空 Map
     */
    public static Map<String, Object> frame(ParticipantView participant, String state, String note) {
        if (!identifiable(participant)) {
            return Map.of();
        }
        String code = state == null ? "" : state.trim();
        if (code.isEmpty()) {
            return frame(participant, false);
        }
        Map<String, Object> body = identity(participant);
        // 终态永远不是 begin：它只收口已有块，绝不能新建块
        body.put("begin", false);
        body.put("state", code);
        if (note != null && !note.isBlank()) {
            body.put("note", note.trim());
        }
        return body;
    }

    /**
     * 序列化一条身份事件，给内存快照和落库正文。
     *
     * <p>这里的字符串只写进事件正文，回放按正文再解析一次。不要把这个字符串交给 SSE 写入器。
     *
     * @param participant 参与者身份；空则返回 null
     * @param begin       true＝该步开始
     * @return JSON 串；入参为空或写不出时 null
     */
    public static String payload(ParticipantView participant, boolean begin) {
        return write(frame(participant, begin));
    }

    /**
     * 序列化一条**参与者终态**身份事件（begin=false + state）。
     *
     * <p>为什么要单独一版：{@code begin=true} 只说明「开始了」，没有任何帧说明「结束了」。
     * 缺了终态，前端只能等整轮收尾才把所有卡从进行态放下来。终态帧沿用同一事件名与同一身份，
     * 只多两个可选字段，老前端不认识 state 时按纯归属帧处理，行为不变。
     *
     * @param participant 参与者身份；空则返回 null
     * @param state       done / failed / aborted；空则退回纯归属帧
     * @param note        终态说明（已脱敏）；可空
     * @return JSON 串；入参为空或写不出时 null
     */
    public static String payload(ParticipantView participant, String state, String note) {
        return write(frame(participant, state, note));
    }

    /**
     * 身份字段。routeKey 为空时不写，避免把空串当成有效键。
     *
     * @param participant 已确认有 id 的身份
     * @return 可继续追加 begin / state 的载荷
     */
    private static Map<String, Object> identity(ParticipantView participant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", participant.id());
        body.put("name", participant.name());
        body.put("kind", participant.kind());
        if (participant.routeKey() != null && !participant.routeKey().isBlank()) {
            body.put("routeKey", participant.routeKey());
        }
        body.put("depth", participant.depth());
        return body;
    }

    /**
     * 空 id 不能出帧，也不能落一条没有身份的正文。
     *
     * @param participant 参与者身份；可空
     * @return true 表示 id 非空
     */
    private static boolean identifiable(ParticipantView participant) {
        return participant != null && participant.id() != null && !participant.id().isBlank();
    }

    /**
     * 把对象载荷收成紧凑 JSON。空载荷返回 null，调用方跳过落库。
     *
     * @param body 对象载荷
     * @return JSON 串；空载荷或写失败时 null
     */
    private static String write(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            // 载荷只含字符串和数字，正常不会失败；失败时不落库，也不把异常抛进运行主链路
            return null;
        }
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
