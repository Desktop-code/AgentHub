package com.agenthub.runtime.domain.model;

import java.util.List;

/**
 * 工作流目标在工作台消息流里的执行卡片视图。所属域：runtime（展示契约）。
 *
 * <p>为什么不直接暴露工作流域的聚合：消息流只认「卡片要画什么」，
 * 不认 {@code workflow_run} / {@code workflow_node_run} 的字段形状。runtime 侧
 * 只保留这一份稳定契约，实时帧（SSE {@code workflow}）、落库事件（{@link EventType#WORKFLOW}）
 * 与刷新回放共用同一份字段，换引擎实现不必改前端。
 *
 * <p>边界：
 * <ul>
 *   <li>只来自现网真实运行事件（引擎节点状态迁移），<b>没有事件就不补进度</b>；</li>
 *   <li>不携带密钥 / 节点输入输出全文，失败说明只放已脱敏的短摘要；</li>
 *   <li>{@code status} 用大写枚举码（与 {@code WorkflowRunStatus} 同字面量），
 *       前端按它决定卡片的颜色与文案。</li>
 * </ul>
 *
 * @param name   工作流展示名（配置名；为空时前端回落到编码）
 * @param code   工作流编码（可空，仅作附注）
 * @param status 运行状态：CREATED / RUNNING / PAUSED_HUMAN / SUCCESS / FAILED / CANCELLED
 * @param answer 终答文本（成功后 END 节点抽出的文本，或失败 / 等待人工的说明）；运行中可为空
 * @param nodes  按图顺序的真实节点（已剔除 START / END，它们不是执行步骤）；无事件时为空表
 */
public record WorkflowRunView(String name, String code, String status, String answer, List<Node> nodes) {

    /** 防御性归一：前端只面对数组，不必逐处判空。 */
    public WorkflowRunView {
        name = name == null ? "" : name.trim();
        code = code == null ? "" : code.trim();
        status = status == null || status.isBlank() ? "RUNNING" : status.trim().toUpperCase();
        answer = answer == null ? "" : answer;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * 卡片上的一个执行步骤。
     *
     * @param code   节点编码（业务唯一）
     * @param name   节点展示名（配置名；为空时前端回落到编码）
     * @param type   节点类型：AGENT / TOOL / CONDITION / HUMAN / PARALLEL_FORK / PARALLEL_JOIN
     * @param status 节点状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / WAITING_HUMAN / CANCELLED
     * @param note   一句状态说明（执行中 / 已完成 / 失败原因 / 等待前置节点）；已脱敏
     */
    public record Node(String code, String name, String type, String status, String note) {

        /** 防御性归一：状态缺省按「未开始」，文案缺省为空串。 */
        public Node {
            code = code == null ? "" : code.trim();
            name = name == null ? "" : name.trim();
            type = type == null ? "" : type.trim().toUpperCase();
            status = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase();
            note = note == null ? "" : note;
        }
    }

    /**
     * 是否已经结束（不再需要转圈）。
     *
     * @return true 表示成功 / 失败 / 取消
     */
    public boolean finished() {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
