package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 循环声明的口径。所属域：workflow 的 domain。
 *
 * <p>校验器、调度器和条件兜底共用这一处，避免「保存认的循环」和「运行认的循环」各写一套。
 * 不落库、不发布卡片。次数硬顶以平台配置为准，本类只规定绝对上限与默认建议值。
 */
public final class WorkflowLoopPolicy {

    /** 画布新建循环节点时的建议次数；保存时仍必须显式有值 */
    public static final int SUGGESTED_ITERATIONS = 3;

    /**
     * 平台硬顶的绝对上限。YAML 写得更大也会被夹回这里，图上的次数不能超过生效值。
     */
    public static final int PLATFORM_CAP = 20;

    /** 入边对「能不能开始跑」的影响 */
    public enum IncomingGate {
        /** 这条边不参与就绪判断（声明回边，避免第一次进入被自己挡住） */
        IGNORE,
        /** 上游还没把这条出口定下来，下游先别跑也别跳过 */
        BLOCK,
        /** 按普通入边：上游必须已经终态 */
        CHECK_TERMINAL
    }

    /** 超限之后怎么离开循环 */
    public enum OnExceeded {
        /** 走 LOOP_EXIT，到下游节点 */
        DOWNSTREAM,
        /** 走 LOOP_EXIT，终点必须是人工节点 */
        HUMAN,
        /** 结束本次运行为失败，不再画离开边 */
        FAIL;

        /**
         * @param raw 图上的 onExceeded，可空
         * @return 合法枚举；空或未知为 null
         */
        public static OnExceeded parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return OnExceeded.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private WorkflowLoopPolicy() {
    }

    /**
     * 条件节点的兜底边：原来的 DEFAULT，或指向循环节点的声明回边。
     *
     * @param type 边类型
     * @return true 表示这是「条件都不成立」时的那一条
     */
    public static boolean isFallbackEdge(WorkflowEdgeType type) {
        return type == WorkflowEdgeType.DEFAULT || type == WorkflowEdgeType.LOOP_BACK;
    }

    /**
     * 已声明的回边：类型是 LOOP_BACK，且终点是循环节点。其它回边仍算未声明的环。
     *
     * @param edge  边
     * @param types 节点 id → 类型
     * @return true 表示环检测应跳过这条边
     */
    public static boolean isDeclaredBackEdge(WorkflowEdge edge, Map<String, WorkflowNodeType> types) {
        if (edge == null || types == null) {
            return false;
        }
        if (WorkflowEdgeType.parse(edge.getType()) != WorkflowEdgeType.LOOP_BACK) {
            return false;
        }
        return types.get(edge.getTarget()) == WorkflowNodeType.LOOP;
    }

    /**
     * 读取图上填写的次数。缺失、小数、小于 1 都视为非法，由校验器拒绝保存。
     *
     * @param node 循环节点
     * @return 合法次数；缺失或非法为 null
     */
    public static Integer configuredIterations(WorkflowNode node) {
        JsonNode config = node == null ? null : node.getConfig();
        if (config == null || !config.isObject() || !config.hasNonNull("maxIterations")) {
            return null;
        }
        JsonNode raw = config.get("maxIterations");
        if (!raw.isIntegralNumber()) {
            return null;
        }
        int value = raw.intValue();
        return value >= 1 ? value : null;
    }

    /**
     * 字段在不在。用来区分「没填」和「填了但不是正整数」。
     *
     * @param node 循环节点
     * @return true 表示 config 里有 maxIterations
     */
    public static boolean iterationsPresent(WorkflowNode node) {
        JsonNode config = node == null ? null : node.getConfig();
        return config != null && config.isObject() && config.has("maxIterations")
                && !config.get("maxIterations").isNull();
    }

    /**
     * @param node 循环节点
     * @return 超限出路；没填或未知为 null
     */
    public static OnExceeded onExceeded(WorkflowNode node) {
        return OnExceeded.parse(WorkflowNodeConfig.textValue(
                node == null ? null : node.getConfig(), "onExceeded"));
    }

    /**
     * 打回时从上下文取反馈的路径。不配则用打回源节点的产出。
     *
     * @param node 循环节点
     * @return 路径；没配为 null
     */
    public static String feedbackPath(WorkflowNode node) {
        return WorkflowNodeConfig.textValue(node == null ? null : node.getConfig(), "feedbackPath");
    }

    /**
     * 本轮实际最多进入循环体几次：图上次数与平台生效硬顶先到先停。
     *
     * @param configured 图上次数，调用前应已是正整数
     * @param platformCap 平台生效硬顶（已夹到 {@link #PLATFORM_CAP}）
     * @return 实际次数
     */
    public static int effectiveIterations(int configured, int platformCap) {
        int cap = platformCap < 1 ? PLATFORM_CAP : Math.min(platformCap, PLATFORM_CAP);
        return Math.min(Math.max(configured, 1), cap);
    }

    /**
     * 循环节点这条超限出口是否还不能当「已判定」。
     *
     * <p>循环还在转（最近一轮选的是进入循环体）时，离开边既没被选中、也还没被放弃。
     * 若这时把下游标成跳过，次数用尽后就没有可走的出口了。
     *
     * @param edge   入边
     * @param source 上游最新一轮运行态
     * @return 调度该怎么对待这条入边
     */
    public static IncomingGate incomingGate(WorkflowEdge edge, WorkflowNodeRun source) {
        WorkflowEdgeType type = WorkflowEdgeType.parse(edge == null ? null : edge.getType());
        if (type == WorkflowEdgeType.LOOP_BACK) {
            return IncomingGate.IGNORE;
        }
        if (type == WorkflowEdgeType.LOOP_EXIT && exitStillOpen(source)) {
            return IncomingGate.BLOCK;
        }
        return IncomingGate.CHECK_TERMINAL;
    }

    /**
     * 上游循环是否还会再选超限出口。
     *
     * @param source 循环节点最新一轮；非成功或已经写了 EXIT 则视为定下来了
     * @return true 表示下游先等着
     */
    public static boolean exitStillOpen(WorkflowNodeRun source) {
        if (source == null) {
            return false;
        }
        WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(source.getStatus());
        if (status == null || !status.terminal()) {
            return true;
        }
        if (status != WorkflowNodeRunStatus.SUCCESS) {
            return false;
        }
        String output = source.getOutputJson();
        if (output == null || output.isBlank()) {
            return true;
        }
        // 只认本类写出的 decision 字段，避免把正文里的 EXIT 字样误判成已经离开
        return !output.contains("\"decision\":\"EXIT\"") && !output.contains("\"decision\": \"EXIT\"");
    }
}
