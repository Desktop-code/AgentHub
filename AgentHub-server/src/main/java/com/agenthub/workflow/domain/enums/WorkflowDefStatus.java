package com.agenthub.workflow.domain.enums;

/**
 * 工作流定义生命周期。所属域：workflow 的 domain。
 * <p>注意：这不是启停开关；停用/启用语义留给后续运营需求，不混进本字段。
 */
public enum WorkflowDefStatus {

    /** 从未发布过任何 release */
    DRAFT_ONLY,
    /** 至少有一个已发布版本；草稿仍可继续改 */
    HAS_RELEASE;

    /**
     * 新建工作流时的初始状态。
     *
     * @return DRAFT_ONLY
     */
    public static String initial() {
        return DRAFT_ONLY.name();
    }

    /**
     * 发布后的状态。
     *
     * @return HAS_RELEASE
     */
    public static String published() {
        return HAS_RELEASE.name();
    }
}
