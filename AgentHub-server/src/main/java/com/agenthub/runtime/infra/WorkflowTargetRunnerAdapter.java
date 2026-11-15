package com.agenthub.runtime.infra;

import com.agenthub.common.error.BizException;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.runtime.application.router.RoutedIntent;
import com.agenthub.runtime.application.router.RouterClock;
import com.agenthub.runtime.application.router.WorkflowTargetRunner;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.application.WorkflowRunAppService;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowWorkbenchOutcome;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        // 执行卡片：先按图铺出时间轴，再由引擎的节点迁移逐个点亮
        NodeCollector collector = new NodeCollector(target, progress);
        WorkflowWorkbenchOutcome outcome;
        try {
            outcome = runs.runFromWorkbench(target.workflowId(), query, sessionId, userId, collector);
        } catch (BizException ex) {
            // 业务失败（未发布 / 默认发布缺失 / 图不可执行 / 超节点上限）：给原因，不抛到外层打断整轮
            log.info("工作台工作流未能启动 workflowId={} sessionId={} reason={}",
                    target.workflowId(), sessionId, ex.getMessage());
            return failedResult(collector, progress, "工作流" + label + "未能运行：" + ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("工作台工作流执行异常 workflowId={} sessionId={} type={} cause={}",
                    target.workflowId(), sessionId, ex.getClass().getSimpleName(), ex);
            String reason = UserFacingText.safeMessage(ex, "工作流运行失败");
            return failedResult(collector, progress, "工作流" + label + "运行失败：" + reason);
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
        // 终态卡片：状态与正文一次给齐，前端据此把描边转绿并展开产物区
        WorkflowRunView view = collector.finish(run, text);
        if (progress != null) {
            progress.workflow(view);
        }
        return new WorkflowTargetResult(turn(text), List.of(), view);
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
    private static WorkflowTargetResult failedResult(NodeCollector collector, TrialProgress progress, String text) {
        WorkflowRunView view = collector.finishFailed(text);
        if (progress != null) {
            progress.workflow(view);
        }
        return new WorkflowTargetResult(turn(text), List.of(text), view);
    }

    /**
     * 节点状态收集器：把工作流域的「节点定义 + 运行态行」翻译成消息流卡片视图。
     *
     * <p>为什么不在工作流域直接产出视图：卡片字段是 runtime 的展示契约，
     * 工作流域只负责调度，不该知道「角标 / 时间轴 / 产物区」长什么样。
     *
     * <p>边界：只读节点状态与编码，不携带节点输入输出（可能含业务数据 / 密钥）；
     * START / END 不作为执行步骤展示——它们不是用户需要看到的「一步」。
     */
    private static final class NodeCollector implements WorkflowRunListener {

        /** 工作流展示信息（来自候选快照；定义被删也能给出名字） */
        private final WorkflowRunSpec target;
        /** 实时出口；可空（同步调试路径没有进度通道） */
        private final TrialProgress progress;
        /** 节点 id → 已展示步骤；按图顺序写入 LinkedHashMap，保证时间轴顺序 */
        private final Map<String, WorkflowRunView.Node> nodes = new LinkedHashMap<>();

        private NodeCollector(WorkflowRunSpec target, TrialProgress progress) {
            this.target = target;
            this.progress = progress;
        }

        @Override
        public void runStarted(List<WorkflowNode> graphNodes, List<WorkflowNodeRun> rows) {
            nodes.clear();
            Map<String, WorkflowNodeRun> rowByNode = new LinkedHashMap<>();
            for (WorkflowNodeRun row : rows == null ? List.<WorkflowNodeRun>of() : rows) {
                if (row != null && row.getNodeId() != null) {
                    rowByNode.put(row.getNodeId(), row);
                }
            }
            for (WorkflowNode node : graphNodes == null ? List.<WorkflowNode>of() : graphNodes) {
                if (node == null || isBoundary(node)) {
                    continue;
                }
                nodes.put(node.getId(), toView(node, rowByNode.get(node.getId())));
            }
            emitRunning();
        }

        @Override
        public void nodeChanged(WorkflowNode node, WorkflowNodeRun row) {
            if (node == null || isBoundary(node)) {
                return;
            }
            nodes.put(node.getId(), toView(node, row));
            emitRunning();
        }

        /**
         * 收口：用 run 的最终状态与终答文本生成最后一份卡片。
         *
         * @param run    数据库读回的 run
         * @param answer 终答文本（成功摘要 / 失败原因 / 等待人工提示）
         * @return 终态视图
         */
        private WorkflowRunView finish(WorkflowRun run, String answer) {
            return new WorkflowRunView(nameOf(), codeOf(),
                    run == null ? "FAILED" : run.getStatus(), answer, List.copyOf(nodes.values()));
        }

        /**
         * 失败收口：run 可能根本没建起来，所以不看 run，直接沿用已收集的节点。
         *
         * @param reason 用户可见失败正文
         * @return 失败态视图
         */
        private WorkflowRunView finishFailed(String reason) {
            return new WorkflowRunView(nameOf(), codeOf(), "FAILED", reason, List.copyOf(nodes.values()));
        }

        /** 运行中的快照：状态固定 RUNNING，终答留空（还没产生）。 */
        private void emitRunning() {
            if (progress == null) {
                return;
            }
            progress.workflow(new WorkflowRunView(nameOf(), codeOf(), "RUNNING", "", List.copyOf(nodes.values())));
        }

        /** 展示名：优先候选快照里的名字，其次编码；都没有时留空由前端兜底。 */
        private String nameOf() {
            return target.name() == null || target.name().isBlank() ? codeOf() : target.name().trim();
        }

        private String codeOf() {
            return target.code() == null ? "" : target.code().trim();
        }

        /** START / END 是边界标记，不是执行步骤。 */
        private static boolean isBoundary(WorkflowNode node) {
            String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
            return "START".equals(type) || "END".equals(type);
        }

        /**
         * 一行运行态 → 卡片步骤。
         *
         * @param node 节点定义
         * @param row  运行态；缺行按「未开始」
         * @return 步骤视图
         */
        private static WorkflowRunView.Node toView(WorkflowNode node, WorkflowNodeRun row) {
            String code = node.getCode() == null ? "" : node.getCode().trim();
            String name = node.getName() == null || node.getName().isBlank() ? code : node.getName().trim();
            String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
            String status = row == null || row.getStatus() == null ? "PENDING" : row.getStatus().trim().toUpperCase();
            String error = row == null || row.getErrorSummary() == null ? "" : row.getErrorSummary().trim();
            return new WorkflowRunView.Node(code, name, type, status, noteOf(status, error));
        }

        /**
         * 状态说明：只用真实状态，不编造「正在检索知识库…」这类过程话术。
         *
         * @param status 节点状态码
         * @param error  已脱敏的失败摘要
         * @return 一句中文说明
         */
        private static String noteOf(String status, String error) {
            return switch (status) {
                case "RUNNING" -> "执行中";
                case "SUCCESS" -> "已完成";
                case "FAILED" -> error.isEmpty() ? "执行失败" : clip(error, 120);
                case "SKIPPED" -> "已跳过";
                case "WAITING_HUMAN" -> "等待人工处理";
                case "CANCELLED" -> "已取消";
                default -> "等待前置节点";
            };
        }

        private static String clip(String text, int max) {
            String trimmed = text == null ? "" : text.trim();
            return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
        }
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
