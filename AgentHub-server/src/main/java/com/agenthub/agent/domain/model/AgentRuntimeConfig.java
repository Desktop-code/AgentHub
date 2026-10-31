package com.agenthub.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

/**
 * Agent 运行配置（JSON 落库到 agent_def.runtime_config_json）。所属域：agent。
 * 一份结构同时承载 ROUTER 的路由开关与 PLAN_AND_EXECUTE 的计划开关：
 * 按运行模式只读取自己那组字段，另一组保持默认值，互不覆盖。
 * 空库值由 {@link #defaultsFor(String)} 补齐；硬顶见 {@link #MAX_ROUTE_CHILDREN_CAP} 与
 * {@link #MAX_PLAN_STEPS_CAP}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRuntimeConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 平台硬顶：每轮最多并行子 Agent 数 */
    public static final int MAX_ROUTE_CHILDREN_CAP = 5;

    /** 默认每轮最多子数 */
    public static final int DEFAULT_MAX_ROUTE_CHILDREN = 3;

    /** 计划步数默认上限 */
    public static final int DEFAULT_MAX_PLAN_STEPS = 8;

    /** 计划步数平台硬顶。再多就不是「一份可读的计划」，而且串行执行必然撞墙钟 */
    public static final int MAX_PLAN_STEPS_CAP = 20;

    /** 单步失败重试平台硬顶 */
    public static final int MAX_STEP_RETRIES_CAP = 3;

    /** 允许多意图路由；新建 ROUTER 默认 true */
    private Boolean multiIntentEnabled;

    /** CONCAT / SUMMARIZE；默认 CONCAT */
    private String answerMode;

    /** 每轮最多 N 个子；空则默认 3，写入时夹到 1～硬顶 */
    private Integer maxRouteChildren;

    /**
     * PLAN_AND_EXECUTE：true＝规划后停住，等试跑页「批准」再执行；false＝规划完直接执行。
     * 默认 false，即新建即为「自动执行」。
     */
    private Boolean requirePlanConfirm;

    /** PLAN_AND_EXECUTE：计划步数上限，夹到 1～{@link #MAX_PLAN_STEPS_CAP} */
    private Integer maxPlanSteps;

    /** PLAN_AND_EXECUTE：单步失败重试次数，夹到 0～{@link #MAX_STEP_RETRIES_CAP} */
    private Integer maxStepRetries;

    /**
     * 按运行模式给出默认配置。三种模式都返回完整对象，便于前端统一绑定。
     *
     * @param runMode REACT / ROUTER / PLAN_AND_EXECUTE
     */
    public static AgentRuntimeConfig defaultsFor(String runMode) {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        // ROUTER 新建默认开多意图；其它模式该字段无意义但保持同结构
        config.setMultiIntentEnabled(true);
        config.setAnswerMode(RouterAnswerMode.CONCAT.name());
        config.setMaxRouteChildren(DEFAULT_MAX_ROUTE_CHILDREN);
        // 计划默认全自动执行，避免新建 Agent 首次试跑卡在等人点批准
        config.setRequirePlanConfirm(false);
        config.setMaxPlanSteps(DEFAULT_MAX_PLAN_STEPS);
        config.setMaxStepRetries(0);
        return config;
    }

    /**
     * 解析库内 JSON；空或非法则按模式默认。
     *
     * @param raw     runtime_config_json
     * @param runMode 当前运行模式
     * @return 已规范化的配置
     */
    public static AgentRuntimeConfig parse(String raw, String runMode) {
        AgentRuntimeConfig defaults = defaultsFor(runMode);
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        try {
            AgentRuntimeConfig parsed = JSON.readValue(raw.trim(), AgentRuntimeConfig.class);
            return normalize(parsed, defaults);
        } catch (Exception ex) {
            return defaults;
        }
    }

    /**
     * 校验并序列化；非法字段回落默认后入库。
     *
     * @param source  请求体，可空
     * @param runMode 运行模式
     * @return 可入库 JSON；始终非空对象串
     */
    public static String toStorageJson(AgentRuntimeConfig source, String runMode) {
        AgentRuntimeConfig normalized = normalize(source == null ? new AgentRuntimeConfig() : source,
                defaultsFor(runMode));
        try {
            return JSON.writeValueAsString(normalized);
        } catch (Exception ex) {
            try {
                return JSON.writeValueAsString(defaultsFor(runMode));
            } catch (Exception ignored) {
                return "{\"multiIntentEnabled\":true,\"answerMode\":\"CONCAT\",\"maxRouteChildren\":3,"
                        + "\"requirePlanConfirm\":false,\"maxPlanSteps\":8,\"maxStepRetries\":0}";
            }
        }
    }

    /**
     * @return 是否允许多意图（空视为 true，与新建默认一致）
     */
    public boolean multiIntentOn() {
        return multiIntentEnabled == null || Boolean.TRUE.equals(multiIntentEnabled);
    }

    /**
     * @return 终答模式枚举
     */
    public RouterAnswerMode answerModeEnum() {
        return RouterAnswerMode.from(answerMode);
    }

    /**
     * @return 夹紧后的每轮最多子数
     */
    public int maxRouteChildrenOrDefault() {
        int n = maxRouteChildren == null ? DEFAULT_MAX_ROUTE_CHILDREN : maxRouteChildren;
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_ROUTE_CHILDREN_CAP);
    }

    /**
     * @return 规划后是否需要人工批准才执行；空视为 false（自动执行）
     */
    public boolean requirePlanConfirmOn() {
        return Boolean.TRUE.equals(requirePlanConfirm);
    }

    /**
     * @return 夹紧后的计划步数上限
     */
    public int maxPlanStepsOrDefault() {
        int n = maxPlanSteps == null ? DEFAULT_MAX_PLAN_STEPS : maxPlanSteps;
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_PLAN_STEPS_CAP);
    }

    /**
     * @return 夹紧后的单步失败重试次数
     */
    public int maxStepRetriesOrDefault() {
        int n = maxStepRetries == null ? 0 : maxStepRetries;
        if (n < 0) {
            return 0;
        }
        return Math.min(n, MAX_STEP_RETRIES_CAP);
    }

    private static AgentRuntimeConfig normalize(AgentRuntimeConfig source, AgentRuntimeConfig defaults) {
        AgentRuntimeConfig out = new AgentRuntimeConfig();
        out.setMultiIntentEnabled(source.getMultiIntentEnabled() == null
                ? defaults.getMultiIntentEnabled()
                : source.getMultiIntentEnabled());
        out.setAnswerMode(RouterAnswerMode.from(
                source.getAnswerMode() == null ? defaults.getAnswerMode() : source.getAnswerMode()).name());
        Integer max = source.getMaxRouteChildren() == null
                ? defaults.getMaxRouteChildren()
                : source.getMaxRouteChildren();
        if (max == null || max < 1) {
            max = DEFAULT_MAX_ROUTE_CHILDREN;
        }
        out.setMaxRouteChildren(Math.min(max, MAX_ROUTE_CHILDREN_CAP));
        out.setRequirePlanConfirm(source.getRequirePlanConfirm() == null
                ? defaults.getRequirePlanConfirm()
                : source.getRequirePlanConfirm());
        Integer steps = source.getMaxPlanSteps() == null ? defaults.getMaxPlanSteps() : source.getMaxPlanSteps();
        if (steps == null || steps < 1) {
            steps = DEFAULT_MAX_PLAN_STEPS;
        }
        out.setMaxPlanSteps(Math.min(steps, MAX_PLAN_STEPS_CAP));
        Integer retries = source.getMaxStepRetries() == null ? defaults.getMaxStepRetries() : source.getMaxStepRetries();
        if (retries == null || retries < 0) {
            retries = 0;
        }
        out.setMaxStepRetries(Math.min(retries, MAX_STEP_RETRIES_CAP));
        return out;
    }
}
