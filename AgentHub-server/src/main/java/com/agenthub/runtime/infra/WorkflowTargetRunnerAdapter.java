package com.agenthub.runtime.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.RouterClock;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowWorkbenchOutcome;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工作流目标执行委托的 runtime 实现（刀5B）。所属域：runtime 的 infra。
 *
 * <p>职责边界：
 * <ul>
 *   <li><b>不合并引擎</b>：真正跑流程仍走 {@link WorkflowRunAppService#runFromWorkbench}，
 *       本类只负责把「子意图 → 入参 → 结果文案」翻译成对话框里的一条助手回复；</li>
 *   <li><b>同一会话</b>：{@code sessionId} 原样传入，run 记 {@code workbench_session_id}，
 *       时间线事件仍落在 {@code entry_channel=WORKBENCH} 的同一条会话上；</li>
 *   <li><b>只跑默认发布</b>：release 解析在工作流域完成，这里不做任何「回落到草稿」的兜底；</li>
 *   <li><b>不入审批流</b>：PAUSED_HUMAN 只给「等待人工处理 + 去编排 → 人工待办」的提示，
 *       对话框里不做 approve / reject。</li>
 * </ul>
 *
 * <p>失败语义：工作流「启动不了」（未发布、图不可执行、超节点上限）属于业务失败，
 * 这里收成一段可展示正文，真正把该子标成失败的是外层模板（不做假成功）；
 * 只有外层墙钟到期才抛 {@link TrialFailure}，与其它子同一口径。
 *
 * <p>线程：调用发生在子执行线程（可能来自 {@code routerTaskExecutor} 池），本类无状态。
 */
@Component
public class WorkflowTargetRunnerAdapter implements WorkflowTargetRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTargetRunnerAdapter.class);

    private final WorkflowRunAppService runs;
    private final ObjectMapper json;

    /**
     * @param runs 工作流域的运行入口（建 run + 调度引擎 + 防重）
     * @param json 全站 ObjectMapper（解析 run.output_json 抽答案文本）
     */
    public WorkflowTargetRunnerAdapter(WorkflowRunAppService runs, ObjectMapper json) {
        this.runs = runs;
        this.json = json;
    }

    /** {@inheritDoc} */
    @Override
    public WorkflowTargetResult run(RoutedIntent intent, WorkflowRunSpec target, String userText, long userId,
                                    long sessionId, long deadlineNanos, TrialProgress progress) {
        if (target == null || target.workflowId() == null) {
            throw new TrialFailure("工作流目标信息缺失，无法运行");
        }
        // 进工作流前先看外层墙钟：已经超时的轮次不再起一条新流程，避免留下「无人等待的 run」
        RouterClock.assertBefore(deadlineNanos);
        String query = intent.subQuery() == null || intent.subQuery().isBlank() ? userText : intent.subQuery();
        String label = "「" + target.label() + "」";
        // 启动提示进同一条时间线：用户能立刻知道「这轮去跑流程了」，而不是对着空白等
        if (progress != null) {
            progress.route("运行工作流" + label + "…");
        }

        WorkflowWorkbenchOutcome outcome;
        try {
            outcome = runs.runFromWorkbench(target.workflowId(), query, sessionId, userId);
        } catch (BizException ex) {
            // 业务失败（未发布 / 默认发布缺失 / 图不可执行 / 超节点上限）：给原因，不抛到外层打断整轮
            log.info("工作台工作流未能启动 workflowId={} sessionId={} reason={}",
                    target.workflowId(), sessionId, ex.getMessage());
            return new WorkflowTargetResult(turn("工作流" + label + "未能运行：" + ex.getMessage()),
                    List.of("工作流" + label + "未能运行：" + ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("工作台工作流执行异常 workflowId={} sessionId={} type={} cause={}",
                    target.workflowId(), sessionId, ex.getClass().getSimpleName(), ex);
            String reason = UserFacingText.safeMessage(ex, "工作流运行失败");
            return new WorkflowTargetResult(turn("工作流" + label + "运行失败：" + reason),
                    List.of("工作流" + label + "运行失败：" + reason));
        }
        // 出工作流后再看一次墙钟：超时按失败终态处理（run 本身已由引擎收口）
        RouterClock.assertBefore(deadlineNanos);

        WorkflowRun run = outcome.run();
        WorkflowDef def = shellOf(target);
        // 复用未结束 run：绝不能再说「已运行完成」，统一按「没有重复发起」口径提示，
        // 等待人工的 run 还要指向人工待办，避免用户以为流程卡死
        String text = outcome.reused()
                ? WorkflowOutcomeTexts.inFlightText(label, run)
                : WorkflowOutcomeTexts.assistantText(def, run, json);
        return new WorkflowTargetResult(turn(text), List.of());
    }

    /** 造一个只读定义壳：文案只需要名称 / 编码 / 主键，避免为一个标签再查一次库。 */
    private static WorkflowDef shellOf(WorkflowRunSpec target) {
        WorkflowDef def = new WorkflowDef();
        def.setId(target.workflowId());
        def.setCode(target.code());
        def.setName(target.name());
        def.setDescription(target.description());
        return def;
    }

    /** 单轮正文：工作流不产 token 账，计量仍在外层模板。 */
    private static TrialTurn turn(String text) {
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText(text == null ? "" : text);
        return turn;
    }
}
