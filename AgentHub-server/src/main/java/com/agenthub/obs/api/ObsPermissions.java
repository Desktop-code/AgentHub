package com.agenthub.obs.api;

/**
 * 调用追踪权限码。所属域：obs。
 * 与 sql/data-init-observability-menu.sql、接口注解、前端按钮使用同一套码。
 */
public final class ObsPermissions {

    public static final String TRACE_LIST = "obs:trace:list";
    public static final String SPAN_LIST = "obs:span:list";

    private ObsPermissions() {
    }
}
