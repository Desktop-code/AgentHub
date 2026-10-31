package com.agenthub.obs.domain.model;

/**
 * 调用来源。所属域：obs。与表注释一致。
 * 控制台试跑只写 CONSOLE。APP_API 留给以后的入站调用。
 */
public enum ObsChannel {

    /** 控制台试跑 */
    CONSOLE,
    /** 应用入站。本期不写 */
    APP_API
}
