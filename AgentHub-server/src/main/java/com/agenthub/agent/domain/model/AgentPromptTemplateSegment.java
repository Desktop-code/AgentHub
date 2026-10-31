package com.agenthub.agent.domain.model;

import lombok.Data;

/**
 * L3 表单里的单个段落视图。所属域：agent。
 * <p>
 * 字段口径来自 {@code prompt_template_segment}：
 * {@code mutable=0} 为 core（灰底只读），{@code mutable=1} 为 soft（可被 L3 覆盖）。
 * {@code displayName} 是表单 Label，前端禁止只展示英文 {@code segKey}。
 */
@Data
public class AgentPromptTemplateSegment {

    /** 段键；soft 段即 override 的提交键，core 段不可出现在 override 里 */
    private String segKey;
    /** Label，如「角色设定」 */
    private String displayName;
    /** 组装顺序 */
    private Integer sortNo;
    /** 0=core 锁定；1=soft 可覆盖 */
    private Integer mutable;
    /** 1=缺段阻断出站 */
    private Integer required;
    /** 段落正文；core 段在表单里只读展示 */
    private String content;
}
