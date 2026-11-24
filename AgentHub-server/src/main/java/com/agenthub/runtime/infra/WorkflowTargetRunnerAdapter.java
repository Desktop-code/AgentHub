package com.agenthub.runtime.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.RouterClock;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.application.router.MaNestedContext;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.WorkflowAgentFileScope;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
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
 *       对话框里不做 approve / reject；WAITING_REPLY 则提示「直接在本会话输入框接着说」。</li>
 * </ul>
 *
 * <p>卡片投影与「等待用户回复」后的续跑共用 {@link WorkflowCardProjector}：
 * 直播、收尾、回放读的永远是同一份 {@link WorkflowRunView} 形状。
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

        // 执行卡片：先按图铺出时间轴，再由引擎的节点迁移逐个点亮
        WorkflowCardProjector collector = new WorkflowCardProjector(
                target.label(), target.code(), progress, json);
        // 文件范围与卡片同一身份：节点里写出的源码才能挂到这张工作流卡上，并落到本会话工作区；
        // 同时把子 Agent 的思考 / 工具交回这张卡，挂到对应节点内部
        WorkflowAgentFileScope.open(progress, participantId(intent, target), target.label(), sessionId, collector);
        try {
            WorkflowWorkbenchOutcome outcome;
            try {
                outcome = runs.runFromWorkbench(target.workflowId(), query, sessionId, userId, collector);
            } catch (BizException ex) {
                // 业务失败（未发布 / 默认发布缺失 / 图不可执行 / 超节点上限）：给原因，不抛到外层打断整轮
                log.info("工作台工作流未能启动 workflowId={} sessionId={} reason={}",
                        target.workflowId(), sessionId, ex.getMessage());
                return withFiles(failedResult(collector, progress,
                        "工作流" + label + "未能运行：" + ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("工作台工作流执行异常 workflowId={} sessionId={} type={} cause={}",
                        target.workflowId(), sessionId, ex.getClass().getSimpleName(), ex);
                String reason = UserFacingText.safeMessage(ex, "工作流运行失败");
                return withFiles(failedResult(collector, progress, "工作流" + label + "运行失败：" + reason));
            }
            // 出工作流后再看一次墙钟：超时按失败终态处理（run 本身已由引擎收口）
            RouterClock.assertBefore(deadlineNanos);

            WorkflowRun run = outcome.run();
            WorkflowDef def = shellOf(target);
            // 复用未结束 run：绝不能再说「已运行完成」，统一按「没有重复发起」口径提示，
            // 等待人工 / 等待回复的 run 各自给出该怎么继续
            String text = outcome.reused()
                    ? WorkflowOutcomeTexts.inFlightText(label, run)
                    : WorkflowOutcomeTexts.assistantText(def, run, json);
            // 终态卡片：状态与正文一次给齐，前端据此把描边转绿并展开产物区
            WorkflowRunView view = collector.finish(run, text);
            if (progress != null) {
                progress.workflow(view);
            }
            /*
             * 本轮正文取卡片自己的 answer：卡片的正文是按节点增量长出来的，
             * 落库 / 回放读的也是这一份，两者不一致时刷新会看到另一段文字。
             * 文件挂在同一份 turn 上，时间线按既有 FILE 事件落库，不另写投影。
             */
            return withFiles(new WorkflowTargetResult(turn(view.answer()), List.of(), view));
        } finally {
            WorkflowAgentFileScope.close();
        }
    }

    /**
     * 启动 / 执行失败时的收口：卡片仍然要出现，只是状态为失败。
     *
     * <p>为什么失败也要出卡片：入口块刚说完「已交由 X 执行」，下面什么都不画会让人以为
     * 消息流丢了；一张写着失败原因的空时间轴卡片，比整块消失诚实。
     *
     * @param collector 已收集到的节点（可能为空）
     * @param progress  实时出口；可空
     * @param text      用户可见的失败正文
     * @return 与正文一致的执行结果
     */
    private static WorkflowTargetResult failedResult(WorkflowCardProjector collector, TrialProgress progress,
                                                     String text) {
        WorkflowRunView view = collector.finishFailed(text);
        if (progress != null) {
            progress.workflow(view);
        }
        return new WorkflowTargetResult(turn(text), List.of(text), view);
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

    /**
     * 把本段节点写出的文件挂到结果上。范围还开着才能取到；关闭之后再取就是空的。
     *
     * @param result 已经收口的执行结果
     * @return 同一个结果，turn 上补了文件
     */
    private static WorkflowTargetResult withFiles(WorkflowTargetResult result) {
        List<GeneratedFile> files = WorkflowAgentFileScope.snapshot();
        if (!files.isEmpty() && result != null && result.turn() != null) {
            result.turn().setFiles(files);
        }
        return result;
    }

    /**
     * 工作流卡的参与者 id，与 {@code ChildRunner} 开跑时的算法一致：
     * {@code p-<深度>-<父方案键|root>-<路由键>}。对不上的话文件会挂错卡。
     *
     * @param intent 本子意图；可空
     * @param target 工作流快照
     * @return 非空参与者 id
     */
    private static String participantId(RoutedIntent intent, WorkflowRunSpec target) {
        String key = "";
        if (intent != null && intent.candidate() != null && intent.candidate().route() != null
                && intent.candidate().route().getRouteKey() != null) {
            key = intent.candidate().route().getRouteKey().trim();
        }
        if (key.isEmpty() && target != null && target.code() != null) {
            key = target.code().trim();
        }
        int depth = Math.max(0, MaNestedContext.depth());
        String parent = MaNestedContext.parentKey();
        if (parent == null || parent.isBlank()) {
            parent = "root";
        }
        String leaf = key.isEmpty() ? "unknown" : key;
        return "p-" + depth + "-" + parent + "-" + leaf;
    }
}
