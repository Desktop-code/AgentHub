package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提示词模板版本行（L3 下拉项）。所属域：agent。
 * 数据来自 prompt 域的已发布版本；草稿不会出现在列表里。
 */
@Data
public class PromptTemplateOptionResp {

    /** 版本行主键；保存时写入 prompt_template_id */
    private Long id;
    private String templateKey;
    private String name;
    /** 用途码，必须与当前配置用途一致 */
    private String purpose;
    private String locale;
    /** 版本号；保存时钉死 */
    private Integer version;
    /** 1 表示该版本当前生效（平台最新），仅用于下拉标注 */
    private Integer isActive;
    private LocalDateTime updateTime;
}
