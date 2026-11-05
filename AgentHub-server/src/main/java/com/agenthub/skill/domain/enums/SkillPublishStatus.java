package com.agenthub.skill.domain.enums;

/**
 * Skill 版本发布状态。所属域：skill。
 * 与 skill_version.publish_status 取值一致，与 prompt 域 PromptPublishStatus 同口径（不互相依赖）。
 */
public enum SkillPublishStatus {

    /** 草稿：可改正文/简介，不可作为运行时注入源 */
    DRAFT(0),
    /** 已发布：正文只读，可被设为唯一生效版本 */
    PUBLISHED(1);

    private final int code;

    SkillPublishStatus(int code) {
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
    public static SkillPublishStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (SkillPublishStatus item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }

    /**
     * @param code 库内码
     * @return true 表示草稿
     */
    public static boolean isDraft(Integer code) {
        return code != null && code == DRAFT.code;
    }

    /**
     * @param code 库内码
     * @return true 表示已发布
     */
    public static boolean isPublished(Integer code) {
        return code != null && code == PUBLISHED.code;
    }
}
