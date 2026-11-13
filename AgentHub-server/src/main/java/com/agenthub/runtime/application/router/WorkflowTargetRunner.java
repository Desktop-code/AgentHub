package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.TrialProgress;

import java.util.List;

/**
 * 「已发布工作流」作为子候选时的执行委托。所属域：runtime。
 *
 * <p>为什么抽接口：子执行（{@link ChildRunner}）只认「跑一个子」这一件事；
 * 真正跑工作流要走工作流域的现网入口（{@code WorkflowRunAppService.runFromWorkbench}），
 * 由 {@code RouterTrialExecutor} 注入实现。这样 ChildRunner 不反向依赖工作流域，
 * 也保证「引擎不合并」——本接口只换「谁去跑」，不换引擎。
 *
 * <p>实现约定（由 RouterTrialExecutor 提供）：
 * <ul>
 *   <li><b>同一条会话</b>：{@code sessionId} 原样交给 run，结果投影回本会话时间线；</li>
 *   <li>只跑默认发布指针，禁跑草稿；未结束 run 由工作流域防重，不在此另做判断；</li>
 *   <li>业务异常按「子 Agent 失败」口径由调用方收成失败段，不打断其它子。</li>
 * </ul>
 */
public interface WorkflowTargetRunner {

    /**
     * 在工作台会话里触发一次工作流并拿到投影文案。
     *
     * @param intent        命中的子意图（sub_query 作为 {@code input.query}）
     * @param target        目标工作流快照（只读，默认发布指针已校验）
     * @param userText      本轮用户原文（sub_query 缺失时兜底）
     * @param userId        平台用户
     * @param sessionId     工作台会话（工作流 run 会记 {@code workbench_session_id}）
     * @param deadlineNanos 外层墙钟截止（用于进出时快速失败）
     * @param progress      可空；启动提示推给同一条时间线
     * @return 投影后的助手正文与时间线说明
     */
    WorkflowTargetResult run(RoutedIntent intent, WorkflowRunSpec target, String userText, long userId,
                             long sessionId, long deadlineNanos, TrialProgress progress);

    /**
     * 工作流一轮的结果。
     *
     * @param turn    投影正文（成功摘要 / 失败原因 / 人工等待提示）
     * @param notices 时间线说明（启动提示、防重提示等）；可空
     */
    record WorkflowTargetResult(TrialTurn turn, List<String> notices) {
    }
}
