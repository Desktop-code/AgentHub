package com.agenthub.workflow.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.enums.HumanTimeoutPolicy;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowScopeView;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.JsonLogicEvaluator;
import com.agenthub.workflow.domain.service.WorkflowAgentInvoker;
import com.agenthub.workflow.domain.service.WorkflowAssigneeRule;
import com.agenthub.workflow.domain.service.WorkflowLoopPolicy;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.service.WorkflowNodeInputs;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.agenthub.workflow.domain.service.WorkflowRunLimits;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.domain.service.WorkflowStateMachine;
import com.agenthub.workflow.domain.service.WorkflowToolInvoker;
import com.agenthub.workflow.domain.service.WorkflowTraceWriter;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 调度引擎。所属域：workflow 的 application。
 * <p>职责：按已发布快照推进节点、落 {@code workflow_node_run} 状态、在 HUMAN 处暂停，
 * 失败时按「ERROR 边优先，否则 FAIL_RUN」收束。
 * <p>边界：只做调度；AGENT / TOOL 的实际出站通过 {@link WorkflowAgentInvoker} /
 * {@link WorkflowToolInvoker} 复用现网入口，引擎内不出现模型 / MCP 细节。
 * 无外部出站的控制类节点（START / END / CONDITION / FORK / JOIN）的「算」在
 * {@link WorkflowNodeExecutors}，循环的计数、回边和超限出路在 {@link WorkflowLoopScheduler}，
 * 本类只负责分派、落库、发布与失败收口。
 * <p>线程模型：一次调用在<b>调用方线程</b>内同步跑完（或停在 HUMAN / 等待回复）。
 * 工作台仍在请求线程调用；控制台试跑由后台池调用，本类不自己开线程。
 * FORK 分支按图顺序**顺序**执行而非真并发，语义上等待 JOIN 等齐。
 * <p>取消：协作式——每执行一个节点前重读 run 状态，发现 CANCELLED 立即退出。
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 节点级重试本刀不做；attempt 恒 1 */
    private static final int ATTEMPT = 1;

    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowHumanTaskRepository humanTasks;
    private final WorkflowAgentInvoker agentInvoker;
    private final WorkflowToolInvoker toolInvoker;
    private final WorkflowGraphCodec codec;
    private final WorkflowProperties properties;
    private final ObjectMapper json;
    /** 埋点端口：根 trace + 每节点 TASK 步；实现失败只记日志，不影响调度 */
    private final WorkflowTraceWriter traces;

    /** 图 / 上下文读写：入参映射、已完成产出一律走这里 */
    private final WorkflowContextSupport context;
    /** 时限账本：上限 / 截止 / 单节点超时；按线程存放，execute 收尾清理 */
    private final WorkflowRunClockKeeper clockKeeper;
    /** 卡片观察者发布：节点变更与时限；observer 异常吞掉，不影响调度结论 */
    private final WorkflowCardPublisher publisher;
    /** 控制类节点执行器：START / END / CONDITION / FORK / JOIN 只算不写库 */
    private final WorkflowNodeExecutors executors;

    /** 循环：计数、打回反馈、超限出路。引擎只在进入节点和成功之后调用 */
    private final WorkflowLoopScheduler loops;

    /** 子图：钉死发布版本解析；旧调用方（单测）可不传，此时遇 SUBGRAPH 直接失败 */
    private final WorkflowSubgraphResolver subgraphs;

    /**
     * 单测 / 旧入口用的构造：不带子图解析器。
     *
     * @param runs         run 仓储
     * @param nodeRuns     node_run 仓储
     * @param humanTasks   人工待办仓储
     * @param agentInvoker AGENT 节点执行口
     * @param toolInvoker  TOOL 节点执行口
     * @param codec        图 JSON 解析
     * @param properties   时限默认值
     * @param json         全站 ObjectMapper
     * @param traces       工作流埋点端口
     */
    public WorkflowEngine(WorkflowRunRepository runs,
                          WorkflowNodeRunRepository nodeRuns,
                          WorkflowHumanTaskRepository humanTasks,
                          WorkflowAgentInvoker agentInvoker,
                          WorkflowToolInvoker toolInvoker,
                          WorkflowGraphCodec codec,
                          WorkflowProperties properties,
                          ObjectMapper json,
                          WorkflowTraceWriter traces) {
        this(runs, nodeRuns, humanTasks, agentInvoker, toolInvoker, codec, properties, json, traces, null);
    }

    /**
     * @param runs         run 仓储
     * @param nodeRuns     node_run 仓储
     * @param humanTasks   人工待办仓储
     * @param agentInvoker AGENT 节点执行口（现网试跑链路）
     * @param toolInvoker  TOOL 节点执行口（现网 MCP）
     * @param codec        图 JSON 解析
     * @param properties   墙钟 / 人工超时默认值
     * @param json         全站 ObjectMapper
     * @param traces       工作流埋点端口
     * @param subgraphs    子图钉死发布版本解析器
     */
    @Autowired
    public WorkflowEngine(WorkflowRunRepository runs,
                          WorkflowNodeRunRepository nodeRuns,
                          WorkflowHumanTaskRepository humanTasks,
                          WorkflowAgentInvoker agentInvoker,
                          WorkflowToolInvoker toolInvoker,
                          WorkflowGraphCodec codec,
                          WorkflowProperties properties,
                          ObjectMapper json,
                          WorkflowTraceWriter traces,
                          WorkflowSubgraphResolver subgraphs) {
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.humanTasks = humanTasks;
        this.agentInvoker = agentInvoker;
        this.toolInvoker = toolInvoker;
        this.codec = codec;
        this.properties = properties;
        this.json = json;
        this.traces = traces;
        this.context = new WorkflowContextSupport(json);
        this.clockKeeper = new WorkflowRunClockKeeper(runs, properties);
        this.publisher = new WorkflowCardPublisher(clockKeeper);
        this.executors = new WorkflowNodeExecutors(json, context, properties);
        this.loops = new WorkflowLoopScheduler(json, context, nodeRuns, properties);
        this.subgraphs = subgraphs;
    }

    /**
     * 从当前持久化状态继续调度，直到终态或停在 HUMAN。
     * <p>调用前 run 必须是 CREATED（首次试跑）或 RUNNING（人工通过后续跑）。
     *
     * @param run        已落库的 run
     * @param operatorId 操作人
     * @throws BizException run 状态不允许调度 / 图不可执行
     */
    public void execute(WorkflowRun run, long operatorId) {
        execute(run, operatorId, null);
    }

    /**
     * 带节点观察者的调度：工作台据此画执行卡片，其它入口（编排页试跑 / 人工恢复）不传。
     *
     * <p>观察者是旁路：每次回调都包在 try/catch 里，实现抛异常只记日志，绝不改变 run 的结果。
     * 回调时机一律在节点状态**落库之后**，保证「页面上看到的」与「库里查到的」是同一事实。
     *
     * @param run        已落库的 run
     * @param operatorId 操作人
     * @param listener   节点状态观察者；可空（等于不带观察者）
     * @throws BizException run 状态不允许调度 / 图不可执行
     */
    public void execute(WorkflowRun run, long operatorId, WorkflowRunListener listener) {
        if (listener == null) {
            executeInternal(run, operatorId);
            return;
        }
        publisher.bind(listener);
        try {
            executeInternal(run, operatorId);
        } finally {
            publisher.clear();
            // 时限账本同样按调用清理：池线程不携带上一轮的上限与截止
            clockKeeper.clear();
        }
    }

    /**
     * 实际调度实现；观察者从 {@link WorkflowCardPublisher} 取。
     *
     * @param run        已落库的 run
     * @param operatorId 操作人
     */
    private void executeInternal(WorkflowRun run, long operatorId) {
        WorkflowRunStatus current = WorkflowRunStatus.parse(run.getStatus());
        if (current == WorkflowRunStatus.CREATED) {
            /*
             * 首次调度：CREATED → RUNNING。用条件更新而不是无条件写：
             * 取消接口可能在调度线程启动前就把 run 写成 CANCELLED，无条件写会把取消覆盖回 RUNNING，
             * 之后节点继续跑，刷新时库里仍是运行中——这正是「停止只在当时生效」的一种来源。
             */
            // 条件更新也照样过状态机，避免将来状态表收紧后这里成为绕过口
            WorkflowStateMachine.requireRun(WorkflowRunStatus.CREATED, WorkflowRunStatus.RUNNING);
            boolean started = runs.updateStatusIfMatches(run.getId(),
                    WorkflowRunStatus.CREATED.name(), WorkflowRunStatus.RUNNING.name(),
                    null, null, null, operatorId);
            if (!started) {
                WorkflowRun fresh = runs.findById(run.getId()).orElse(null);
                WorkflowRunStatus freshStatus = fresh == null ? null
                        : WorkflowRunStatus.parse(fresh.getStatus());
                if (freshStatus != WorkflowRunStatus.RUNNING) {
                    // 已经被取消（或收成其它终态）：不再启动，内存对象与库对齐
                    run.setStatus(fresh == null
                            ? WorkflowRunStatus.CANCELLED.name() : fresh.getStatus());
                    return;
                }
            }
            run.setStatus(WorkflowRunStatus.RUNNING.name());
            run.setStartTime(LocalDateTime.now());
        } else if (current != WorkflowRunStatus.RUNNING) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前运行状态为 " + WorkflowStateMachine.label(current) + "，不可继续调度");
        }

        WorkflowGraph graph = codec.parse(run.getGraphJson());
        // 根作用域：节点 id / code 保持不变；子图才会加前缀
        WorkflowScope rootScope = WorkflowScope.root(graph, run.getWorkflowId());
        WorkflowRuntimeGraph runtime = rootScope.runtime();
        ObjectNode input = context.readObject(run.getInputJson());
        List<WorkflowNodeRun> rows = ensureNodeRuns(run, runtime);
        // 同一节点可能有多轮：调度只看最新一轮，历史行留给回放
        Map<String, WorkflowNodeRun> rowByNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : rows) {
            rowByNode.put(row.getNodeId(), row);
        }
        /*
         * 时限账本：上限取「工作流自身配置 ∩ 平台硬顶」，截止时刻首次调度时落库。
         * 放在 publisher.started 之前：卡片第一帧就要看到整段上限，不能等第一次节点变更才补。
         */
        long platformMs = properties.effectiveWallMs();
        WorkflowRunClockKeeper.RunClock clock = clockKeeper.open(run, graph, platformMs, operatorId);
        clockKeeper.bind(clock);
        publisher.limits(null, null);
        // 卡片先按图顺序铺出完整时间轴（全部未开始），后续节点状态再逐个点亮
        publisher.started(runtime, rows);
        ObjectNode outputs = context.collectOutputs(runtime, rows, rootScope::outputCode);
        ObjectNode ctx = JsonLogicEvaluator.context(input, outputs, json.createObjectNode());
        loops.restore(runtime, rows, ctx);

        // 埋点：首次运行在这里建根 trace，HUMAN 恢复后按 run.trace_id 续用同一条
        traceSafely("beginRun", run.getId(), () -> traces.beginRun(run));
        try {
            drive(run, rootScope, rowByNode, ctx, outputs, operatorId, DriveLimits.root(clock));
        } finally {
            /*
             * 取消与调度并发时的节点兜底：取消接口先到、节点行后由 ensureNodeRuns 建出来时，
             * 取消那边的一次 cancelOpen 会漏掉这些行，库里留下 PENDING / RUNNING。
             * 引擎退出前按 run 的终态再收一次，与取消口径一致（在途 → CANCELLED，未开始 → SKIPPED）。
             */
            if (WorkflowRunStatus.CANCELLED.name().equalsIgnoreCase(run.getStatus())) {
                try {
                    nodeRuns.cancelOpen(run.getId());
                } catch (RuntimeException ex) {
                    // 收口失败不改 run 的终态：取消已落库，节点明细最多晚一拍
                    log.warn("取消后收口节点失败 runId={} type={}",
                            run.getId(), ex.getClass().getSimpleName());
                }
            }
            // 只有终态才收口；PAUSED_HUMAN / WAITING_REPLY 保持未结束，等人工通过 / 用户回复后续写
            traceSafely("finishRun", run.getId(), () -> traces.finishRun(run));
        }
    }

    /**
     * 埋点调用的安全壳：观测是旁路，写失败只告警，绝不能改变 run 的结果。
     *
     * @param action 动作名，仅用于日志定位（beginRun / beginNode / endNode / finishRun）
     * @param runId  运行 id
     * @param task   实际埋点调用
     */
    private void traceSafely(String action, Long runId, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.warn("工作流埋点失败 action={} runId={} type={}", action, runId, ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * 一个作用域内的调度主循环：取消、步数、时限、挑节点、执行节点。
     *
     * <p>根作用域与子图作用域共用同一套推进逻辑，差别只在「收口归谁」：
     * 根作用域失败 / 完成要写 run 终态；子图作用域只把结果交回父 SUBGRAPH 节点，
     * 由父节点按既有失败规则决定整条 run 的走向。
     *
     * @param run        已落库的 run（子图也是同一条 run）
     * @param scope      当前作用域
     * @param rowByNode  当前作用域的节点运行态
     * @param ctx        当前作用域上下文（子图有自己的 input / outputs）
     * @param outputs    当前作用域的输出集合
     * @param operatorId 操作人
     * @param limits     本作用域与父调用叠加后的时限
     * @return 结束原因；根作用域调用方忽略
     */
    private DriveResult drive(WorkflowRun run, WorkflowScope scope,
                              Map<String, WorkflowNodeRun> rowByNode, ObjectNode ctx,
                              ObjectNode outputs, long operatorId, DriveLimits limits) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        // 无循环时仍是「每节点大约两拍」；有声明循环时按次数放宽，次数本身不靠这个守卫
        int maxSteps = loops.stepBudget(runtime);
        int steps = 0;
        while (true) {
            // 协作式取消放最前：取消接口可能在另一线程写库，这里每拍重读一次。
            // 顺序有意为之——用户主动取消优先于墙钟 / 无进展文案，避免「已取消」被误报成「撞限」。
            WorkflowRun fresh = runs.findById(run.getId()).orElse(null);
            if (fresh == null) {
                return DriveResult.stopped();
            }
            WorkflowRunStatus freshStatus = WorkflowRunStatus.parse(fresh.getStatus());
            if (freshStatus == WorkflowRunStatus.CANCELLED) {
                run.setStatus(fresh.getStatus());
                return DriveResult.stopped();
            }
            if (++steps > maxSteps) {
                // 内部细节（步数 / 进度）只进日志，对话里只给「异常已停止」
                log.warn("工作流调度无进展，主动收口 runId={} scope={} maxSteps={} progress={}",
                        run.getId(), scope.key(), maxSteps, progressBrief(rowByNode));
                if (scope.depth() == 0) {
                    failRun(run, WorkflowOutcomeTexts.RUN_ABORTED_TEXT, operatorId);
                    return DriveResult.stopped();
                }
                return DriveResult.failed("子图「" + scope.label() + "」执行异常，已停止");
            }
            long now = System.currentTimeMillis();
            if (limits.runDeadlineMs > 0 && now > limits.runDeadlineMs) {
                /*
                 * 整段超时：等待用户回复 / 人工卡点的时长已在时限账本 open 里补回截止，
                 * 这里扣到的都是真正在跑的时长。日志留内部细节，对话只给一句话术。
                 */
                log.warn("工作流整段运行超时 runId={} scope={} runLimitMs={} deadlineMs={} progress={}",
                        run.getId(), scope.key(), limits.runLimitMs(), limits.runDeadlineMs,
                        progressBrief(rowByNode));
                if (scope.depth() == 0) {
                    failRun(run, limits.runTimeoutText(), operatorId);
                    return DriveResult.stopped();
                }
                return DriveResult.failed(limits.runTimeoutText());
            }
            // 父 SUBGRAPH 节点的单节点上限约束整次子图调用；超时按父节点超时规则收口
            if (limits.callDeadlineMs() > 0 && now > limits.callDeadlineMs()) {
                return DriveResult.callTimeout();
            }
            WorkflowNode next = pickNext(runtime, rowByNode);
            if (next == null) {
                if (scope.depth() == 0) {
                    settle(run, runtime, rowByNode, operatorId);
                }
                return DriveResult.finished();
            }
            boolean keepGoing = executeNode(run, scope, next, rowByNode, ctx, outputs, operatorId, limits);
            if (!keepGoing) {
                // 根：失败已由 failRun / 取消落库；子图：HUMAN / 回复等待已把父 run 挂起
                return DriveResult.stopped();
            }
        }
    }

    /**
     * 一个作用域的时限视图。根作用域只有整段截止；子图再叠加自己的整段 / 单节点上限，
     * 以及父 SUBGRAPH 节点的单节点上限（约束整次调用）。
     *
     * @param runDeadlineMs  本作用域整段截止
     * @param runLimitMs     本作用域整段上限（日志用）
     * @param runTimeoutText 整段超时文案
     * @param nodeLimitMs    本作用域单节点上限
     * @param callDeadlineMs 父节点单节点上限的截止；0 表示不适用
     */
    private record DriveLimits(long runDeadlineMs, long runLimitMs, String runTimeoutText,
                               long nodeLimitMs, long callDeadlineMs) {

        /**
         * 根作用域：整段截止与单节点上限直接取时限账本。
         *
         * @param clock 本轮时限账本
         * @return 根作用域时限
         */
        static DriveLimits root(WorkflowRunClockKeeper.RunClock clock) {
            return new DriveLimits(clock.deadlineMs, clock.runLimitMs,
                    WorkflowOutcomeTexts.RUN_TIMEOUT_TEXT, clock.nodeLimitMs, 0L);
        }
    }

    /**
     * 一次 drive 的结束原因。
     *
     * @param status 结束类型
     * @param message 子图失败 / 超时的中文文案；根作用域通常为空
     */
    private record DriveResult(DriveStatus status, String message) {

        static DriveResult finished() {
            return new DriveResult(DriveStatus.FINISHED, null);
        }

        static DriveResult stopped() {
            return new DriveResult(DriveStatus.STOPPED, null);
        }

        static DriveResult callTimeout() {
            return new DriveResult(DriveStatus.CALL_TIMEOUT, null);
        }

        static DriveResult failed(String message) {
            return new DriveResult(DriveStatus.FAILED, message);
        }
    }

    /** drive 结束类型 */
    private enum DriveStatus {
        /** 全部节点已终态 */
        FINISHED,
        /** 停在人工 / 回复等待 / 取消 */
        STOPPED,
        /** 父 SUBGRAPH 节点的单节点上限到了 */
        CALL_TIMEOUT,
        /** 子图内部失败 / 超时 */
        FAILED
    }

    /**
     * 执行一个 SUBGRAPH 节点：按钉死发布版本真正跑子图，再把图级产出挂回父图。
     *
     * <p>嵌入方式与循环一致：子图节点行仍写在同一条 {@code run_id} 下，
     * 只是 id / code 带范围前缀；不新建 run、不新建会话、不新开工作台卡片。
     *
     * <p>边界：
     * <ul>
     *   <li>只跑 {@code subReleaseId} 指向的发布快照，永不回落草稿 / 最新版；</li>
     *   <li>入参只走 SUBGRAPH 节点已有的 {@code inputMapping}，父图 {@code $.vars} 不自动灌入；</li>
     *   <li>出参取子图 END 节点的图级产出，挂到父图 {@code $.outputs.<子图节点code>.*}；</li>
     *   <li>子图内部等待回复 / 人工卡点会把父 run 挂起，续跑仍回到子图那个节点。</li>
     * </ul>
     *
     * @return true 表示子图成功、父图可继续；false 表示父 run 已挂起 / 失败 / 取消
     */
    private boolean executeSubgraph(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                    WorkflowNodeRun row, Map<String, WorkflowNodeRun> rowByNode,
                                    ObjectNode ctx, ObjectNode outputs, long operatorId,
                                    DriveLimits limits) {
        if (subgraphs == null) {
            return handleFailure(run, scope, node, row, "子图执行未装配，无法运行", null, operatorId);
        }
        Long subWorkflowId = WorkflowNodeConfig.longValue(node.getConfig(), "subWorkflowId");
        Long subReleaseId = WorkflowNodeConfig.longValue(node.getConfig(), "subReleaseId");
        // 先查环 / 深度：调用栈上同一工作流出现两次、或超过 3 层，直接失败且不展开
        String nestFailure = scope.nestFailure(subWorkflowId);
        if (!nestFailure.isBlank()) {
            return handleFailure(run, scope, node, row, nestFailure, null, operatorId);
        }
        // 运行时再校验：版本仍在、属于所绑工作流、同租户；不静默改跑最新版 / 草稿
        WorkflowSubgraphSnapshot snapshot;
        try {
            snapshot = subgraphs.resolve(run.getTenantId(), subWorkflowId, subReleaseId);
        } catch (WorkflowNodeFailure ex) {
            return handleFailure(run, scope, node, row, ex.getMessage(), ex, operatorId);
        } catch (BizException ex) {
            return handleFailure(run, scope, node, row, ex.getMessage(), ex, operatorId);
        } catch (RuntimeException ex) {
            log.warn("子图发布版本解析异常 runId={} nodeCode={} releaseId={} type={}",
                    run.getId(), node.getCode(), subReleaseId, ex.getClass().getSimpleName(), ex);
            return handleFailure(run, scope, node, row,
                    "子图绑定的发布版本不可用，请重新选择后再运行", ex, operatorId);
        }
        // 父节点展示名换成「被调工作流名 · vN」：卡片的子图框与父节点用同一个标题
        WorkflowNode labeled = WorkflowScopeGraphs.withName(node, snapshot.label());
        int attempt = row.getAttempt() == null ? ATTEMPT : Math.max(1, row.getAttempt());
        WorkflowScope child;
        try {
            child = WorkflowScope.child(scope, node, attempt, snapshot);
        } catch (BizException ex) {
            return handleFailure(run, scope, labeled, row,
                    "子图「" + snapshot.label() + "」的图不可执行：" + ex.getMessage(), ex, operatorId);
        }
        Map<String, WorkflowNodeRun> childRowByNode = ensureScopeRows(run, child);
        List<WorkflowNodeRun> childRows = new ArrayList<>(childRowByNode.values());
        /*
         * 先更新父 SUBGRAPH 节点的展示名，再铺子图范围：
         * scopeStarted 会立刻出一帧，若名称后到，框标题会先闪一下内部编码。
         */
        publisher.node(labeled, row);
        // 子图快照与内部节点铺给观察者：框默认展开，内部节点按子图顺序逐拍点亮
        publisher.scope(child.view(childRows));

        ObjectNode childInput = childInput(ctx, node, childRows);
        ObjectNode childOutputs = context.collectOutputs(child.runtime(), childRows, child::outputCode);
        ObjectNode childCtx = JsonLogicEvaluator.context(childInput, childOutputs, json.createObjectNode());
        loops.restore(child.runtime(), childRows, childCtx);

        DriveLimits childLimits = childLimits(child, row, childRows, limits);
        DriveResult result;
        try {
            result = drive(run, child, childRowByNode, childCtx, childOutputs, operatorId, childLimits);
        } catch (WorkflowNodeFailure ex) {
            /*
             * 子图内部节点已在自己的行上落 FAILED；父 SUBGRAPH 节点用「被调工作流名 · vN」
             * 这一份定义收口，错误边与失败文案都走既有节点失败规则，不改框标题。
             */
            return handleFailure(run, scope, labeled, row, ex.getMessage(), ex, operatorId);
        }
        if (result.status() == DriveStatus.CALL_TIMEOUT) {
            // 父节点单节点上限约束整次子图调用：按既有「单节点超时」规则收口
            return failNodeTimeout(run, scope, labeled, row, operatorId, limits);
        }
        if (result.status() == DriveStatus.FAILED) {
            return handleFailure(run, scope, labeled, row, result.message(), null, operatorId);
        }
        if (result.status() == DriveStatus.STOPPED) {
            // 子图里的等待回复 / 人工卡点已把父 run 挂起；结束时间留空，续跑还回到这里
            return false;
        }

        // 子图成功：沿用现网「END 节点产出 = 图级输出」的约定，不把每个节点平铺进父图
        JsonNode produced = childGraphOutput(child, childRows);
        if (produced == null || produced.isEmpty()) {
            return handleFailure(run, scope, labeled, row,
                    "子图「" + child.label() + "」未到达结束节点，请检查子图连线后再运行",
                    null, operatorId);
        }
        JsonNode mapped = WorkflowNodeInputs.shapeOutput(
                produced.isObject() ? (ObjectNode) produced : json.createObjectNode().set("text", produced),
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        row.setOutputJson(mapped.toString());
        succeed(row, scope, labeled, outputs, context.defaultEdges(scope.runtime(), node), ctx, rowByNode);
        return true;
    }

    /**
     * 子图入参：只解析父图上该 SUBGRAPH 节点已配的 {@code inputMapping}。
     *
     * <p>空来源（路径读不到 / null）不落键；父图 {@code $.vars} 不自动灌入子图。
     * 若子图上次停在内部节点的「等待回复」，当前 run 的 {@code input.reply} 只作为兜底透传，
     * 显式映射仍然优先。
     *
     * @param parentCtx 父图上下文
     * @param node      SUBGRAPH 节点
     * @param childRows 子图已有运行行（判断是否从子图内部停等恢复）
     * @return 子图 {@code $.input}
     */
    private ObjectNode childInput(ObjectNode parentCtx, WorkflowNode node,
                                  List<WorkflowNodeRun> childRows) {
        ObjectNode childInput = json.createObjectNode();
        ObjectNode mapped = WorkflowNodeInputs.mapped(parentCtx,
                WorkflowNodeConfig.objectValue(node.getConfig(), "inputMapping"));
        mapped.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            // 路径读不到时 mapped 会放 NullNode：空来源不补空值
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                childInput.set(entry.getKey(), value);
            }
        });
        JsonNode parentReply = parentCtx == null || parentCtx.get("input") == null
                ? null : parentCtx.get("input").get("reply");
        boolean resumedFromChild = childRows.stream().anyMatch(childRow -> {
            if (childRow == null) {
                return false;
            }
            if (childRow.getReplyText() != null && !childRow.getReplyText().isBlank()) {
                return true;
            }
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(childRow.getStatus());
            return status == WorkflowNodeRunStatus.WAITING_REPLY
                    || status == WorkflowNodeRunStatus.WAITING_HUMAN;
        });
        if (resumedFromChild && parentReply != null && !parentReply.isNull()
                && !childInput.has("reply")) {
            // 子图内部等待过：同一句回复必须回到那个节点，这里只补默认键
            childInput.set("reply", parentReply);
        }
        return childInput;
    }

    /**
     * 建 / 取子图作用域自己的节点行：同一条 run_id，id / code 带范围前缀。
     *
     * <p>幂等：已存在的行原样返回（含等待回复 / 已成功），只在缺行时补 PENDING；
     * 子图节点行 attempt 恒 1，轮次由范围 key（父节点 id#轮次）区分。
     *
     * @param run   run
     * @param scope 子图作用域
     * @return 子图节点 id → 最新运行态
     */
    private Map<String, WorkflowNodeRun> ensureScopeRows(WorkflowRun run, WorkflowScope scope) {
        Map<String, WorkflowNodeRun> byNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : nodeRuns.listByRunId(run.getId())) {
            if (row != null && scope.owns(row.getNodeId())) {
                // 同一节点多轮：listByRunId 按 id 升序，后写覆盖前写，保留最新一轮
                byNode.put(row.getNodeId(), row);
            }
        }
        List<WorkflowNodeRun> missing = new ArrayList<>();
        for (WorkflowNode node : scope.runtime().nodes()) {
            if (byNode.containsKey(node.getId())) {
                continue;
            }
            WorkflowNodeRun row = new WorkflowNodeRun();
            row.setRunId(run.getId());
            row.setNodeId(node.getId());
            row.setNodeCode(node.getCode());
            row.setNodeType(node.getType());
            row.setAttempt(ATTEMPT);
            row.setStatus(WorkflowNodeRunStatus.PENDING.name());
            missing.add(row);
            byNode.put(node.getId(), row);
        }
        if (!missing.isEmpty()) {
            nodeRuns.insertBatch(missing);
        }
        return byNode;
    }

    /**
     * 子图图级输出：沿用现网约定，取 END 节点产出，形状为 {@code {结束节点code: 产出}}。
     *
     * @param child     子图作用域
     * @param childRows 子图节点行
     * @return 图级输出；没有成功的 END 节点时为空对象
     */
    private ObjectNode childGraphOutput(WorkflowScope child, List<WorkflowNodeRun> childRows) {
        Map<String, WorkflowNodeRun> byNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : childRows) {
            if (row != null && row.getNodeId() != null) {
                byNode.put(row.getNodeId(), row);
            }
        }
        ObjectNode endOutput = json.createObjectNode();
        for (WorkflowNode node : child.runtime().nodes()) {
            if (child.runtime().type(node.getId()) != WorkflowNodeType.END) {
                continue;
            }
            WorkflowNodeRun row = byNode.get(node.getId());
            if (row == null || WorkflowNodeRunStatus.parse(row.getStatus()) != WorkflowNodeRunStatus.SUCCESS) {
                continue;
            }
            JsonNode produced = context.parse(row.getOutputJson());
            if (produced != null) {
                endOutput.set(child.outputCode(node), produced);
            }
        }
        return endOutput;
    }

    /**
     * 子图时限：子图自己的整段 / 单节点上限生效，同时被父 SUBGRAPH 节点的单节点上限约束。
     *
     * <p>开始时刻取子图最早一行；本轮从等待恢复时补回等待时长（与根作用域同一口径）。
     *
     * @param child        子图作用域
     * @param parentRow    父 SUBGRAPH 节点行
     * @param childRows    子图节点行
     * @param parentLimits 父作用域时限
     * @return 子图生效时限
     */
    private DriveLimits childLimits(WorkflowScope child, WorkflowNodeRun parentRow,
                                    List<WorkflowNodeRun> childRows, DriveLimits parentLimits) {
        WorkflowRunLimits own = WorkflowRunLimits.resolve(child.graph(), properties.effectiveWallMs());
        long now = System.currentTimeMillis();
        LocalDateTime earliest = null;
        for (WorkflowNodeRun childRow : childRows) {
            if (childRow != null && childRow.getStartTime() != null
                    && (earliest == null || childRow.getStartTime().isBefore(earliest))) {
                earliest = childRow.getStartTime();
            }
        }
        long childStartMs = earliest == null ? now : clockKeeper.effectiveStartMs(earliest);
        long childDeadlineMs = childStartMs + own.runLimitMs();
        WorkflowRunClockKeeper.RunClock clock = clockKeeper.clock();
        long rootDeadlineMs = clock == null ? childDeadlineMs : clock.deadlineMs;
        long runDeadlineMs = Math.min(childDeadlineMs, rootDeadlineMs);
        String timeoutText = childDeadlineMs <= rootDeadlineMs
                ? "子图「" + child.label() + "」运行超时，已停止"
                : WorkflowOutcomeTexts.RUN_TIMEOUT_TEXT;
        // 父 SUBGRAPH 节点的单节点上限：约束的是整次子图调用，包含子图内部所有节点
        long callDeadlineMs = 0L;
        long parentNodeLimitMs = parentLimits == null ? 0L : parentLimits.nodeLimitMs();
        if (parentNodeLimitMs > 0 && parentRow != null && parentRow.getStartTime() != null) {
            callDeadlineMs = clockKeeper.effectiveStartMs(parentRow.getStartTime()) + parentNodeLimitMs;
        }
        /*
         * 外层 SUBGRAPH 节点的单节点上限要贯穿整条嵌套调用链：
         * 父作用域若已经被一个更外层的调用截止约束，内层取更早的那个，不能让孙图抢先跑完。
         */
        long outerCallDeadlineMs = parentLimits == null ? 0L : parentLimits.callDeadlineMs();
        if (outerCallDeadlineMs > 0 && (callDeadlineMs <= 0 || outerCallDeadlineMs < callDeadlineMs)) {
            callDeadlineMs = outerCallDeadlineMs;
        }
        return new DriveLimits(runDeadlineMs, own.runLimitMs(), timeoutText,
                own.nodeLimitMs(), callDeadlineMs);
    }

    /**
     * 单节点超时收口：节点 FAILED、run FAILED，对话只给「这一步运行超时，已停止」。
     *
     * <p>不走 ERROR 边：超时是「这一步没做完」，继续走错误分支容易把半成品当正常产出。
     *
     * @param run        已落库的 run
     * @param node       当前节点
     * @param row        运行态行
     * @param operatorId 操作人
     * @return 恒 false，表示本次调度到此停止
     */
    private boolean failNodeTimeout(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                    WorkflowNodeRun row, long operatorId, DriveLimits limits) {
        long limitMs = limits == null ? 0L : limits.nodeLimitMs();
        long costMs = clockKeeper.costMs(row);
        // 内部细节只进日志：节点、生效上限、实际耗时
        log.warn("工作流单节点运行超时 runId={} scope={} nodeCode={} nodeLimitMs={} costMs={}",
                run.getId(), scope.key(), node.getCode(), limitMs, costMs);
        row.setStatus(WorkflowNodeRunStatus.FAILED.name());
        row.setErrorSummary(WorkflowOutcomeTexts.NODE_TIMEOUT_TEXT);
        row.setEndTime(LocalDateTime.now());
        row.setSelectedEdges("");
        nodeRuns.update(row);
        publisher.node(node, row);
        if (scope.depth() > 0) {
            // 子图内部超时：节点已落 FAILED，交给父 SUBGRAPH 节点按普通失败规则收口
            throw new WorkflowNodeFailure("子图「" + scope.label() + "」中的节点「"
                    + scope.displayName(node) + "」" + WorkflowOutcomeTexts.NODE_TIMEOUT_TEXT);
        }
        failRun(run, WorkflowOutcomeTexts.NODE_TIMEOUT_TEXT, operatorId);
        return false;
    }

    /**
     * 挑下一个可运行节点：入边全部判定且至少一条被选中。
     * <p>入边全部判定但都没选中 → 该节点 SKIPPED，继续扫描；扫描后重新来一轮，
     * 让下游节点在同一拍内获得「上游已跳过」的信息。
     */
    private WorkflowNode pickNext(WorkflowRuntimeGraph runtime, Map<String, WorkflowNodeRun> rowByNode) {
        while (true) {
            boolean skippedAny = false;
            for (WorkflowNode node : runtime.nodes()) {
                WorkflowNodeRun row = rowByNode.get(node.getId());
                if (row == null) {
                    continue;
                }
                WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
                /*
                 * 子图节点在子图内部停等（等待回复 / 人工）时父 SUBGRAPH 行会保持 RUNNING，
                 * 续跑要回到这个节点把子图跑完；普通节点仍是「只有 PENDING 才调度」，
                 * 不改变既有「RUNNING 不重入」的口径。
                 */
                boolean resumableSubgraph = status == WorkflowNodeRunStatus.RUNNING
                        && runtime.type(node.getId()) == WorkflowNodeType.SUBGRAPH;
                if (status != WorkflowNodeRunStatus.PENDING && !resumableSubgraph) {
                    continue;
                }
                List<WorkflowEdge> incoming = runtime.incoming(node.getId());
                if (runtime.type(node.getId()) == WorkflowNodeType.START) {
                    return node;
                }
                if (!allDecided(incoming, rowByNode)) {
                    continue;
                }
                if (!anySelected(incoming, rowByNode)) {
                    markSkipped(node, row);
                    skippedAny = true;
                    continue;
                }
                return node;
            }
            if (!skippedAny) {
                return null;
            }
        }
    }

    /**
     * 入边是否都已判定。
     *
     * <p>循环节点的「次数用尽」出口在循环还没退出前不能当已判定，否则下游会被提前跳过。
     * 但如果下游同时还有别的入边已经被选中（例如条件的「通过」直接到点评），
     * 这条已选中的边本身就已经决定了要跑，不能再被尚未定下来的出口一直挡住——
     * 否则点评永远停在 PENDING，整次运行会以「未到达结束节点」失败。
     * 因此 BLOCK 只在「没有任何入边被选中」时生效。
     *
     * @param incoming  该节点的入边
     * @param rowByNode 节点 → 最新运行态
     * @return true 表示入边都已判定，可以决定跑还是跳过
     */
    private boolean allDecided(List<WorkflowEdge> incoming, Map<String, WorkflowNodeRun> rowByNode) {
        boolean blockByOpenExit = false;
        for (WorkflowEdge edge : incoming) {
            WorkflowNodeRun source = rowByNode.get(edge.getSource());
            if (source == null) {
                continue;
            }
            // 声明回边不挡住循环的第一次进入；超限出口在循环还没离开前也不能把下游提前跳过
            WorkflowLoopPolicy.IncomingGate gate = WorkflowLoopPolicy.incomingGate(edge, source);
            if (gate == WorkflowLoopPolicy.IncomingGate.IGNORE) {
                continue;
            }
            if (gate == WorkflowLoopPolicy.IncomingGate.BLOCK) {
                blockByOpenExit = true;
                continue;
            }
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(source.getStatus());
            if (status == null || !status.terminal()) {
                return false;
            }
        }
        if (!blockByOpenExit) {
            return true;
        }
        // 出口还没定，但有别的入边已经选中：按「已经该跑」处理，不再等循环退出
        return anySelected(incoming, rowByNode);
    }

    private boolean anySelected(List<WorkflowEdge> incoming, Map<String, WorkflowNodeRun> rowByNode) {
        for (WorkflowEdge edge : incoming) {
            WorkflowNodeRun source = rowByNode.get(edge.getSource());
            if (source != null && context.selectedEdgeIds(source).contains(edge.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分支未被选中：节点收成 SKIPPED，并通知观察者（时间轴上仍保留该行，不静默消失）。
     *
     * @param node 节点定义
     * @param row  运行态行
     */
    private void markSkipped(WorkflowNode node, WorkflowNodeRun row) {
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.SKIPPED).name());
        row.setEndTime(LocalDateTime.now());
        nodeRuns.update(row);
        publisher.node(node, row);
    }

    /**
     * 执行单个节点。
     *
     * @return true 表示可继续调度；false 表示已停在 HUMAN 或 run 已失败 / 取消
     */
    private boolean executeNode(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                Map<String, WorkflowNodeRun> rowByNode, ObjectNode ctx,
                                ObjectNode outputs, long operatorId, DriveLimits limits) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        WorkflowNodeRun row = rowByNode.get(node.getId());
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.RUNNING).name());
        // 已经是 RUNNING 的节点（父 SUBGRAPH 在子图停等后重入）保留原开始时刻：
        // 等待时长由时限账本按 pausedMs 补回，不能靠重置开始时间「抹掉」已跑时长
        if (from != WorkflowNodeRunStatus.RUNNING) {
            row.setStartTime(LocalDateTime.now());
        }
        nodeRuns.update(row);
        // 落库之后再报「正在跑」：卡片上的蓝点与库里的 RUNNING 是同一时刻的事实
        publisher.node(node, row);

        WorkflowNodeType type = runtime.type(node.getId());
        // 埋点：节点步在这里开，收口统一放 finally（成功 / 失败 / 取消都落终态）
        traceSafely("beginNode", run.getId(), () -> traces.beginNode(run, node));
        try {
            switch (type) {
                /*
                 * 控制类节点（无外部出站）：执行器只算入参 / 产出 / 选中边或失败文案，
                 * 落库与卡片发布仍由下面统一的 succeed / handleFailure 完成。
                 * 异常分类（ERROR 边优先于 FAIL_RUN）与 trace 收口保持在外壳里。
                 */
                case START, END, CONDITION, PARALLEL_FORK, PARALLEL_JOIN -> {
                    WorkflowNodeExecutors.ControlOutcome outcome = executors.execute(type, runtime, node, ctx);
                    if (outcome.failure() != null) {
                        /*
                         * 失败前是否写入参与原实现一致：CONDITION 先写 input_json 再失败，
                         * PARALLEL_FORK 超额则不入参（执行器回 null，这里跳过）。
                         */
                        if (outcome.inputJson() != null) {
                            row.setInputJson(outcome.inputJson());
                        }
                        return handleFailure(run, scope, node, row, outcome.failure(), null, operatorId);
                    }
                    row.setInputJson(outcome.inputJson());
                    row.setOutputJson(outcome.outputJson());
                    succeed(row, scope, node, outputs, outcome.selectedEdges(), ctx, rowByNode);
                }
                case LOOP -> {
                    if (!executeLoop(run, scope, node, row, rowByNode, ctx, outputs, operatorId)) {
                        return false;
                    }
                }
                case AGENT -> {
                    if (!invokeAgent(run, scope, node, row, ctx, outputs, operatorId, rowByNode, limits)) {
                        return false;
                    }
                }
                case TOOL -> {
                    if (!invokeTool(run, scope, node, row, ctx, outputs, operatorId, rowByNode, limits)) {
                        return false;
                    }
                }
                case HUMAN -> {
                    return enterHuman(run, scope, node, row, ctx, operatorId);
                }
                case SUBGRAPH -> {
                    return executeSubgraph(run, scope, node, row, rowByNode, ctx, outputs,
                            operatorId, limits);
                }
                default -> {
                    // 校验器本应拦下未接入类型；手改库绕过校验时在这里明确失败，不静默跳过。
                    return handleFailure(run, scope, node, row,
                            "节点类型 " + type + " 尚未接入引擎", null, operatorId);
                }
            }
            return true;
        } catch (WorkflowNodeFailure ex) {
            /*
             * 子图内部节点失败时，handleFailure 已把该行落成 FAILED，再把可读原因抛给父节点。
             * 不能在这里二次收口：同一行 FAILED → FAILED 会重复发布，也会把「子图哪个节点失败」
             * 的上下文覆盖掉。只有还没落失败的行（普通本层节点主动抛）才交给 handleFailure。
             */
            if (WorkflowNodeRunStatus.parse(row.getStatus()) == WorkflowNodeRunStatus.FAILED) {
                throw ex;
            }
            return handleFailure(run, scope, node, row, ex.getMessage(), ex, operatorId);
        } catch (BizException ex) {
            return handleFailure(run, scope, node, row, ex.getMessage(), ex, operatorId);
        } catch (RuntimeException ex) {
            log.warn("工作流节点执行异常 runId={} scope={} nodeCode={} type={} root={}",
                    run.getId(), scope.key(), node.getCode(), type, ex.getClass().getSimpleName(), ex);
            return handleFailure(run, scope, node, row,
                    "节点「" + scope.displayName(node) + "」执行异常："
                            + scope.cleanText(context.safe(ex.getMessage())), ex, operatorId);
        } finally {
            // 哪怕上面已 return，也要把节点步收成终态；WAITING_HUMAN 由根 trace 统一收口
            traceSafely("endNode", run.getId(), () -> traces.endNode(run, node, row));
        }
    }

    private boolean invokeAgent(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                WorkflowNodeRun row, ObjectNode ctx, ObjectNode outputs, long operatorId,
                                Map<String, WorkflowNodeRun> rowByNode, DriveLimits limits) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        Long agentId = WorkflowNodeConfig.longValue(node.getConfig(), "agentId");
        if (agentId == null) {
            return handleFailure(run, scope, node, row, "Agent 节点未绑定 Agent", null, operatorId);
        }
        JsonNode mapping = WorkflowNodeConfig.objectValue(node.getConfig(), "inputMapping");
        String message = WorkflowNodeInputs.message(ctx, mapping);
        ObjectNode mapped = WorkflowNodeInputs.mapped(ctx, mapping);
        row.setInputJson(mapped.isEmpty()
                ? json.createObjectNode().put("text", message).toString()
                : mapped.toString());
        long userId = run.getTriggeredBy() == null ? operatorId : run.getTriggeredBy();
        /*
         * 询问节点（awaitReply）要能带可点选项，因此走带选项的执行口；其它节点保持原调用，
         * 既不装选项工具，也不多一次出站。
         */
        boolean awaitReply = WorkflowNodeConfig.trueValue(node.getConfig(), "awaitReply");
        /*
         * 带上节点编码：执行器把子 Agent 的思考 / 工具 / 运行中正文按节点交回卡片投影，
         * 卡片才能把它们挂到这条时间轴的对应节点里，而不是堆在整张卡下面。
         */
        WorkflowAgentInvoker.Reply reply = awaitReply
                ? agentInvoker.invokeForReply(agentId, message, userId, run.getId(), node.getCode())
                : new WorkflowAgentInvoker.Reply(
                        agentInvoker.invoke(agentId, message, userId, run.getId(), node.getCode()), "");
        String text = reply == null ? null : reply.text();
        if (text == null || text.isBlank()) {
            return handleFailure(run, scope, node, row,
                    "Agent「" + scope.displayName(node) + "」没有返回文本", null, operatorId);
        }
        /*
         * 单节点上限：出站回来后立刻核对这一步耗时。放在产出落库前，
         * 超时就不再进 succeed / 等待回复，避免「已经超时的节点还挂在等待里」。
         * 子图内部节点用发布版本里的上限，而不是根图的上限。
         */
        if (clockKeeper.nodeTimedOut(row, limits == null ? 0L : limits.nodeLimitMs())) {
            return failNodeTimeout(run, scope, node, row, operatorId, limits);
        }
        ObjectNode raw = json.createObjectNode();
        raw.put("text", text);
        ObjectNode shaped = WorkflowNodeInputs.shapeOutput(raw,
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        row.setOutputJson(shaped.toString());
        /*
         * 对话停等：节点配了 awaitReply 且本 run 还没有用户回复时，把这一节点刚产出的问句
         * 作为「已经流出的正文」落进 output，随后把 run 挂到 WAITING_REPLY 并停止调度。
         * 不把 run 标成成功来假装本轮结束——用户下一句回来要从这里接着往下走。
         */
        if (awaitReply && !replyProvided(ctx)) {
            return enterReplyWait(run, scope, node, row, shaped, reply.choiceJson(), operatorId);
        }
        succeed(row, scope, node, outputs, context.defaultEdges(runtime, node), ctx, rowByNode);
        return true;
    }

    /**
     * 是否已经从输入里拿到用户回复。
     *
     * <p>只认 {@code input.reply}：回复由续跑入口写进 run 的输入快照（同一会话的下一句）。
     * 历史 run 没有这个键，因此老图重跑时行为与原来一致。
     *
     * @param ctx 本轮求值上下文
     * @return true 表示本次调度是在用户回复之后继续
     */
    private static boolean replyProvided(ObjectNode ctx) {
        JsonNode input = ctx == null ? null : ctx.get("input");
        JsonNode reply = input == null ? null : input.get("reply");
        return reply != null && reply.isTextual() && !reply.asText().isBlank();
    }

    /**
     * 进入「等待用户回复」：节点 WAITING_REPLY、run WAITING_REPLY，然后停止调度。
     *
     * <p>问句必须留在节点产出里（{@code text} / {@code question}）：工作流卡的正文按
     * 节点产出增量拼接，卡片与回放都读这一份；只在内存里留一句会刷新即丢。
     *
     * <p>可点选项同理落在 {@code choice}：形状与 CHOICE 事件正文完全一致
     * （{@code {"question","options":[{"id","label"}]}}），前端一套渲染即可，
     * 不需要为工作流另做一套芯片。
     *
     * @param run        已落库的 run
     * @param node       当前 AGENT 节点
     * @param row        运行态行（已是 RUNNING）
     * @param produced   节点产出（至少含 text）
     * @param choiceJson 结构化选项 JSON；没有选项时为空
     * @param operatorId 操作人
     * @return 恒 false，表示本次调度到此停止
     */
    private boolean enterReplyWait(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                   WorkflowNodeRun row, ObjectNode produced, String choiceJson,
                                   long operatorId) {
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.WAITING_REPLY).name());
        String question = WorkflowNodeInputs.text(produced == null ? null : produced.get("text"));
        ObjectNode kept = produced == null ? json.createObjectNode() : produced.deepCopy();
        if (!question.isBlank()) {
            // 单独留一份 question：后续节点 / 卡片想只取问句时不必再拆正文
            kept.put("question", question.trim());
        }
        JsonNode choice = context.parse(choiceJson);
        if (choice != null && choice.isObject()) {
            /*
             * 存成对象而不是字符串：卡片投影与回放都按同一份结构读取，
             * 前端只需一次 JSON.parse 就能拿到 {question, options}。
             */
            kept.set("choice", choice);
        }
        row.setOutputJson(kept.toString());
        // 节点还没结束：结束时间留空，等用户回复后由续跑入口补
        row.setEndTime(null);
        nodeRuns.update(row);
        /*
         * 先把 run 迁到 WAITING_REPLY，再把带选项的等待卡推给前端。
         *
         * <p>顺序不能反：等待卡一出现，用户就可能立刻点选项；若此时库里 run 还是 RUNNING，
         * 会话入口按「等待中的运行」查不到它，这句回复会被当成新问题重新过一遍入口。
         * 只有 run 真正挂起后才发布卡片，才能保证「看到选项时一定拦得住下一句」。
         */
        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_REPLY,
                "等待用户回复", null, operatorId);
        // 并发取消优先：run 已被停止时不再发布一张还在等待的卡片
        if (!WorkflowRunStatus.WAITING_REPLY.name().equals(run.getStatus())) {
            return false;
        }
        publisher.node(node, row);
        return false;
    }

    private boolean invokeTool(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                               WorkflowNodeRun row, ObjectNode ctx, ObjectNode outputs, long operatorId,
                               Map<String, WorkflowNodeRun> rowByNode, DriveLimits limits) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        Long toolId = WorkflowNodeConfig.longValue(node.getConfig(), "toolId");
        if (toolId == null) {
            return handleFailure(run, scope, node, row, "工具节点未绑定工具项", null, operatorId);
        }
        ObjectNode args = WorkflowNodeInputs.mapped(ctx,
                WorkflowNodeConfig.objectValue(node.getConfig(), "inputMapping"));
        row.setInputJson(args.toString());
        String text = toolInvoker.invoke(toolId, args);
        if (text == null || text.isBlank()) {
            return handleFailure(run, scope, node, row,
                    "工具「" + scope.displayName(node) + "」没有返回内容", null, operatorId);
        }
        // 工具调用同样受单节点上限约束（MCP 可能长时间不回）；子图用子图自己的上限
        if (clockKeeper.nodeTimedOut(row, limits == null ? 0L : limits.nodeLimitMs())) {
            return failNodeTimeout(run, scope, node, row, operatorId, limits);
        }
        ObjectNode raw = json.createObjectNode();
        raw.put("text", text);
        ObjectNode shaped = WorkflowNodeInputs.shapeOutput(raw,
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        row.setOutputJson(shaped.toString());
        succeed(row, scope, node, outputs, context.defaultEdges(runtime, node), ctx, rowByNode);
        return true;
    }

    /**
     * 执行循环节点。次数未到就进入循环体；到了按配置离开。
     * 配置成失败时直接结束本次运行，不把超限当成节点异常去跟 ERROR 边。
     *
     * @return true 表示可以继续调度
     */
    private boolean executeLoop(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                WorkflowNodeRun row, Map<String, WorkflowNodeRun> rowByNode,
                                ObjectNode ctx, ObjectNode outputs, long operatorId) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        WorkflowLoopScheduler.Decision decision = loops.enter(runtime, node, row, rowByNode, ctx);
        if (decision.stopRun()) {
            if (decision.inputJson() != null) {
                row.setInputJson(decision.inputJson());
            }
            if (decision.outputJson() != null) {
                row.setOutputJson(decision.outputJson());
            }
            row.setStatus(WorkflowStateMachine.requireNode(
                    WorkflowNodeRunStatus.parse(row.getStatus()), WorkflowNodeRunStatus.FAILED).name());
            row.setErrorSummary(context.truncate(scope.cleanText(decision.failure())));
            row.setEndTime(LocalDateTime.now());
            row.setSelectedEdges("");
            nodeRuns.update(row);
            publisher.node(node, row);
            if (scope.depth() > 0) {
                // 子图内部循环超限：节点已落 FAILED，交给父 SUBGRAPH 节点按普通失败规则收口
                throw new WorkflowNodeFailure("子图「" + scope.label() + "」中的节点「"
                        + scope.displayName(node) + "」" + scope.cleanText(decision.failure()));
            }
            failRun(run, decision.failure(), operatorId);
            return false;
        }
        row.setInputJson(decision.inputJson());
        row.setOutputJson(decision.outputJson());
        succeed(row, scope, node, outputs, decision.selectedEdges(), ctx, rowByNode);
        return true;
    }

    /**
     * 进入人工卡点：节点 WAITING_HUMAN、run PAUSED_HUMAN、写 OPEN 待办，然后停止调度。
     */
    private boolean enterHuman(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                               WorkflowNodeRun row, ObjectNode ctx, long operatorId) {
        WorkflowNodeRunStatus from = WorkflowNodeRunStatus.parse(row.getStatus());
        row.setStatus(WorkflowStateMachine.requireNode(from, WorkflowNodeRunStatus.WAITING_HUMAN).name());
        nodeRuns.update(row);
        // 人工卡点也要点亮：卡片上显示「等待人工处理」，用户才知道卡在哪一步
        publisher.node(node, row);

        HumanTimeoutPolicy policy = HumanTimeoutPolicy.parse(
                WorkflowNodeConfig.textValue(node.getConfig(), "timeoutPolicy"));
        if (policy == null) {
            // 校验器已拦；手改库绕过时按最安全的自动拒绝兜底
            policy = HumanTimeoutPolicy.AUTO_REJECT;
        }
        long timeoutMs = WorkflowNodeConfig.longValue(node.getConfig(), "timeoutMs") == null
                ? properties.getHumanDefaultTimeoutMs()
                : Math.max(WorkflowNodeConfig.longValue(node.getConfig(), "timeoutMs"), 0L);
        LocalDateTime expire = timeoutMs <= 0 ? null : LocalDateTime.now().plusNanos(timeoutMs * 1_000_000L);

        WorkflowHumanTask task = new WorkflowHumanTask();
        task.setTenantId(run.getTenantId());
        task.setRunId(run.getId());
        task.setNodeRunId(row.getId());
        // 独立待办页展示原始编码，不暴露子图作用域前缀
        task.setNodeCode(scope.outputCode(node));
        task.setStatus("OPEN");
        task.setAssigneeRule(WorkflowAssigneeRule.normalize(
                WorkflowNodeConfig.textValue(node.getConfig(), "assigneeRule")));
        task.setTimeoutPolicy(policy.name());
        ObjectNode form = json.createObjectNode();
        form.set("input", ctx.get("input"));
        task.setFormJson(form.toString());
        task.setExpireTime(expire);
        task.setCreatedBy(operatorId);
        humanTasks.insert(task);

        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED_HUMAN,
                null, null, operatorId);
        return false;
    }

    /**
     * 节点失败收口：有 ERROR 出边则选中错误边继续；否则整 run FAILED。
     *
     * @return true 表示错误边已接管，可继续调度
     */
    private boolean handleFailure(WorkflowRun run, WorkflowScope scope, WorkflowNode node,
                                  WorkflowNodeRun row, String message, Throwable cause, long operatorId) {
        WorkflowRuntimeGraph runtime = scope == null ? null : scope.runtime();
        String cleanMessage = scope == null ? message : scope.cleanText(message);
        List<WorkflowEdge> errorEdges = runtime == null ? List.of() : runtime.errorOutgoing(node.getId());
        row.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.parse(row.getStatus()), WorkflowNodeRunStatus.FAILED).name());
        row.setErrorSummary(context.truncate(cleanMessage));
        row.setEndTime(LocalDateTime.now());
        row.setSelectedEdges(context.joinIds(errorEdges));
        nodeRuns.update(row);
        publisher.node(node, row);
        if (cause != null) {
            log.warn("工作流节点失败 runId={} scope={} nodeCode={} reason={} root={}",
                    run.getId(), scope == null ? "root" : scope.key(), node.getCode(), cleanMessage,
                    cause.getClass().getSimpleName());
        }
        if (!errorEdges.isEmpty()) {
            return true;
        }
        if (scope != null && scope.depth() > 0) {
            /*
             * 子图内部失败：子图这一行已落 FAILED，框里能看到出错节点；
             * 不直接把整条 run 收成失败，而是把可读原因抛给父 SUBGRAPH 节点，
             * 由父节点按它自己的 ERROR 边 / FAIL_RUN 规则收口。
             */
            throw new WorkflowNodeFailure("子图「" + scope.label() + "」中的节点「"
                    + scope.displayName(node) + "」失败：" + cleanMessage);
        }
        failRun(run, "节点「" + node.getCode() + "」失败：" + cleanMessage, operatorId);
        return false;
    }

    /**
     * 普通节点成功收口：写状态、输出、选中边，并把产出放进 ctx.outputs.&lt;code&gt;。
     *
     * <p>同一节点再次进入时，上下文只保留最新一轮产出，上一轮仍在自己的 node_run 行里。
     * 节点编码在一张图里是唯一的，所以这里的再次写入就是循环的下一轮，不是两条并行分支撞名。
     */
    private void succeed(WorkflowNodeRun row, WorkflowScope scope, WorkflowNode node, ObjectNode outputs,
                         List<WorkflowEdge> selected, ObjectNode ctx,
                         Map<String, WorkflowNodeRun> rowByNode) {
        WorkflowRuntimeGraph runtime = scope.runtime();
        // 展示 / $.outputs 用原始编码；运行行的 node_code 仍是前缀后的唯一键
        String code = scope.outputCode(node);
        JsonNode produced = context.parse(row.getOutputJson());
        row.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.parse(row.getStatus()), WorkflowNodeRunStatus.SUCCESS).name());
        row.setEndTime(LocalDateTime.now());
        row.setSelectedEdges(context.joinIds(selected));
        nodeRuns.update(row);
        publisher.node(node, row);
        if (code != null && !code.isBlank() && produced != null) {
            outputs.set(code, produced);
        }
        // 回边反馈和下一轮行在落库之后处理，失败 / 取消不会进到这里
        loops.afterNodeSuccess(runtime, node, row, rowByNode, ctx);
    }

    /**
     * 调度收尾：全部节点终态后判定 SUCCESS / FAILED。
     */
    private void settle(WorkflowRun run, WorkflowRuntimeGraph runtime,
                        Map<String, WorkflowNodeRun> rowByNode, long operatorId) {
        boolean waitingHuman = false;
        boolean waitingReply = false;
        boolean failed = false;
        boolean endSuccess = false;
        String firstError = null;
        ObjectNode endOutput = json.createObjectNode();
        for (WorkflowNode node : runtime.nodes()) {
            WorkflowNodeRun row = rowByNode.get(node.getId());
            if (row == null) {
                continue;
            }
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
            if (status == WorkflowNodeRunStatus.WAITING_HUMAN) {
                waitingHuman = true;
            }
            if (status == WorkflowNodeRunStatus.WAITING_REPLY) {
                waitingReply = true;
            }
            if (status == WorkflowNodeRunStatus.FAILED) {
                failed = true;
                if (firstError == null) {
                    firstError = row.getErrorSummary();
                }
            }
            if (status == WorkflowNodeRunStatus.SUCCESS && runtime.type(node.getId()) == WorkflowNodeType.END) {
                endSuccess = true;
                JsonNode produced = context.parse(row.getOutputJson());
                if (produced != null) {
                    endOutput.set(node.getCode(), produced);
                }
            }
        }
        if (waitingHuman) {
            transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED_HUMAN,
                    null, null, operatorId);
            return;
        }
        /*
         * 等待回复优先于「未到达结束节点」：图里若「问完就结束本轮」用 END 收尾，
         * 只要还有一个节点停在等待回复，这条 run 就是挂起而不是成功 / 失败。
         * 挂起态必须由用户下一句送回后才继续，不能在这里收口。
         */
        if (waitingReply) {
            transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING_REPLY,
                    "等待用户回复", null, operatorId);
            return;
        }
        if (endSuccess) {
            // 走到 END 即成功；途中有被 ERROR 边接管的失败节点也允许成功
            transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.SUCCESS,
                    null, endOutput.toString(), operatorId);
            return;
        }
        String reason = failed
                ? "工作流失败：" + (firstError == null ? "节点执行失败" : firstError)
                : "未到达结束节点：可能存在未连通的节点或条件分支全部未选中";
        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.FAILED,
                context.truncate(reason), null, operatorId);
    }

    private void failRun(WorkflowRun run, String reason, long operatorId) {
        transitionRun(run, WorkflowRunStatus.RUNNING, WorkflowRunStatus.FAILED,
                context.truncate(reason), null, operatorId);
    }

    /**
     * run 状态迁移：所有引擎内迁移都走条件更新，避免覆盖并发的取消终态。
     *
     * <p>为什么不能无条件 {@code updateStatus}：取消接口在另一线程写库，可能在
     * 「循环已判定可以收口」与「真正写 SUCCESS / FAILED」之间把当前状态改成 CANCELLED。
     * 无条件写会把取消覆盖成成功 / 失败，刷新后库里又是一条「正常结束」的 run。
     *
     * <p>条件更新失败时以库里的状态为准，把内存对象对齐后直接返回；
     * 取消是用户动作，优先级高于引擎自己的收口结论。
     *
     * @param run         已落库的 run
     * @param from        期望的当前状态（引擎内正常流程恒为 RUNNING）
     * @param to          目标状态
     * @param errorSummary 失败 / 等待摘要；可空
     * @param outputJson  终态输出；可空
     * @param operatorId  操作人
     */
    private void transitionRun(WorkflowRun run, WorkflowRunStatus from, WorkflowRunStatus to,
                               String errorSummary, String outputJson, long operatorId) {
        WorkflowStateMachine.requireRun(from, to);
        LocalDateTime end = to.terminal() ? LocalDateTime.now() : null;
        // 条件迁移：库里仍是 from 才写入；已被取消或他人改写则放弃本次收口
        boolean changed = runs.updateStatusIfMatches(run.getId(), from.name(), to.name(),
                errorSummary, outputJson, end, operatorId);
        if (!changed) {
            // 以库为准：可能是取消，也可能是人工入口已收口；内存对象不能保留错的终态
            WorkflowRun fresh = runs.findById(run.getId()).orElse(null);
            if (fresh != null) {
                run.setStatus(fresh.getStatus());
                run.setErrorSummary(fresh.getErrorSummary());
                run.setOutputJson(fresh.getOutputJson());
                run.setEndTime(fresh.getEndTime());
            }
            return;
        }
        run.setStatus(to.name());
        if (errorSummary != null) {
            run.setErrorSummary(errorSummary);
        }
        if (outputJson != null) {
            run.setOutputJson(outputJson);
        }
        if (end != null) {
            run.setEndTime(end);
        }
        /*
         * 进入等待态：记下「等待开始时刻」并落库。
         *
         * <p>恢复调度时按这个时刻把等待时长补回截止；补完即清空，所以同一段等待只补一次。
         * 同时把冻结后的剩余交给观察者——等待态卡片不应继续往下扣时间。
         */
        if (to == WorkflowRunStatus.PAUSED_HUMAN || to == WorkflowRunStatus.WAITING_REPLY) {
            WorkflowRunClockKeeper.RunClock clock = clockKeeper.clock();
            LocalDateTime waitingAt = LocalDateTime.now();
            if (clock != null) {
                LocalDateTime deadlineAt = WorkflowRunClockKeeper.toLocalDateTime(clock.deadlineMs);
                clockKeeper.markWaiting(waitingAt);
                runs.updateRunLimits(run.getId(), clock.runLimitMs, clock.nodeLimitMs,
                        deadlineAt, waitingAt, operatorId);
                run.setRunLimitMs(clock.runLimitMs);
                run.setNodeLimitMs(clock.nodeLimitMs);
                run.setRunDeadline(deadlineAt);
                run.setWaitingSince(waitingAt);
                publisher.limits(null, null);
            }
        }
    }

    /**
     * 取回 / 自愈节点运行态：建 run 时已批量预建；缺行按图补 attempt=1 的 PENDING。
     * 返回含历史轮次的全部行（id 升序），调用方用同一 nodeId 的最后一行作为最新一轮。
     */
    private List<WorkflowNodeRun> ensureNodeRuns(WorkflowRun run, WorkflowRuntimeGraph runtime) {
        List<WorkflowNodeRun> rows = new ArrayList<>(nodeRuns.listByRunId(run.getId()));
        Map<String, WorkflowNodeRun> byNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : rows) {
            byNode.put(row.getNodeId(), row);
        }
        List<WorkflowNodeRun> missing = new ArrayList<>();
        for (WorkflowNode node : runtime.nodes()) {
            if (byNode.containsKey(node.getId())) {
                continue;
            }
            WorkflowNodeRun row = new WorkflowNodeRun();
            row.setRunId(run.getId());
            row.setNodeId(node.getId());
            row.setNodeCode(node.getCode());
            row.setNodeType(node.getType());
            row.setAttempt(ATTEMPT);
            row.setStatus(WorkflowNodeRunStatus.PENDING.name());
            missing.add(row);
        }
        if (!missing.isEmpty()) {
            nodeRuns.insertBatch(missing);
            rows.addAll(missing);
        }
        return rows;
    }

    /**
     * 调度进度摘要（脱敏）：只给状态计数与已完成节点编码，不带任何输入 / 输出正文。
     *
     * <p>用于撞限类失败文案，让用户知道「跑到哪一步卡住」而不是只看到超时数字。
     *
     * @param rowByNode 当前 run 的节点运行态
     * @return 中文摘要，如「已完成 2 个节点（start、answer），失败 0，等待 0，共 5 个」
     */
    private String progressBrief(Map<String, WorkflowNodeRun> rowByNode) {
        int success = 0;
        int failed = 0;
        int waiting = 0;
        List<String> doneCodes = new ArrayList<>();
        for (WorkflowNodeRun row : rowByNode.values()) {
            WorkflowNodeRunStatus status = WorkflowNodeRunStatus.parse(row.getStatus());
            if (status == WorkflowNodeRunStatus.SUCCESS) {
                success++;
                if (doneCodes.size() < 5) {
                    doneCodes.add(row.getNodeCode());
                }
            } else if (status == WorkflowNodeRunStatus.FAILED) {
                failed++;
            } else if (status == WorkflowNodeRunStatus.WAITING_HUMAN
                    || status == WorkflowNodeRunStatus.WAITING_REPLY) {
                waiting++;
            }
        }
        String codes = doneCodes.isEmpty() ? "无" : String.join("、", doneCodes);
        return "已完成 " + success + " 个节点（" + codes + "），失败 " + failed
                + "，等待人工 " + waiting + "，共 " + rowByNode.size() + " 个";
    }
}
