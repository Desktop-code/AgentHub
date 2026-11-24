package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作流结果 → 工作台可展示文案。所属域：workflow 的 domain。
 *
 * <p>为什么单独一个纯函数类：同一段文案要在两处产出——
 * ① 工作台当轮同步执行完的终答；② 人工待办恢复 / 取消后在原会话追写的说明。
 * 两处必须同口径，否则用户会看到「同一份 run 两种说法」。
 *
 * <p>只读 {@code run.outputJson}（END 节点映射结果），不查库、不改状态。
 * <b>禁止</b>把整图 node 输出倒进对话框：优先取常见文本键，取不到才给一句简洁摘要。
 */
public final class WorkflowOutcomeTexts {

    /** 助手正文硬顶：工作流输出可能很长，超长按段落截断，避免一次灌满对话框 */
    private static final int ANSWER_MAX = 6000;

    /**
     * 整段运行超时的用户文案。
     *
     * <p>对最终用户只说「发生了什么、已停下」，<b>不</b>出现配置项名、毫秒或「撞限」。
     * 内部细节（生效上限、跑到哪一步）只进日志。
     */
    public static final String RUN_TIMEOUT_TEXT = "工作流运行超时，已停止";

    /** 单节点运行超时的用户文案；同样不带内部参数名与毫秒 */
    public static final String NODE_TIMEOUT_TEXT = "这一步运行超时，已停止";

    /** 调度异常（无进展等）的用户文案；内部原因写日志，不进对话 */
    public static final String RUN_ABORTED_TEXT = "工作流运行异常，已停止";

    /** 单节点触及执行预算（轮次 / 调用次数）的用户文案；同样不带内部键名 */
    public static final String NODE_LIMIT_TEXT = "这一步运行超过上限，已停止";

