package com.agenthub.prompt.domain.enums;

/**
 * 提示词发布状态。所属域：prompt。
 * 与 prompt_global_constraint.status / prompt_template.status 取值一致。
 */
public enum PromptPublishStatus {

    /** 草稿：可改文案与段落，不可作为运行时生效源 */
    DRAFT(0),
    /** 已发布：可被切为 is_active=1；已发布行禁止原地改内容 */
    PUBLISHED(1);

    private final int code;

    PromptPublishStatus(int code) {
        this.code = code;
    }

    /**
     * @return 库内 tinyint 码
     */
    public int getCode() {
        return code;
    }

    /**
     * @param code 库内码
     * @return 枚举；未知码返回 null
     */
    public static PromptPublishStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (PromptPublishStatus item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}
