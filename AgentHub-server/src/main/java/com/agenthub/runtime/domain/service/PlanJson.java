package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepState;
import com.agenthub.runtime.domain.model.StepView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 计划与步骤事件正文的序列化/反序列化。所属域：runtime，纯逻辑。
 *
 * <p>为什么单独成类：同一份 JSON 要同时喂三个消费者——
 * SSE 出站帧、agent_run_event.content_text 落库、以及刷新后前端回放。
 * 三者字段必须完全一致，散落各处手拼字符串迟早会漂移，故在此集中一处。
 *
 * <p>形状（字段名即前端契约，改名属协议变更）：
 * <pre>
 * plan: {"planId":"...","state":"PENDING","steps":[{"seq":1,"title":"...","detail":"..."}]}
 * step: {"seq":1,"title":"...","state":"DONE","text":"该步结果"}
 * </pre>
 * 状态更新类 plan 事件不带 steps（前端沿用已有步骤），与 {@link PlanView#stateOnly} 一致。
 */
public final class PlanJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanJson() {
    }

    /**
     * @param plan 计划快照
     * @return JSON 串；序列化失败时返回最小可用结构，不抛异常打断试跑
     */
    public static String planPayload(PlanView plan) {
        ObjectNode root = JSON.createObjectNode();
        if (plan == null) {
            root.put("planId", "");
            root.put("state", PlanState.FAILED.name());
            root.set("steps", JSON.createArrayNode());
            return root.toString();
        }
        root.put("planId", plan.planId() == null ? "" : plan.planId());
        root.put("state", plan.state() == null ? PlanState.FAILED.name() : plan.state().name());
        ArrayNode steps = JSON.createArrayNode();
        for (PlanStep step : plan.steps()) {
            ObjectNode one = JSON.createObjectNode();
            one.put("seq", step.seq());
            one.put("title", step.title());
            one.put("detail", step.detail());
            steps.add(one);
        }
        root.set("steps", steps);
        return root.toString();
    }

    /**
     * @param step 单步快照
     * @return JSON 串
     */
    public static String stepPayload(StepView step) {
        ObjectNode root = JSON.createObjectNode();
        if (step == null) {
            return root.toString();
        }
        root.put("seq", step.seq());
        root.put("title", step.title());
        root.put("state", step.state() == null ? StepState.FAILED.name() : step.state().name());
        root.put("text", step.text());
        return root.toString();
    }

    /**
     * 回读计划事件正文。前端回放走同一份 JSON，这里供后端与单测校验形状一致。
     *
     * @param raw content_text
     * @return 计划视图；解析失败或非计划 JSON 时为空
     */
    public static Optional<PlanView> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            String planId = root.path("planId").asText("");
            PlanState state = stateOf(root.path("state").asText(""));
            List<PlanStep> steps = new ArrayList<>();
            JsonNode array = root.path("steps");
            if (array.isArray()) {
                for (JsonNode one : array) {
                    steps.add(new PlanStep(one.path("seq").asInt(steps.size() + 1),
                            one.path("title").asText(""), one.path("detail").asText("")));
                }
            }
            return Optional.of(new PlanView(planId, state, steps));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static PlanState stateOf(String raw) {
        for (PlanState state : PlanState.values()) {
            if (state.name().equals(raw)) {
                return state;
            }
        }
        // 状态缺失按「执行失败」处理：伪装成已完成会让界面显示错误结论
        return PlanState.FAILED;
    }
}
