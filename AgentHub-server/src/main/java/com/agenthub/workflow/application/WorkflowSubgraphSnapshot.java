package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.model.WorkflowGraph;

/**
 * 子图绑定的不可变发布快照。所属域：workflow 的 application。
 *
 * <p>职责：把「SUBGRAPH 节点钉死的 releaseId」解析成执行真源——
 * 被调工作流名、版本与发布时的 graph_json。发布快照一旦取到就不再随草稿 / 新版本变化。
 *
 * <p>关键约束：只由 {@link WorkflowSubgraphResolver} 构造，调用方不得拿草稿图顶替。
 *
 * @param releaseId    发布版本主键
 * @param workflowId   被调工作流定义主键
 * @param workflowName 被调工作流展示名（可空，框标题兜底用编码）
 * @param workflowCode 被调工作流编码（可空）
 * @param version      发布版本号
 * @param graph        发布快照解析出的图（不可变使用）
 */
public record WorkflowSubgraphSnapshot(Long releaseId, Long workflowId, String workflowName,
                                       String workflowCode, Integer version, WorkflowGraph graph) {

    /**
     * 框标题：被调工作流名 + 版本（如 {@code 客服分流 · v3}）。
     * <p>名称缺失时用编码，再缺失时用定义 id；只给用户看，不拼内部路径。
     *
     * @return 非空标题
     */
    public String label() {
        String name = workflowName == null || workflowName.isBlank()
                ? (workflowCode == null || workflowCode.isBlank() ? String.valueOf(workflowId) : workflowCode)
                : workflowName.trim();
        return version == null ? name : name + " · v" + version;
    }
}
