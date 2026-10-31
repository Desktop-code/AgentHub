package com.agenthub.runtime.domain.model;

/**
 * 试跑会话状态。所属域：runtime。与 agent_run_session.status 注释一致。
 */
public enum RunStatus {

    /** 多轮进行中。每一轮结束仍保持这个状态；空闲到期由 CloseJob 收口为 SUCCESS 后再批抽 */
    RUNNING,
    /** 终态。含历史遗留与空闲超时自动结束；抽取失败不回滚、不打回 RUNNING */
    SUCCESS,
    /** 预留。本轮失败只写 error_summary，不把整段会话改成失败 */
    FAILED,
    /** 用户取消；同步执行中的模型请求本期不强制中断 */
    CANCELLED
}
