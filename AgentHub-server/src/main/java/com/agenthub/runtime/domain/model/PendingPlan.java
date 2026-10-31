package com.agenthub.runtime.domain.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待批准的计划。所属域：runtime。仅在 require_plan_confirm=true 且已暂停时存在。
 *
 * <p>为什么缓存在进程内而不是只靠库：批准后要继续执行，必须拿到与规划时**完全相同**的
 * 用户原文、已拼好记忆的用户侧文本；重新拼一次会把窗口摘要推进两遍。
 * 服务重启后这里为空，批准接口据此返回「计划已失效」，由用户重新发起，不做静默降级。
 *
 * @param planId    计划标识，与已落库的 PLAN 事件 JSON 一致
 * @param sessionId 试跑会话
 * @param agentId   Agent 定义
 * @param userId    平台用户（批准时校验归属）
 * @param userText  用户原文，用于执行指令与终答
 * @param modelUser 规划时已注入摘要/近轮/短期记忆的用户侧文本，执行时复用
 * @param steps     已按 max_plan_steps 截断的有序步骤
 * @param createTime 规划完成时刻，用于过期判断
 */
public record PendingPlan(String planId, Long sessionId, Long agentId, long userId, String userText,
                          String modelUser, List<PlanStep> steps, LocalDateTime createTime) {

    public PendingPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
