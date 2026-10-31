package com.agenthub.skill.api;

/**
 * Skill 权限码。所属域：skill。
 * 与 sql/data-init-skill-menu.sql、接口注解、前端按钮使用同一套两段码。
 */
public final class SkillPermissions {

    public static final String LIST = "skill:list";
    public static final String ADD = "skill:add";
    public static final String EDIT = "skill:edit";
    public static final String REMOVE = "skill:remove";
    public static final String ENABLE = "skill:enable";

    private SkillPermissions() {
    }
}
