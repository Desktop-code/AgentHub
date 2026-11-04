package com.agenthub.obs.domain.model;

/**
 * 调用来源。所属域：obs。与表注释一致。
 * 对话试跑写 CONSOLE，工作流试跑 / 执行写 WORKFLOW。APP_API 留给以后的入站调用。
 */
public enum ObsChannel {

    /** 控制台对话试跑 */
    CONSOLE,
    /** 工作流试跑 / 执行（一次 run 一条根 trace） */
    WORKFLOW,
    /** 应用入站。本期不写 */
    APP_API
}
