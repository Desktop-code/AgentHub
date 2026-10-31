package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * L3 下拉里可选的 L2 模板版本（已发布）。所属域：agent。
 * <p>
 * 只暴露选择器需要的字段，不带段落正文——段落由
 * {@link AgentPromptTemplateView} 单独按钉死版拉取，避免一次把全量版本正文塞进响应。
 */
@Data
public class AgentPromptTemplateOption {

    /** 模板主键；保存时写入 prompt_template_id */
    private Long id;
    /** 业务键，如 CHAT_DEFAULT */
    private String templateKey;
    /** 展示名 */
    private String name;
    /** 用途码；与 Agent 侧用途对齐后才会出现在下拉里 */
    private String purpose;
    /** 语言 */
    private String locale;
    /** 版本号；保存时钉死该值 */
    private Integer version;
    /** 1 表示该版本当前生效（平台最新），仅用于下拉标注 */
    private Integer isActive;
    private LocalDateTime updateTime;
}
