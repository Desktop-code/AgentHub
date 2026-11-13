package com.agenthub.runtime.api;

/**
 * 试跑权限码。所属域：runtime。与菜单种子、注解和前端按钮一致。
 */
public final class RuntimePermissions {

    public static final String LIST = "runtime:trial:list";
    public static final String RUN = "runtime:trial:run";
    /**
     * 工作台正式对话权限（{@code /api/runtime/workbench/**}）。
     * 与调试权 {@code runtime:trial:run} 分离：普通用户只拿这个码也能对话，
     * 但打不开 Agent / MA 的调试入口。
     */
    public static final String CHAT = "runtime:chat:use";

    private RuntimePermissions() {
    }
}
