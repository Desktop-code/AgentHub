package com.agenthub.obs.domain.model;

/**
 * 打开一个步骤。所属域：obs。
 * parentId 为空表示树根。inputText 可先空着，结束时再补输入/输出。
 * parallelFanout 为 true 时按并行分叉发 step_no（N.1 / N.2）。
 *
 * @param type           步骤类型。TASK 会被拒绝
 * @param name           展示名，受列宽限制
 * @param agentId        本步所属 Agent，可空
 * @param parentId       父行自增 id，可空
 * @param inputText      已产生的输入，可空
 * @param parallelFanout 同父并行扇出
 */
public record SpanDraft(SpanType type, String name, Long agentId, Long parentId, String inputText,
                        boolean parallelFanout) {

    /**
     * 非并行扇出的便捷构造。
     */
    public SpanDraft(SpanType type, String name, Long agentId, Long parentId, String inputText) {
        this(type, name, agentId, parentId, inputText, false);
    }
}
