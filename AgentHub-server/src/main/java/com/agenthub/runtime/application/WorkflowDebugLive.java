package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.WorkflowAgentFileScope;
import com.agenthub.runtime.domain.service.WorkflowCardProjector;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.domain.service.WorkflowTrialLive;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试试跑的直播实现。所属域：runtime 的 application。
 *
 * <p>职责：把现有 {@link WorkflowCardProjector} 挂到一次 TRIAL 运行上，把节点迁移和
 * 子 Agent 的思考 / 工具增量投影成同一张 {@link WorkflowRunView}，再按 run 存进
 * {@link WorkflowDebugLiveCards} 供调试抽屉读取。没有第二套卡片、没有第二套 SSE。
 *
 * <p>与工作台的边界：
 * <ul>
 *   <li>只接 {@code trigger_type=TRIAL} 且没有 {@code workbench_session_id} 的 run；
 *       工作台 run 返回 {@code null}，其会话直播链路完全不变；</li>
 *   <li>不写 {@code agent_run_event}，不建 {@code agent_run_session}；</li>
 *   <li>节点过程范围以 {@code runId} 作沙箱会话（{@code sandboxSessionId<=0} 时框架回落到 runId），
 *       与「工作流页单独运行」原有语义一致。</li>
 * </ul>
 *
 * <p>线程：{@link #open} 与 {@link #finish} 必须同线程成对调用；投影与节点过程范围
 * 都只活在这一次同步执行里。读请求线程只读 {@link WorkflowDebugLiveCards} 的整份快照。
 */
@Component
public class WorkflowDebugLive implements WorkflowTrialLive {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDebugLive.class);

    /** 当前执行线程上打开的投影；收口时用它保留已经流出的节点过程。 */
    private static final ThreadLocal<Active> ACTIVE = new ThreadLocal<>();

    private final WorkflowRunRepository runs;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowDefRepository defs;
    private final WorkflowDebugCardQuery cardQuery;
    private final WorkflowDebugLiveCards liveCards;
    private final ObjectMapper json;

    /**
     * @param runs      运行仓储（续跑种子 / 收口时重读终态）
     * @param nodeRuns  节点行（续跑种子与直播快照）
     * @param defs      工作流定义（卡片标题 / 收口文案）
     * @param cardQuery 按库现算卡片（直播入口丢失时兜底种子）
     * @param liveCards 按 run 隔离的直播快照
     * @param json      全站 ObjectMapper
     */
    public WorkflowDebugLive(WorkflowRunRepository runs, WorkflowNodeRunRepository nodeRuns,
                             WorkflowDefRepository defs, WorkflowDebugCardQuery cardQuery,
                             WorkflowDebugLiveCards liveCards, ObjectMapper json) {
        this.runs = runs;
        this.nodeRuns = nodeRuns;
        this.defs = defs;
        this.cardQuery = cardQuery;
        this.liveCards = liveCards;
        this.json = json;
    }

    /**
     * 为一次 TRIAL 挂上卡片投影与节点过程范围。
     *
     * <p>续跑时先接上一段正文：优先用内存里上一次已发布的卡片，内存没有（后端重启 / 条目过期）
     * 再按库现算一次已落库的卡片，并剥掉其中的收口提示，避免把「仍在运行 / 等待回复」
     * 这句旧提示永久留在正文里。
     *
     * @param run   准备调度的运行实例
     * @param reply 用户回复；新建或人工通过时为空
     * @return 节点观察者；非试跑 / 异常时为 {@code null}
     */
    @Override
    public WorkflowRunListener open(WorkflowRun run, String reply) {
        if (!isTrial(run)) {
            return null;
        }
        Long runId = run.getId();
        try {
            WorkflowDef def = definitionOf(run);
            String name = def == null || def.getName() == null ? "" : def.getName();
            String code = def == null || def.getCode() == null ? "" : def.getCode();

            List<WorkflowNodeRun> rows = nodeRuns.listByRunId(runId);
            WorkflowRunView previous = liveCards.viewOf(runId);
            String seed = liveCards.answerOf(runId);
            if (previous == null) {
                // 内存里没有这一段（后端重启 / 超过保留时长）：按库现算一次再接上
                previous = persistedView(run, rows);
                if (seed == null || seed.isEmpty()) {
                    seed = seedAnswerOf(run, rows);
                }
            }
            // 上一轮收口的「正在等待…」只属于那一轮：续跑后不能再出现在正文里
            seed = WorkflowOutcomeTexts.withoutWaitingHint(seed);
            // 新建（CREATED）且没有任何已流出正文时不按续跑处理；其余情况都接上上一段
            boolean resume = !seed.isEmpty()
                    || run.getStatus() != null && !"CREATED".equalsIgnoreCase(run.getStatus());

            RunBroadcast broadcast = new RunBroadcast(runId, liveCards);
            WorkflowCardProjector projector = new WorkflowCardProjector(name, code, broadcast, json,
                    resume, seed, reply);
            broadcast.bind(projector);
            /*
             * 新一轮开始时不把上一段已收口的卡当作首帧：那份卡带着 WAITING_REPLY / PAUSED_HUMAN，
             * 若请求线程恰好在 begin 之前读到，会把刚回到 RUNNING 的运行误判成又停了。
             * 正文延续由投影的 seed 负责，前端在首帧之前继续保留已经画出来的卡。
             */
            liveCards.begin(runId, run.getTenantId(), null, rows);

            // 节点过程范围：文件照旧收集，思考 / 工具 / 正文挂到当前节点；沙箱按 runId 回落
            WorkflowAgentFileScope.open(null, "", name, 0L, projector);
            ACTIVE.set(new Active(runId, projector));
            return broadcast;
        } catch (RuntimeException ex) {
            log.warn("调试直播挂载失败 runId={} type={}", runId, ex.getClass().getSimpleName());
            WorkflowAgentFileScope.close();
            ACTIVE.remove();
            // 挂到一半失败时已经登记过一条：丢掉，让前端退回详情现算，别停在永远不更新的运行中
            liveCards.discard(runId);
            return null;
        }
    }

    /**
     * 结算直播：优先用本次投影收口（保留已流出的思考与节点过程），
     * 没有本次投影时按库现算一张终态卡（例如人工驳回直接 FAILED）。
     *
     * @param runId 运行主键
     */
    @Override
    public void finish(Long runId) {
        if (runId == null) {
            return;
        }
        Active active = ACTIVE.get();
        if (active != null && runId.equals(active.runId())) {
            try {
                WorkflowRun fresh = runs.findById(runId).orElse(null);
                List<WorkflowNodeRun> rows = nodeRuns.listByRunId(runId);
                WorkflowRunView view = active.projector().finish(fresh, tailOf(fresh));
                liveCards.finish(runId, view, rows,
                        fresh == null ? null : fresh.getStatus(),
                        fresh == null ? null : fresh.getErrorSummary());
            } catch (RuntimeException ex) {
                log.warn("调试直播收口失败 runId={} type={}", runId, ex.getClass().getSimpleName());
                // 收口失败也不能把最后一帧留着显示「运行中」；按库再算一次终态卡
                finalizeFromDb(runId);
            } finally {
                WorkflowAgentFileScope.close();
                ACTIVE.remove();
            }
            return;
        }
        // 没有本次投影（人工驳回直接 FAILED / 取消时没有引擎线程）：按库现算收口
        finalizeFromDb(runId);
    }

    /**
     * 不依赖内存投影，按库里的 run 与节点行现算一张终态卡并收口。
     *
     * @param runId 运行主键
     */
    private void finalizeFromDb(Long runId) {
        try {
            WorkflowRun fresh = runs.findById(runId).orElse(null);
            if (!isTrial(fresh)) {
                return;
            }
            List<WorkflowNodeRun> rows = nodeRuns.listByRunId(runId);
            /*
             * 取消 / 人工驳回走请求线程，拿不到引擎线程上的投影（ACTIVE 是 ThreadLocal），
             * 只能按库现算终态卡。但库里没有「哪个节点写了哪个文件」这一列，
             * 现算的卡会把已经流出的文件行整块抹掉；这里从上一帧补回来，
             * 状态与正文仍以按库现算的为准（终态必须对齐）。
             */
            WorkflowRunView view = withNodeFiles(persistedView(fresh, rows), liveCards.viewOf(runId));
            liveCards.finish(runId, view, rows, fresh.getStatus(), fresh.getErrorSummary());
        } catch (RuntimeException ex) {
            log.warn("调试直播按库收口失败 runId={} type={}", runId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 把上一帧已经流出的节点文件行补到按库现算的卡片上。
     *
     * <p>为什么需要：{@code workflow_node_run} 没有文件清单这一列，取消 / 驳回这类
     * 不经引擎线程的收口只能按库现算终态卡；不补就会把用户已经看到的文件行连同下载入口一起抹掉。
     * 口径与刷新回放一致：同名文件只留一份，重建卡里已有的那份优先。
     *
     * <p>子图被拍平（按库还原子图范围失败、内部节点缺席）时，文件改挂到看得见的父图
     * SUBGRAPH 节点，不能随内部节点一起消失。
     *
     * @param rebuilt  按库现算的终态卡；可空
     * @param previous 上一帧直播卡；可空
     * @return 补好文件行的卡；没有可补的内容时是 {@code rebuilt} 本身
     */
    static WorkflowRunView withNodeFiles(WorkflowRunView rebuilt, WorkflowRunView previous) {
        if (rebuilt == null || previous == null || previous.nodes().isEmpty()) {
            return rebuilt;
        }
        Map<String, List<WorkflowRunView.NodeFile>> filesByKey = new LinkedHashMap<>();
        Map<String, WorkflowRunView.Node> previousByCode = new LinkedHashMap<>();
        for (WorkflowRunView.Node node : previous.nodes()) {
            String code = node.code() == null ? "" : node.code().trim();
            previousByCode.putIfAbsent(code, node);
            if (!node.files().isEmpty()) {
                filesByKey.putIfAbsent(nodeKeyOf(node), node.files());
            }
        }
        if (filesByKey.isEmpty()) {
            return rebuilt;
        }
        Map<String, WorkflowRunView.Node> rebuiltByKey = new LinkedHashMap<>();
        Map<String, WorkflowRunView.Node> rebuiltByCode = new LinkedHashMap<>();
        for (WorkflowRunView.Node node : rebuilt.nodes()) {
            rebuiltByKey.putIfAbsent(nodeKeyOf(node), node);
            String code = node.code() == null ? "" : node.code().trim();
            if (!code.isEmpty()) {
                rebuiltByCode.putIfAbsent(code, node);
            }
        }
        Map<String, List<WorkflowRunView.NodeFile>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorkflowRunView.NodeFile>> entry : filesByKey.entrySet()) {
            WorkflowRunView.Node target = rebuiltByKey.get(entry.getKey());
            if (target == null) {
                target = visibleFrameOf(previousByCode.get(codeOfKey(entry.getKey())),
                        previousByCode, rebuiltByCode);
            }
            if (target == null) {
                continue;
            }
            // 目标自己已经带的文件行优先，骨架只补缺的名字
            merged.merge(nodeKeyOf(target), unionFiles(target.files(), entry.getValue()),
                    WorkflowDebugLive::unionFiles);
        }
        if (merged.isEmpty()) {
            return rebuilt;
        }
        List<WorkflowRunView.Node> nodes = new ArrayList<>(rebuilt.nodes().size());
        for (WorkflowRunView.Node node : rebuilt.nodes()) {
            List<WorkflowRunView.NodeFile> extra = merged.get(nodeKeyOf(node));
            nodes.add(extra == null ? node : copyWithFiles(node, extra));
        }
        return new WorkflowRunView(rebuilt.name(), rebuilt.code(), rebuilt.status(), rebuilt.answer(),
                nodes, rebuilt.choice(), rebuilt.limits(), rebuilt.runId());
    }

    /**
     * 节点在重建卡里不见时，沿 {@code group} 往上找看得见的那层父图 SUBGRAPH 节点。
     *
     * @param source        上一帧里写出文件的节点
     * @param previousByCode 上一帧编码 → 节点（用来继续往上走）
     * @param rebuiltByCode  重建卡编码 → 节点
     * @return 可挂文件的子图节点；没有则为 {@code null}
     */
    private static WorkflowRunView.Node visibleFrameOf(WorkflowRunView.Node source,
                                                       Map<String, WorkflowRunView.Node> previousByCode,
                                                       Map<String, WorkflowRunView.Node> rebuiltByCode) {
        if (source == null) {
            return null;
        }
        String group = source.group() == null ? "" : source.group().trim();
        // 防御脏数据造成的环
        int guard = 0;
        while (!group.isEmpty() && guard < 32) {
            guard += 1;
            WorkflowRunView.Node frame = rebuiltByCode.get(group);
            if (frame != null && "SUBGRAPH".equalsIgnoreCase(frame.type())) {
                return frame;
            }
            WorkflowRunView.Node parent = previousByCode.get(group);
            group = parent == null || parent.group() == null ? "" : parent.group().trim();
        }
        return null;
    }

    /**
     * 合并文件行：已有那份优先，只补新名字。
     *
     * @param current 目标节点已有的文件行
     * @param extra   要补的文件行
     * @return 合并结果（不修改入参）
     */
    private static List<WorkflowRunView.NodeFile> unionFiles(List<WorkflowRunView.NodeFile> current,
                                                             List<WorkflowRunView.NodeFile> extra) {
        List<WorkflowRunView.NodeFile> merged = new ArrayList<>(current);
        Set<String> names = new LinkedHashSet<>();
        for (WorkflowRunView.NodeFile file : current) {
            names.add(file.fileName());
        }
        for (WorkflowRunView.NodeFile file : extra) {
            if (names.add(file.fileName())) {
                merged.add(file);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 复制一个节点并换掉文件行。
     *
     * @param node  原节点
     * @param files 新的文件行
     * @return 新节点
     */
    private static WorkflowRunView.Node copyWithFiles(WorkflowRunView.Node node,
                                                      List<WorkflowRunView.NodeFile> files) {
        return new WorkflowRunView.Node(node.code(), node.name(), node.type(), node.status(), node.note(),
                node.output(), node.steps(), files, node.reply(), node.attempt(), node.group(),
                node.loopRound(), node.loopTotal(), node.loopDecision(), node.displayCode());
    }

    /**
     * 节点匹配键：编码 + 轮次（缺省第 1 轮）。
     *
     * @param node 节点
     * @return {@code 编码#轮次}
     */
    private static String nodeKeyOf(WorkflowRunView.Node node) {
        String code = node.code() == null ? "" : node.code().trim();
        return code + "#" + Math.max(1, node.attempt());
    }

    /**
     * 从匹配键里取回节点编码。
     *
     * @param key {@code 编码#轮次}
     * @return 编码
     */
    private static String codeOfKey(String key) {
        int cut = key.lastIndexOf('#');
        return cut < 0 ? key : key.substring(0, cut);
    }

    /**
     * 按库现算一张卡片。复用详情接口同一个 {@link WorkflowDebugCardQuery#viewOf}，
     * 失败时返回 {@code null}，不打断调度。
     *
     * @param run  运行实例；可空
     * @param rows 节点行
     * @return 卡片；图不可读时为 {@code null}
     */
    private WorkflowRunView persistedView(WorkflowRun run, List<WorkflowNodeRun> rows) {
        if (run == null) {
            return null;
        }
        WorkflowRunDetail detail = new WorkflowRunDetail();
        detail.setRun(run);
        detail.setNodeRuns(rows);
        return cardQuery.viewOf(detail);
    }

    /**
     * 从已落库卡片里取出「纯正文」作为续跑种子：去掉平台等待提示，
     * 避免新一段继续追加时把上一轮的「等待回复 / 等待人工」也一起带上。
     *
     * @param run  运行实例
     * @param rows 节点行
     * @return 正文；没有内容时为空串
     */
    private String seedAnswerOf(WorkflowRun run, List<WorkflowNodeRun> rows) {
        WorkflowRunView view = persistedView(run, rows);
        return view == null ? "" : WorkflowOutcomeTexts.withoutWaitingHint(view.answer());
    }

    /**
     * 收口文案，与详情 / 工作台同一份口径。
     *
     * @param run 运行实例；可空
     * @return 文案；run 为空时仍返回一句兜底
     */
    private String tailOf(WorkflowRun run) {
        return WorkflowOutcomeTexts.assistantText(definitionOf(run), run, json);
    }

    /**
     * @param run 运行实例；可空
     * @return 定义；查不到时为 {@code null}
     */
    private WorkflowDef definitionOf(WorkflowRun run) {
        if (run == null || run.getWorkflowId() == null) {
            return null;
        }
        return defs.findById(run.getWorkflowId()).orElse(null);
    }

    /**
     * 只接受调试自己的试跑：工作台 run 不接，非 TRIAL 不接。
     *
     * @param run 运行实例
     * @return true 表示可以接直播
     */
    private static boolean isTrial(WorkflowRun run) {
        if (run == null || run.getId() == null || run.getWorkbenchSessionId() != null) {
            return false;
        }
        String trigger = run.getTriggerType();
        return trigger != null && "TRIAL".equalsIgnoreCase(trigger.trim());
    }

    /**
     * 一次执行线程上打开的投影。
     *
     * @param runId     运行主键
     * @param projector 卡片投影
     */
    private record Active(Long runId, WorkflowCardProjector projector) {
    }

    /**
     * 节点观察者 + 直播出口。
     *
     * <p>把引擎的节点迁移转给投影，把投影吐出的整卡视图按 run 存快照；同时按节点行
     * 维护一份最新的节点清单，供每一帧一起发布，画布高亮不必再等详情轮询。
     */
    private static final class RunBroadcast implements WorkflowRunListener, TrialProgress {

        private final Long runId;
        private final WorkflowDebugLiveCards cards;
        /**
         * 节点行快照：节点迁移在引擎线程写，卡片出帧可能来自子 Agent 线程，
         * 两边会并发读写，所以用并发 Map，读侧再复制一份整表。
         */
        private final Map<String, WorkflowNodeRun> rows = new ConcurrentHashMap<>();
        private WorkflowCardProjector projector;

        private RunBroadcast(Long runId, WorkflowDebugLiveCards cards) {
            this.runId = runId;
            this.cards = cards;
        }

        /**
         * 绑定本次投影；投影会在每次出帧时回调 {@link #workflow(WorkflowRunView)}。
         *
         * @param projector 卡片投影
         */
        private void bind(WorkflowCardProjector projector) {
            this.projector = projector;
        }

        @Override
        public void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> currentRows) {
            capture(currentRows);
            projector.runStarted(nodes, currentRows);
        }

        @Override
        public void runStarted(List<WorkflowNode> nodes, List<WorkflowNodeRun> currentRows,
                               Map<String, List<WorkflowEdge>> outgoing) {
            capture(currentRows);
            projector.runStarted(nodes, currentRows, outgoing);
        }

        @Override
        public void scopeStarted(com.agenthub.workflow.domain.model.WorkflowScopeView scope) {
            if (scope == null) {
                return;
            }
            // 子图节点行也要进直播快照：画布高亮与过程区框共用同一份行清单
            for (WorkflowNodeRun row : scope.rows()) {
                capture(row);
            }
            projector.scopeStarted(scope);
        }

        @Override
        public void nodeChanged(WorkflowNode node, WorkflowNodeRun row) {
            capture(row);
            projector.nodeChanged(node, row);
        }

        @Override
        public void limits(com.agenthub.workflow.domain.service.WorkflowCardLimits limits) {
            projector.limits(limits);
        }

        /**
         * 投影出一帧整卡：存内存快照，不推 SSE、不写事件表。
         *
         * @param view 整卡视图
         */
        @Override
        public void workflow(WorkflowRunView view) {
            cards.publish(runId, view, List.copyOf(rows.values()));
        }
        /**
         * 记住一行的最新状态。key 与投影一致：nodeId#attempt，缺失 nodeId 时退到 nodeCode。
         *
         * @param row 节点行；可空
         */
        private void capture(WorkflowNodeRun row) {
            if (row == null) {
                return;
            }
            int attempt = row.getAttempt() == null || row.getAttempt() < 1 ? 1 : row.getAttempt();
            String nodeKey = row.getNodeId() == null ? row.getNodeCode() : row.getNodeId();
            rows.put((nodeKey == null ? "" : nodeKey) + "#" + attempt, row);
        }

        /**
         * 批量记住节点行；同 key 以新行为准。
         *
         * @param currentRows 节点行；可空
         */
        private void capture(List<WorkflowNodeRun> currentRows) {
            if (currentRows == null) {
                return;
            }
            for (WorkflowNodeRun row : currentRows) {
                capture(row);
            }
        }

        @Override
        public void phase(String phase) {
            // 阶段切换不单独进工作流卡
        }

        @Override
        public void route(String summary) {
            // 子专家路由句不进工作流卡
        }

        @Override
        public void reasoning(String delta) {
            // 节点思考由 WorkflowAgentFileScope 直接交给投影，这里不重复转发
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            // 节点工具过程由 WorkflowAgentFileScope 直接交给投影
        }

        @Override
        public void assistant(String delta) {
            // 子专家 token 不直接进卡片正文；节点完成时由投影追加成文结论
        }
    }
}
