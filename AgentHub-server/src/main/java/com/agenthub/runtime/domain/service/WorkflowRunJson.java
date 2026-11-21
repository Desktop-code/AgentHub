package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行卡片视图的 JSON 序列化。所属域：runtime，纯逻辑。
 *
 * <p>形状（字段名即前后端契约，改名属协议变更）：
 * <pre>
 * {"name":"制度要点与报告","code":"policy_brief_report","status":"RUNNING","answer":"",
 *  "nodes":[{"code":"policy_points","name":"知识问答","type":"AGENT",
 *            "status":"RUNNING","note":"执行中"}]}
 * </pre>
 *
 * <p>两条来源共用本类：实时 SSE {@code workflow} 帧与落库 {@code WORKFLOW} 事件正文。
 * 前端一套解析即可覆盖「实时推送 / 内存快照 / 刷新回放」。
 *
 * <p>线程安全：纯静态方法，只读入参。
 */
public final class WorkflowRunJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowRunJson() {
    }

    /**
     * 转成 SSE 帧载荷（对象形态，出站层再统一序列化一次）。
     *
     * @param view 执行卡片视图；可空
     * @return 载荷对象；视图为空时返回空 Map（调用方据此跳过出站）
     */
    public static Map<String, Object> payload(WorkflowRunView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (view == null) {
            return body;
        }
        body.put("name", view.name());
        body.put("code", view.code());
        body.put("status", view.status());
        body.put("answer", view.answer());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (WorkflowRunView.Node node : view.nodes()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("code", node.code());
            one.put("name", node.name());
            one.put("type", node.type());
            one.put("status", node.status());
            one.put("note", node.note());
            nodes.add(one);
        }
        body.put("nodes", nodes);
        return body;
    }

    /**
     * 写成落库事件正文（紧凑 JSON）。
     *
     * @param view 执行卡片视图；可空
     * @return JSON 字符串；视图为空或序列化失败时返回空串（调用方据此跳过落库）
     */
    public static String write(WorkflowRunView view) {
        if (view == null) {
            return "";
        }
        try {
            return JSON.writeValueAsString(payload(view));
        } catch (JsonProcessingException ex) {
            // 载荷只含字符串，正常不会失败；失败时宁可不落库，也不把异常抛进运行主链路
            return "";
        }
    }

    /**
     * 把一份仍在运行的工作流卡片快照改成「用户已停止」的终态。
     *
     * <p>用途：工作台停止一轮时，内存快照里最后一张卡片通常还写着 RUNNING、
     * 在途节点也还是「执行中」。取消接口已把库里的 run 写成 CANCELLED，
     * 这里让快照与库里的终态对齐，刷新后回放不会再把这一轮画成运行中。
     *
     * <p>边界：只改非终态快照；已经是 SUCCESS / FAILED / CANCELLED 的卡片原样返回，
     * 不把真实完成或真实失败改写成中断。JSON 不可解析时也原样返回，展示层走兜底。
     *
     * @param json 落库 / 内存快照里的卡片 JSON
     * @return 改动后的 JSON；入参为空或不可解析时原样返回
     */
    public static String cancelled(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return json;
            }
            String status = body.path("status").asText("").trim().toUpperCase();
            // 已经收口的卡片不再改写：成功、失败都是真实结果，不能因为停止请求晚到就改口
            if (isTerminal(status)) {
                return json;
            }
            body.put("status", "CANCELLED");
            JsonNode nodes = body.get("nodes");
            if (nodes instanceof ArrayNode list) {
                for (JsonNode item : list) {
                    if (!(item instanceof ObjectNode node)) {
                        continue;
                    }
                    String nodeStatus = node.path("status").asText("").trim().toUpperCase();
                    if ("RUNNING".equals(nodeStatus) || "WAITING_HUMAN".equals(nodeStatus)) {
                        // 与 WorkflowNodeRunRepository.cancelOpen 同一口径：在途节点落成 CANCELLED
                        node.put("status", "CANCELLED");
                        node.put("note", "已中断");
                    } else if ("PENDING".equals(nodeStatus)) {
                        // 还没轮到的节点与库里一样落成 SKIPPED，避免刷新后仍显示「等待前置节点」
                        node.put("status", "SKIPPED");
                        node.put("note", "已跳过");
                    }
                }
            }
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            // 展示用快照：解析失败时宁可不改，也不把异常抛进停止链路
            return json;
        }
    }

    /**
     * 卡片 JSON 里的状态码，大写；解析失败返回空串。
     *
     * <p>用于停止补落库时判断「库里这张卡是不是已经收口」，避免拿更早的直播快照
     * 覆盖库里已经完成 / 已取消的终态卡片。
     *
     * @param json 卡片 JSON
     * @return 大写状态码；空 / 不可解析时返回空串
     */
    public static String statusOf(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return "";
            }
            return body.path("status").asText("").trim().toUpperCase();
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 卡片标识：编码优先、名字兜底；同一轮里同名同编码视为同一张卡。
     *
     * @param json 卡片 JSON
     * @return 标识；空 / 不可解析时返回空串
     */
    public static String cardKey(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode body)) {
                return "";
            }
            String code = body.path("code").asText("").trim();
            return code.isEmpty() ? body.path("name").asText("").trim() : code;
        } catch (JsonProcessingException ex) {
            return "";
        }
    }

    /**
     * 是否终态。与 {@link #statusText(String)} 的分支保持一致。
     *
     * @param status 状态码；可空，大小写不敏感
     * @return true 表示 SUCCESS / FAILED / CANCELLED
     */
    public static boolean terminal(String status) {
        return isTerminal(status == null ? "" : status.trim().toUpperCase());
    }

    /**
     * 是否终态。与 {@link #statusText(String)} 的分支保持一致。
     *
     * @param status 大写状态码；可空
     * @return true 表示 SUCCESS / FAILED / CANCELLED
     */
    private static boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * 事件摘要：折叠 / 列表里的一句话，不进正文。
     *
     * @param view 执行卡片视图；可空
     * @return 形如「工作流「制度要点与报告」已完成」；视图为空时为空串
     */
    public static String summary(WorkflowRunView view) {
        if (view == null) {
            return "";
        }
        String name = view.name().isEmpty() ? view.code() : view.name();
        String label = name.isEmpty() ? "工作流" : "工作流「" + name + "」";
        return label + statusText(view.status());
    }

    /**
     * 运行状态 → 中文短语（卡片状态标签与事件摘要共用一份口径）。
     *
     * @param status 大写状态码；可空
     * @return 中文短语；未知状态按「运行中」处理，不伪造已完成
     */
    public static String statusText(String status) {
        String code = status == null ? "" : status.trim().toUpperCase();
        return switch (code) {
            case "SUCCESS" -> "已完成";
            case "FAILED" -> "运行失败";
            case "CANCELLED" -> "已取消";
            case "PAUSED_HUMAN" -> "等待人工处理";
            case "CREATED" -> "准备运行";
            default -> "运行中";
        };
    }
}
