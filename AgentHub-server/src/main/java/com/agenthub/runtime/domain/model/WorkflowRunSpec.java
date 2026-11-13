package com.agenthub.runtime.domain.model;

/**
 * 一条「工作流」路由目标的执行来源快照。所属域：runtime。
 *
 * <p>为什么不在执行器里直接读 {@code workflow_def}：路由一轮可能在池线程上跑多个子，
 * 执行路径上不允许边跑边查配置表；装载阶段一次性把可用目标翻译成这份只读快照，
 * 执行器只按 {@code workflowId} 委托给工作流域的运行入口。
 *
 * <p><b>只读且只绑默认发布</b>：{@code defaultReleaseId} 是装载时该定义的默认发布指针，
 * 运行入口会再按库里的最新指针解析 release；<b>永不</b>回落到草稿。
 *
 * @param workflowId        workflow_def.id
 * @param code              工作流编码（route_key 派生、时间线展示）
 * @param name              工作流名称（chip / 下拉 / 时间线文案）
 * @param description       工作流说明，意图说明唯一真源（不读 ma_candidate.intent_desc）
 * @param defaultReleaseId  装载时的默认发布指针；为空表示已不可运行（装载侧已挡掉）
 */
public record WorkflowRunSpec(Long workflowId, String code, String name, String description,
                              Long defaultReleaseId) {

    /**
     * 展示名：名称 → 编码 → 「工作流」兜底；永不返回 null，避免下游拼空串。
     *
     * @return 非空展示名
     */
    public String label() {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return "工作流";
    }
}
