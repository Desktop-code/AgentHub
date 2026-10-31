package com.agenthub.agent.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 某一个已发布 L2 版本及其段落，供 L3 表单渲染。所属域：agent。
 * <p>
 * 用途：配置页拿到后按 {@code mutable} 分栏——{@code 0} 灰底只读展示 core，
 * {@code 1} 生成输入框（Label 取 {@code displayName}，提交键取 {@code segKey}）。
 * <p>
 * 约束：只包含**已发布**版本；草稿不会出现在配置页，避免绑到永远不会生效的版本。
 */
@Data
public class AgentPromptTemplateView {

    /** 模板主键（即 prompt_template 的版本行主键） */
    private Long id;
    private String templateKey;
    private String name;
    /** 用途码；必须与 Agent / 绑定行的用途一致 */
    private String purpose;
    private String locale;
    /** 版本号；保存时钉死该值 */
    private Integer version;
    /** 1 表示该版本当前生效 */
    private Integer isActive;
    /** 段落，按 sort_no 升序 */
    private List<AgentPromptTemplateSegment> segments;
}