    /**
     * 把内部失败文案收成对话可见的一句话。
     *
     * <p>子 Agent 的预算 / 超时异常原文形如「撞限：timeout（试跑超时）」「撞限：max_llm_calls（超过 30 次）」，
     * 这些既含内部说法也含「撞限」，不能出现在工作流卡片与对话正文里。
     * 其它普通失败（绑定缺失、无返回文本等）原样返回——它们已经是给人看的说明。
     *
     * @param raw 节点失败原文；可空
     * @return 用户可见文案；空入参返回空串
     */
    public static String userText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.contains("撞限")) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // 超时与「达到轮次 / 调用上限」对用户是两件事，分别落到两句固定话术
        return lower.contains("timeout") ? NODE_TIMEOUT_TEXT : NODE_LIMIT_TEXT;
    }

    /** END 节点映射里常见的「人类可读答案」键，按优先级匹配（大小写不敏感） */
    private static final List<String> ANSWER_KEYS =
            List.of("answer", "result", "output", "text", "content", "message", "summary", "reply");

    private WorkflowOutcomeTexts() {
    }

    /**
     * 一档 run 的助手正文。
     *
     * <p>成功：优先 END 节点里的 answer / result / output 等文本，否则给「已运行完成」的简洁摘要；
     * 失败 / 取消：给原因（error_summary 已是引擎收敛后的中文摘要）；
     * 等待人工 / 仍在运行：给等待提示，不伪造答案。
     *
     * @param def  工作流定义；可为空（定义已删时仍要能展示）
     * @param run  run（状态以库里读回的为准）
     * @param json 全站 ObjectMapper
     * @return 非空正文
     */
    public static String assistantText(WorkflowDef def, WorkflowRun run, ObjectMapper json) {
        if (run == null) {
            return "工作流运行结果不可用，请稍后重试。";
        }
        String label = label(def, run);
        WorkflowRunStatus status = WorkflowRunStatus.parse(run.getStatus());
        if (status == WorkflowRunStatus.SUCCESS) {
            String answer = extractAnswer(run.getOutputJson(), json);
            if (answer != null) {
                return clip(answer);
            }
            return "工作流" + label + "已运行完成（版本 v" + version(run) + "），结束节点未返回文本输出。";
        }
        if (status == WorkflowRunStatus.FAILED) {
            String reason = run.getErrorSummary() == null || run.getErrorSummary().isBlank()
                    ? "节点执行失败，未产出结果" : run.getErrorSummary().trim();
            /*
             * 超时 / 异常停止已经是完整的一句话，再加「工作流「X」运行失败：」变成两层前缀。
             * 对用户只保留那一句（不含内部键名与毫秒）。
             */
            if (isStopNotice(reason)) {
                return clip(reason, 800);
            }
            return "工作流" + label + "运行失败：" + clip(reason, 800);
        }
        if (status == WorkflowRunStatus.CANCELLED) {
            String reason = run.getErrorSummary() == null || run.getErrorSummary().isBlank()
                    ? "运行已被取消" : run.getErrorSummary().trim();
            return "工作流" + label + "已取消：" + clip(reason, 400);
        }
        if (status == WorkflowRunStatus.PAUSED_HUMAN) {
            return waitingText(label);
        }
        if (status == WorkflowRunStatus.WAITING_REPLY) {
            return replyWaitingText(label);
        }
        return "工作流" + label + "仍在运行中，完成后结果会继续追加到本会话。";
    }

    /**
     * 是否是「已停止」类的收口文案（超时 / 调度异常）。
     *
     * <p>这类文案本身就写清了状态，调用方不要再套「运行失败：」前缀，
     * 也确保最终用户看到的正文里没有内部参数名。
     *
     * @param text 失败摘要；可空
     * @return true 表示可直接作为正文展示
     */
    public static boolean isStopNotice(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return RUN_TIMEOUT_TEXT.equals(trimmed)
                || NODE_TIMEOUT_TEXT.equals(trimmed)
                || NODE_LIMIT_TEXT.equals(trimmed)
                || RUN_ABORTED_TEXT.equals(trimmed);
    }

    /**
     * 人工待办等待提示：只给「去哪儿处理」，<b>不</b>在工作台里做审批。
     *
     * @param label 工作流称呼（带「」）
     * @return 中文提示
     */
    public static String waitingText(String label) {
        return "工作流" + label + "已运行到人工处理节点，正在等待人工处理。"
                + "请前往「编排 → 人工待办」处理；处理完成后结果会自动追加到本会话。";
    }

    /**
     * 「等待用户回复」提示：直接在本会话输入框接着说，不需要去待办页点通过。
     *
     * @param label 工作流称呼（带「」）
     * @return 中文提示
     */
    public static String replyWaitingText(String label) {
        return "工作流" + label + "正在等待你的回复；请直接在本会话下方输入框接着说，"
                + "这句话会送回同一条运行继续往下走。";
    }

    /**
     * 未结束 run 的防重提示：上一轮仍在跑 / 等人工时不允许再开第二 run。
     *
     * @param label 工作流称呼（带「」）
     * @param run   仍在进行中的 run
     * @return 中文提示
     */
    public static String inFlightText(String label, WorkflowRun run) {
        if (WorkflowRunStatus.parse(run.getStatus()) == WorkflowRunStatus.PAUSED_HUMAN) {
            return "工作流" + label + "上一轮仍在等待人工处理（「编排 → 人工待办」），本次没有重复发起；"
                    + "处理完成后结果会继续追加到本会话。";
        }
        if (WorkflowRunStatus.parse(run.getStatus()) == WorkflowRunStatus.WAITING_REPLY) {
            return "工作流" + label + "仍在等待你的回复，本次没有重复发起；"
                    + "请直接在本会话下方输入框接着说。";
        }
        return "工作流" + label + "上一轮仍在运行中，本次没有重复发起；"
                + "请等待完成后再追问，或换到其它线路。";
    }

    /**
     * 展示用工作流称呼：优先名称，其次编码，最后主键。
     *
     * @param def 定义；可空
     * @param run 运行实例（定义被删时用于兜底）
     * @return 形如「「报销审批」」的引号文案；都没有时返回空串
     */
    public static String label(WorkflowDef def, WorkflowRun run) {
        if (def != null && def.getName() != null && !def.getName().isBlank()) {
            return "「" + def.getName().trim() + "」";
        }
        if (def != null && def.getCode() != null && !def.getCode().isBlank()) {
            return "「" + def.getCode().trim() + "」";
        }
        Long id = run == null ? null : run.getWorkflowId();
        return id == null ? "" : "「#" + id + "」";
    }

    /**
     * 从 END 节点输出里抽人类可读文本。
     *
     * <p>输出形状由引擎决定：{@code {"<endCode>": {"answer":"..."}}}；
     * 未配 outputMapping 时是 {@code {"nodeCode":"...","input":{...}}}，此时没有答案可抽。
     *
     * @param outputJson run.output_json；可空
     * @param json       ObjectMapper
     * @return 文本；抽不到返回 null
     */
    public static String extractAnswer(String outputJson, ObjectMapper json) {
        if (outputJson == null || outputJson.isBlank() || json == null) {
            return null;
        }
        try {
            JsonNode root = json.readTree(outputJson);
            if (root == null || !root.isObject()) {
                return null;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                JsonNode value = fields.next().getValue();
                String text = textOf(value);
                if (text != null) {
                    return text;
                }
            }
            return null;
        } catch (RuntimeException | java.io.IOException ex) {
            // 输出是展示用旁路：解析失败只当作「没有文本答案」，不能把整轮打成失败
            return null;
        }
    }

    /**
     * 单个节点输出的文本：字符串直接用；对象按常见键优先，再退回单键文本值。
     *
     * @param node 节点输出
     * @return 非空文本；没有返回 null
     */
    private static String textOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            return text == null || text.isBlank() ? null : text.trim();
        }
        if (!node.isObject()) {
            return null;
        }
        for (String key : ANSWER_KEYS) {
            JsonNode candidate = fieldIgnoreCase(node, key);
            if (candidate != null && candidate.isTextual()) {
                String text = candidate.asText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        // 再退一层：有些结束节点把答案包在 output / result 子对象里
        for (String key : ANSWER_KEYS) {
            JsonNode candidate = fieldIgnoreCase(node, key);
            if (candidate != null && candidate.isObject()) {
                for (Iterator<JsonNode> it = candidate.elements(); it.hasNext(); ) {
                    JsonNode inner = it.next();
                    if (inner.isTextual() && !inner.asText().isBlank()) {
                        return inner.asText().trim();
                    }
                }
            }
        }
        return null;
    }

    /** 大小写不敏感取子字段：不同节点实现里键名大小写并不统一。 */
    private static JsonNode fieldIgnoreCase(JsonNode node, String wanted) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        String target = wanted.toLowerCase(Locale.ROOT);
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 发布版本展示：缺省时给 0，避免文案出现 null。 */
    private static int version(WorkflowRun run) {
        return run.getReleaseVersion() == null ? 0 : run.getReleaseVersion();
    }

    private static String clip(String text) {
        return clip(text, ANSWER_MAX);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max) + "\n\n（输出较长，已截断）";
    }
}
