package com.agenthub.prompt.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * L1 生效约束快照。所属域：prompt。
 * 缓存单位：同 locale 至多一条生效行，因此只带版本与正文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptConstraintSnapshot {

    private String locale;
    private Integer version;
    /** 正文；禁止进日志全文 */
    @ToString.Exclude
    private String content;
}
