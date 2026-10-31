package com.agenthub.prompt.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 组装用的段落快照。所属域：prompt。
 * <p>
 * 与 {@link PromptSegment} 的区别：本类只带组装需要的字段，可被 JSON 序列化进 Redis，
 * 因此不带任何审计列与懒加载引用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptSegmentSnapshot {

    /** 段键，L3 override 按它匹配 */
    private String segKey;
    /** 展示名，仅 L3 表单用，组装不注入 */
    private String displayName;
    /** 组装顺序，越小越前 */
    private Integer sortNo;
    /** 0=core 锁定；1=soft 可被 L3 覆盖 */
    private Integer mutable;
    /** 1=缺段必须阻断出站 */
    private Integer required;
    /** 段落正文；禁止进日志全文 */
    @ToString.Exclude
    private String content;

    /**
     * @return true 表示该段可被 L3 覆盖
     */
    public boolean soft() {
        return mutable != null && mutable == 1;
    }

    /**
     * @return true 表示缺该段必须阻断出站
     */
    public boolean mandatory() {
        return required != null && required == 1;
    }
}
