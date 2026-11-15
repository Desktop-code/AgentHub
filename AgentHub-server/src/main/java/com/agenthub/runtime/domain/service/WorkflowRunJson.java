package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
