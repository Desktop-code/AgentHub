package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 可加入默认接待路由池的「已发布工作流」选项（刀5B）。所属域：agent。
 *
 * <p>只列<b>真正能加</b>的定义：未删除、{@code status=HAS_RELEASE}、有默认发布指针、说明非空，
 * 且当前默认接待池里还没有它。口径与保存侧 {@code MaDefPolicy.assertWorkflowTargets} 一致，
 * 避免「下拉里能选、点了却 400」。
 *
 * <p>为什么单列一个 DTO 而不复用 {@link ReceptionSchemeOptionResp}：
 * 工作流没有方案模板，生命周期与默认发布指针是它独有的字段，混用会让前端只能靠猜。
 */
@Data
public class ReceptionWorkflowOptionResp {

    /** 工作流主键（workflow_def.id），添加候选时作为 workflowDefId 提交 */
    private Long id;
    private String name;
    private String code;
    /** 定义生命周期：HAS_RELEASE（本列表只会出现该值），前端只读展示 */
    private String status;
    /** 默认发布指针：工作台只跑这个版本的快照，禁跑草稿 */
    private Long defaultReleaseId;
    /** 工作流说明：加进池子后就是这条线路的意图说明（单一真源） */
    private String description;
}
