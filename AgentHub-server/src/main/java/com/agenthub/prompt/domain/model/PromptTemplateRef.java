package com.agenthub.prompt.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生效模板的轻量引用。所属域：prompt。
 * 用于「按 purpose / 按 key 找当前生效版」这一跳的缓存：先拿引用，再按
 * {@code key + locale + version} 取完整快照，避免每次调用都查主表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplateRef {

    private Long templateId;
    private String templateKey;
    private String purpose;
    private String locale;
    private Integer version;
}
