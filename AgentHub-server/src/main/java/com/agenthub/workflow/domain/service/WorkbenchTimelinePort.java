package com.agenthub.workflow.domain.service;

/**
 * 工作台时间线追加端口。所属域：workflow 的 domain（SPI），由 runtime 实现。
 *
 * <p>为什么需要这个端口：工作流引擎跑完（尤其是人工待办恢复后续跑到终态）时，
 * 当前请求线程已经不一定是工作台那一轮 SSE——结果必须能<b>主动</b>追写回原会话，
 * 而不是等用户再发一句话才看到。
 *
 * <p>实现方约定（runtime）：
 * <ul>
 *   <li>只追加事件，不改会话状态、不推进记忆窗口、不写 obs；</li>
 *   <li>摘要与正文都会脱敏并截断（复用现有事件写入规范）；</li>
 *   <li>实现必须是幂等调用安全的最小副作用：失败只记日志，不能把人工审批本身变红。</li>
 * </ul>
 *
 * <p>调用方（workflow application）必须用 try/catch 包住每一次调用：时间线是旁路，
 * 追写失败不得影响 run 状态与人工待办的最终结果。
 */
public interface WorkbenchTimelinePort {

    /**
     * 追加一条 SYSTEM 说明（思考区），用于运行开始 / 人工等待 / 结束提示。
     *
     * @param sessionId 工作台会话 id
     * @param text      中文说明
     */
    void appendSystem(long sessionId, String text);

    /**
     * 追加一条助手回复，用于把工作流终态结果写回会话正文。
     *
     * @param sessionId 工作台会话 id
     * @param text      助手正文（Markdown）
     */
    void appendAssistant(long sessionId, String text);
}
