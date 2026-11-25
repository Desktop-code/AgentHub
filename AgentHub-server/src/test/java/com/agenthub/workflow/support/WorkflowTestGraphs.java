package com.agenthub.workflow.support;

import com.agenthub.workflow.domain.model.WorkflowRun;

/**
 * 工作流窄测的图 JSON 拼装工具。所属域：workflow 的 test support。
 * <p>只做字符串拼接，不重新实现 codec；测试要验证的是引擎对**已解析图**的调度行为。
 */
public final class WorkflowTestGraphs {

    private WorkflowTestGraphs() {
    }

    /**
     * @param nodeItems 已用逗号连接的节点片段
     * @param edgeItems 已用逗号连接的边片段
     * @return graph_json 文本
     */
    public static String graph(String nodeItems, String edgeItems) {
        return "{\"nodes\":[" + nodeItems + "],\"edges\":[" + (edgeItems == null ? "" : edgeItems) + "]}";
    }

    /**
     * @param id     节点 id
     * @param code   节点编码
     * @param type   节点类型
     * @param config config JSON 文本
     * @return 节点片段
     */
    public static String node(String id, String code, String type, String config) {
        return "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"type\":\"" + type
                + "\",\"name\":\"" + code + "\",\"config\":" + (config == null ? "{}" : config)
                + ",\"ui\":{\"x\":0,\"y\":0}}";
    }

    /**
     * @param id    边 id
     * @param from  起点节点 id
     * @param to    终点节点 id
     * @param type  边类型
     * @param config 边 config（条件边放 condition）
     * @return 边片段
     */
    public static String edge(String id, String from, String to, String type, String config) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + from + "\",\"target\":\"" + to
                + "\",\"type\":\"" + type + "\",\"config\":" + (config == null ? "{}" : config) + "}";
    }

    /**
     * 带运行时限的图：在 {@link #graph(String, String)} 的根上补一个 limits 对象。
     *
     * @param nodeItems   节点片段
     * @param edgeItems   边片段
     * @param runMinutes  整段上限（分钟）；<=0 表示不配
     * @param nodeMinutes 单节点上限（分钟）；<=0 表示不配
     * @return graph_json 文本
     */
    public static String graphWithLimits(String nodeItems, String edgeItems,
                                         int runMinutes, int nodeMinutes) {
        StringBuilder limits = new StringBuilder("{");
        if (runMinutes > 0) {
            limits.append("\"runMinutes\":").append(runMinutes);
        }
        if (nodeMinutes > 0) {
            limits.append(runMinutes > 0 ? "," : "").append("\"nodeMinutes\":").append(nodeMinutes);
        }
        limits.append("}");
        return "{\"nodes\":[" + nodeItems + "],\"edges\":[" + (edgeItems == null ? "" : edgeItems)
                + "],\"limits\":" + limits + "}";
    }

    /**
     * @param run        run 头
     * @param graphJson  图
     * @param inputJson  输入
     * @return 补全必填字段后的 run（未落库）
     */
    public static WorkflowRun run(WorkflowRun run, String graphJson, String inputJson) {
        run.setGraphJson(graphJson);
        run.setInputJson(inputJson == null ? "{}" : inputJson);
        return run;
    }
}
