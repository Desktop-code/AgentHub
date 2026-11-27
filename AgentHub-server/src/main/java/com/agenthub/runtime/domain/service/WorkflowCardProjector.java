package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowScopeView;
import com.agenthub.workflow.domain.service.WorkflowCardLimits;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流节点事件 → 消息流执行卡片视图。所属域：runtime 的 domain。
 * <p>为什么单独成类：同一张卡片有两条<b>互斥</b>来源——首次发起（{@code WorkflowTargetRunnerAdapter}）
 * 与「等待用户回复」后的续跑（{@code WorkflowReplyResumer}）。两者必须共用同一套投影，
 * 否则直播与回放、第一段与续跑段会长得不一样。抽出本类后，两边只负责「谁去跑」，
 * 卡片形状只有这一份实现。
 *
 * <p>正文增量：工作流没有 token 通道，但每完成一个 AGENT 节点就真的产出了一段正文。
 * 这里按节点完成顺序把这些片段追加进快照的 {@code answer}，前端对同一张卡原位覆盖，
 * 文字就是一段段长出来的。「只增不减」是硬约束：收尾文本只会是已流出片段的延长，绝不回退成空。
 *
 * <p>边界：不整图倒 node 输出。只取 AGENT 节点里像答案的那一段文本（口径同
 * {@link WorkflowOutcomeTexts}）；工具节点输出常是 JSON / 日志原文，留在节点时间轴与 END 映射里。
 * START / END 不作为执行步骤展示——它们不是用户需要看到的「一步」。
 *
 * <p>线程：只在该轮执行线程内使用，不跨线程共享；观察回调由引擎同步触发。
 */
public final class WorkflowCardProjector implements WorkflowRunListener, WorkflowNodeStepSink {

    /** 正文硬顶：与终答同一档，避免节点很多时一次灌满对话框。 */
    private static final int ANSWER_MAX = 6000;
    /** 单个节点正文的展示硬顶：节点很多时，每个子 Agent 的整段代码都不该无限撑大同一张卡。 */
    private static final int OUTPUT_MAX = 6000;
    /** 单个节点思考 / 工具结果的展示硬顶：卡片只展示四行，无需把整段工具日志带进快照。 */
    private static final int STEP_MAX = 4000;
    /** 节点过程快照的最小推送间隔（毫秒）：流式思考逐字推太密，攒一拍再统一出快照。 */
    private static final long LIVE_EMIT_GAP_MS = 150;

    /** 卡片展示名（配置名；为空时前端回落到编码） */
    private final String name;
    /** 工作流编码（可空，仅作附注） */
    private final String code;
    /** 实时出口；可空（同步调试路径没有进度通道） */
    private final TrialProgress progress;
    /** 解析节点输出用的 ObjectMapper；抽不出文本时当「这一步没有正文」 */
    private final ObjectMapper json;
    /** 节点 id → 已展示步骤；按图顺序写入 LinkedHashMap，保证时间轴顺序 */
    private final Map<String, WorkflowRunView.Node> nodes = new LinkedHashMap<>();
    /** 已按完成顺序流出的正文片段（只增不减） */
    private final List<String> streamed = new ArrayList<>();
    /** 已经收进正文的节点：同一节点状态多次迁移也只追加一次 */
    private final Set<String> streamedNodes = new HashSet<>();
    /** 节点编码 → 该节点自己的思考 / 工具步（按发生顺序，运行中持续增长） */
    private final Map<String, List<WorkflowRunView.Step>> liveSteps = new LinkedHashMap<>();
    /** 节点编码 → 该节点正在流出的正文（工具轮里通常到收口才有整段） */
    private final Map<String, StringBuilder> liveOutput = new LinkedHashMap<>();
    /** 节点编码 → 该节点写出的文件（按文件名去重，保留第一次的体积） */
    private final Map<String, List<WorkflowRunView.NodeFile>> nodeFiles = new LinkedHashMap<>();
    /**
     * 节点 id → 所属循环体的循环节点编码。
     *
     * <p>只用于展示分组：把同一循环体的体节点圈进一个框。由图上 {@code LOOP_BODY} /
     * {@code LOOP_BACK} 边算出（{@code WorkflowLoopBodies}），不参与调度、不落库。
     * 没有循环图时为空，节点照旧平铺，展示与改动前一致。
     */
    private final Map<String, String> loopGroupByNode = new LinkedHashMap<>();
    /** 节点 id → 图内位置：开始时刻相同时按图顺序排，未开始的节点排在最后 */
    private final Map<String, Integer> nodeIndexById = new LinkedHashMap<>();
    /**
     * 子图节点 id → 原始编码。子图运行行用带范围前缀的编码保证唯一，
     * 卡片附注仍显示用户画布上的原编码，不把内部前缀暴露给用户。
     */
    private final Map<String, String> displayCodeByNodeId = new LinkedHashMap<>();
    /**
     * 子图节点 id → 父图 SUBGRAPH 节点编码。
     *
     * <p>直播的 {@code nodeChanged} 只拿到节点本身，必须靠这份映射把子图内部节点
     * 继续归到同一个框下；否则状态一变就会从框里掉出来。
     */
    private final Map<String, String> scopeGroupByNodeId = new LinkedHashMap<>();
    /**
     * 节点视图键（{@code nodeId#attempt}）→ 该轮开始时刻（epoch 毫秒）。
     *
     * <p>多轮行是后插进库的，直接按插入顺序铺会把第 2 轮排到循环外的下游节点之后，
     * 时间线就成了「点评先出现、循环框接在最后」。出快照时按真实开始时刻重排，
     * 每一轮就落回它真正发生的位置。这条顺序只影响展示，调度仍只看最新行。
     */
    private final Map<String, Long> nodeStartMs = new LinkedHashMap<>();
    /** 上一次节点过程快照的推送时间：思考 / 正文增量按此降频 */
    private long lastLiveEmitAt;
    /**
     * 本次运行的 {@code workflow_run} 主键。
     *
     * <p>落库骨架只留指针，刷新回放要按它从 {@code workflow_node_run} 现算节点产出；
     * 引擎每次回调都带运行态行，随时可以更新，因此不要求构造方预先知道。
     */
    private volatile Long runId;
    /**
     * 是否「续跑」这一段的投影。
     *
     * <p>续跑时引擎开跑前会上报全部节点行，其中之前已经成功的节点带着旧产出。
     * 若照常按顺序补正文，上一轮已经发过的问句会被整段重复到新的这一条回复里。
     * 续跑模式下先把这些「已完成」节点登记成已流出，只让本次真正新完成的节点写正文。
     */
    private final boolean resume;

    /**
     * 续跑前已经流出的正文（上一轮卡片的 answer）。
     *
     * <p>续跑是新一张回复，但同一条运行的正文不该在用户回答后消失或整段重来：
     * 开跑时先把这段铺进已流出片段，本次新完成的节点在它后面继续追加。
     */
    private final String seedAnswer;

    /**
     * 用户对等待中询问的回复。
     *
     * <p>只在「点了选项 / 在输入框提交」续跑这一条运行时传入。它不落成新的用户消息，
     * 而是挂到被答复的询问节点上（节点产出里仍有 {@code choice} 结构可认）。
     * 首次发起的投影传空串，节点不会多出这一行。
     */
    private final String reply;

    /**
     * @param name     工作流展示名；可空
     * @param code     工作流编码；可空
     * @param progress 实时出口；可空
     * @param json     全站 ObjectMapper
     */
    public WorkflowCardProjector(String name, String code, TrialProgress progress, ObjectMapper json) {
        this(name, code, progress, json, false, null, null);
    }

    /**
     * @param name     工作流展示名；可空
     * @param code     工作流编码；可空
     * @param progress 实时出口；可空
     * @param json     全站 ObjectMapper
     * @param resume   true 表示这是在已有运行上续跑（不重复上一轮已流出的正文）
     * @param seedAnswer 续跑前已经流出的正文；首次发起传 null
     */
    public WorkflowCardProjector(String name, String code, TrialProgress progress, ObjectMapper json,
                                 boolean resume, String seedAnswer) {
        this(name, code, progress, json, resume, seedAnswer, null);
    }

    /**
     * @param name       工作流展示名；可空
     * @param code       工作流编码；可空
     * @param progress   实时出口；可空
     * @param json       全站 ObjectMapper
     * @param resume     true 表示这是在已有运行上续跑（不重复上一轮已流出的正文）
     * @param seedAnswer 续跑前已经流出的正文；首次发起传 null
     * @param reply      用户对等待中询问的回复；非续跑传 null
     */
    public WorkflowCardProjector(String name, String code, TrialProgress progress, ObjectMapper json,
                                 boolean resume, String seedAnswer, String reply) {
        this.name = name;
        this.code = code;
        this.progress = progress;
        this.json = json;
        this.resume = resume;
        this.seedAnswer = seedAnswer == null ? "" : seedAnswer.trim();
        this.reply = reply == null ? "" : reply.trim();
    }

    /**
     * 当前这张卡要展示的可点选项。
     *
     * <p>只在「等用户回复」的询问节点上出现；其它节点完成、续跑结束、取消之后一律清空，
     * 避免已经选过的选项在后续快照里继续可点。
     */
    private WorkflowRunView.Choice choice;

    /**
     * 引擎上报的时限快照。
     *
     * <p>只更新账本、不单独推帧：它随下一次 {@link #nodeChanged} / 收口帧一起带出，
     * 卡片一帧里就有「节点 + 该节点上限 + 剩余」，不会出现两者差一拍的情况。
     */
    private volatile WorkflowCardLimits limitSnapshot;

    @Override
    public void limits(WorkflowCardLimits limits) {
        this.limitSnapshot = limits;
    }

    @Override
    public void runStarted(List<WorkflowNode> graphNodes, List<WorkflowNodeRun> rows) {
        runStarted(graphNodes, rows, null);
    }

    /**
     * 调度开始：铺出全部节点，并按图上的循环边算出「体节点归属哪个循环」。
     *
     * <p>循环分组只用于展示（把同一循环体的多轮圈进一个框），不参与调度、不落库。
     * 没有出边索引（旧调用点 / 测试替身）时分组为空，节点照旧平铺。
     *
     * @param graphNodes 图内节点，按图顺序
     * @param rows       运行态行（含历史轮次）
     * @param outgoing   节点 id → 出边；可空
     */
    @Override
    public void runStarted(List<WorkflowNode> graphNodes, List<WorkflowNodeRun> rows,
                           Map<String, List<WorkflowEdge>> outgoing) {
        nodes.clear();
        // 新一轮：上一轮的片段必须清掉，否则复用的快照会把旧正文带进这一轮
        streamed.clear();
        streamedNodes.clear();
        // 节点内过程同样按轮清空：复用的投影对象不能把上一轮思考带到新卡片
        liveSteps.clear();
        liveOutput.clear();
        nodeFiles.clear();
        nodeStartMs.clear();
        displayCodeByNodeId.clear();
        scopeGroupByNodeId.clear();
        lastLiveEmitAt = 0L;
        // 续跑轮：上一轮已经点过的选项不再展示（用户已经选完了）
        choice = null;
        // 续跑：上一句已经流出的问句先铺好，后面新完成的节点接在它后面（只增不减）
        if (resume && !seedAnswer.isEmpty()) {
            streamed.add(seedAnswer);
        }
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : graphNodes == null ? List.<WorkflowNode>of() : graphNodes) {
            if (node != null && node.getId() != null) {
                byId.put(node.getId(), node);
            }
        }
        // 循环分组：体节点 → 循环编码。只算一次，后面每个视图都读这份
        loopGroupByNode.clear();
        loopGroupByNode.putAll(WorkflowLoopCards.groupByNode(graphNodes,
                WorkflowLoopCards.bodiesOf(graphNodes, outgoing)));
        // 图内位置：多轮行是后插进库的，出快照时按「开始时刻 + 图顺序」重排，轮次才不会散
        nodeIndexById.clear();
        List<WorkflowNode> ordered = graphNodes == null ? List.of() : graphNodes;
        for (int index = 0; index < ordered.size(); index += 1) {
            WorkflowNode node = ordered.get(index);
            if (node != null && node.getId() != null) {
                nodeIndexById.put(node.getId(), index);
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WorkflowNodeRun row : rows == null ? List.<WorkflowNodeRun>of() : rows) {
            if (row == null || row.getNodeId() == null) {
                continue;
            }
            if (runId == null && row.getRunId() != null) {
                runId = row.getRunId();
            }
            WorkflowNode node = byId.get(row.getNodeId());
            if (node == null || isBoundary(node)) {
                continue;
            }
            int attempt = attemptOf(row);
            String status = row.getStatus() == null ? "" : row.getStatus().trim().toUpperCase();
            /*
             * 下一轮预建的空行不铺：循环会提前为体节点插入 attempt+1 的 PENDING 行，
             * 若照常铺出来，框里会先出现一张「第 2 轮 · 等待前置节点」的空卡。
             * 那一轮真开始（nodeChanged）时自然会进来。
             */
            if (attempt > 1 && "PENDING".equals(status)) {
                seen.add(row.getNodeId());
                continue;
            }
            // 同一节点的每一轮各占一条，后一轮不覆盖前一轮
            String viewKey = row.getNodeId() + "#" + attempt;
            nodes.put(viewKey, toView(node, row));
            // 真实开始时刻：出快照时按它重排，让每一轮落回它发生的位置
            nodeStartMs.put(viewKey, startMsOf(row, attempt));
            seen.add(row.getNodeId());
            if (resume) {
                if ("SUCCESS".equals(status)) {
                    streamedNodes.add(row.getNodeId() + "#" + attempt);
                }
            }
        }
        for (WorkflowNode node : graphNodes == null ? List.<WorkflowNode>of() : graphNodes) {
            if (node == null || isBoundary(node) || seen.contains(node.getId())) {
                continue;
            }
            nodes.put(node.getId() + "#1", toView(node, null));
        }
        emitRunning();
    }

    /**
     * 进入一层子图：把子图自己的节点补进同一张卡，并归到父图 SUBGRAPH 节点的框下。
     *
     * <p>只做展示：不改调度、不另开卡片 / 时间线。框的默认展开由前端负责，
     * 这里只保证「子图还没跑到时没有节点」，不会提前摆出空框。
     *
     * @param scope 子图范围快照；可空
     */
    @Override
    public void scopeStarted(WorkflowScopeView scope) {
        if (scope == null || scope.nodes().isEmpty()) {
            return;
        }
        registerScope(scope);
        emitRunning();
    }

    /**
     * 把一层子图的节点、循环分组和展示编码登记进同一份时间线。
     *
     * <p>子图节点的 id / code 已带范围前缀，与父图同名节点不会互相覆盖；
     * 展示编码另存一份，步骤卡附注仍显示画布上的原编码。
     *
     * @param scope 子图范围
     */
    private void registerScope(WorkflowScopeView scope) {
        String defaultGroup = scope.parentNodeCode();
        // 子图内部的循环仍用同一套循环框；编码带范围前缀，不会和父图循环撞框
        loopGroupByNode.putAll(WorkflowLoopCards.groupByNode(scope.nodes(),
                WorkflowLoopCards.bodiesOf(scope.nodes(), scope.outgoing())));
        for (WorkflowNode node : scope.nodes()) {
            if (node == null || node.getId() == null) {
                continue;
            }
            String displayCode = scope.displayCodeOf(node.getId());
            if (!displayCode.isBlank()) {
                displayCodeByNodeId.put(node.getId(), displayCode);
            }
            // 子图里的每个节点都记住外层框编码，直播覆盖同一视图键时不会把 group 丢掉
            if (defaultGroup != null && !defaultGroup.isBlank()) {
                scopeGroupByNodeId.put(node.getId(), defaultGroup);
            }
            if (!isBoundary(node) && !nodeIndexById.containsKey(node.getId())) {
                nodeIndexById.put(node.getId(), nodeIndexById.size());
            }
        }
        // 同一节点可能有多轮（循环内的子图），每一轮各占一条；同 key 以新行为准
        Map<String, List<WorkflowNodeRun>> rowsByNode = new LinkedHashMap<>();
        for (WorkflowNodeRun row : scope.rows()) {
            if (row == null || row.getNodeId() == null) {
                continue;
            }
            rowsByNode.computeIfAbsent(row.getNodeId(), ignored -> new ArrayList<>()).add(row);
        }
        for (WorkflowNode node : scope.nodes()) {
            if (node == null || node.getId() == null || isBoundary(node)) {
                continue;
            }
            List<WorkflowNodeRun> rows = rowsByNode.get(node.getId());
            if (rows == null || rows.isEmpty()) {
                nodes.putIfAbsent(node.getId() + "#1", toView(node, null, defaultGroup));
                continue;
            }
            for (WorkflowNodeRun row : rows) {
                int attempt = attemptOf(row);
                String status = row.getStatus() == null ? "" : row.getStatus().trim().toUpperCase();
                if (attempt > 1 && "PENDING".equals(status)) {
                    continue;
                }
                String viewKey = node.getId() + "#" + attempt;
                nodes.put(viewKey, toView(node, row, defaultGroup));
                nodeStartMs.put(viewKey, startMsOf(row, attempt));
                if (resume && "SUCCESS".equals(status)) {
                    streamedNodes.add(viewKey);
                }
            }
        }
    }

    @Override
    public void nodeChanged(WorkflowNode node, WorkflowNodeRun row) {
        if (node == null || isBoundary(node)) {
            return;
        }
        // 运行主键以运行态行为准：引擎每次迁移都带这一行，缺失时保留上一次的值
        if (runId == null && row != null) {
            runId = row.getRunId();
        }
        int attempt = row == null ? 1 : attemptOf(row);
        String nodeKey = (node.getId() == null ? node.getCode() : node.getId()) + "#" + attempt;
        nodes.put(nodeKey, toView(node, row));
        /*
         * 这一拍的开始时刻：多轮排序靠它落回原位。
         * 只有真的拿到开始时间才覆盖——补帧 / 旧快照没有 startTime 时不能把已知时刻抹掉。
         */
        if (row != null && row.getStartTime() != null) {
            nodeStartMs.put(nodeKey, startMsOf(row, attempt));
        }
        // 先收正文再出快照：这一拍就能看到刚完成那一步的文字，而不是下一拍才补
        appendNodeText(node, row);
        // 选项跟着问句一起出：等待这一拍就带 chips，其余状态拍一律不带
        choice = choiceOf(row);
        emitRunning();
    }

    /**
     * 节点思考增量：接在该节点最近一段思考后面；遇到工具后另起一段，顺序与真实执行一致。
     *
     * @param nodeCode 节点编码
     * @param delta    思考增量
     */
    @Override
    public void reasoning(String nodeCode, String delta) {
        if (nodeCode == null || nodeCode.isBlank() || delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            List<WorkflowRunView.Step> steps = stepList(liveKey(nodeCode));
            int last = steps.size() - 1;
            if (last >= 0 && "REASONING".equals(steps.get(last).type())) {
                WorkflowRunView.Step previous = steps.get(last);
                steps.set(last, new WorkflowRunView.Step("REASONING", "",
                        clip(previous.content() + delta, STEP_MAX), ""));
            } else {
                steps.add(new WorkflowRunView.Step("REASONING", "", clip(delta, STEP_MAX), ""));
            }
        }
        emitLiveThrottled();
    }

    /**
     * 节点工具开始：先落一条 running 工具步，前端在四行窗口里先看到「正在调用」。
     *
     * @param nodeCode 节点编码
     * @param name     工具名
     */
    @Override
    public void toolStart(String nodeCode, String name) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return;
        }
        synchronized (this) {
            stepList(liveKey(nodeCode)).add(new WorkflowRunView.Step("TOOL", name, "", "RUNNING"));
        }
        emitRunning();
    }

    /**
     * 节点工具结束：把最近一条同名 running 工具步补成成功 / 失败；没有开始帧时补一条完整步。
     *
     * @param nodeCode 节点编码
     * @param name     工具名
     * @param result   工具结果（已脱敏 / 已截断）
     * @param success  是否成功
     */
    @Override
    public void tool(String nodeCode, String name, String arguments, String result, boolean success) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return;
        }
        synchronized (this) {
            List<WorkflowRunView.Step> steps = stepList(liveKey(nodeCode));
            String state = success ? "DONE" : "ERROR";
            String body = clip(result, STEP_MAX);
            for (int index = steps.size() - 1; index >= 0; index--) {
                WorkflowRunView.Step step = steps.get(index);
                if ("TOOL".equals(step.type()) && "RUNNING".equals(step.state())
                        && (name == null || name.equals(step.name()))) {
                    steps.set(index, new WorkflowRunView.Step("TOOL", step.name(), body, state));
                    emitRunning();
                    return;
                }
            }
            steps.add(new WorkflowRunView.Step("TOOL", name, body, state));
        }
        emitRunning();
    }

    /**
     * 节点正文增量：运行中先挂在该节点里；收口时若库里产出更完整，再以库里那份为准。
     *
     * @param nodeCode 节点编码
     * @param delta    正文增量
     */
    @Override
    public void assistant(String nodeCode, String delta) {
        if (nodeCode == null || nodeCode.isBlank() || delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            StringBuilder buffer = liveOutput.computeIfAbsent(liveKey(nodeCode), ignored -> new StringBuilder());
            buffer.append(delta);
            if (buffer.length() > ANSWER_MAX) {
                buffer.setLength(ANSWER_MAX);
            }
        }
        emitLiveThrottled();
    }

    /**
     * 节点写出文件：只记文件名与体积，不把文件正文塞进卡片；同名文件只留一份。
     *
     * @param nodeCode 节点编码
     * @param fileName 相对工作区根的文件名
     * @param size     字节数
     */
    @Override
    public void file(String nodeCode, String fileName, long size) {
        if (nodeCode == null || nodeCode.isBlank() || fileName == null || fileName.isBlank()) {
            return;
        }
        synchronized (this) {
            List<WorkflowRunView.NodeFile> files = nodeFiles.computeIfAbsent(
                    liveKey(nodeCode), ignored -> new ArrayList<>());
            String name = fileName.trim();
            for (WorkflowRunView.NodeFile existing : files) {
                // 同名覆盖体积：重复投递（实时回调 + 收口补一次）不能刷出一排空行
                if (existing.fileName().equals(name)) {
                    return;
                }
            }
            files.add(new WorkflowRunView.NodeFile(name, size));
        }
        // 文件行不触发整份快照：工具线程上写 SSE 会和其他节点迁移抢同一出口，
        // 下一拍 nodeChanged（或收口）会把这份清单一起带出去。
    }

    /**
     * 只凭已落库的 run 与节点行现算一张卡片，不依赖会话事件，也不推 SSE。
     *
     * <p>调试抽屉轮询详情时没有 {@code agent_run_event} 骨架。这里复用直播同一套
     * {@link #runStarted} / {@link #toView} / {@link #nodeText} / 循环分组，
     * 把库里的状态、问句、选项、节点正文和回复填进 {@link WorkflowRunView}。
     * 思考与工具的逐字过程只存在于直播回调，库里没有就不编造。
     *
     * <p>不改 {@link #nodeChanged} 与回放补全：那些路径仍按原来的内存增量走。
     *
     * @param run        运行实例；可空时按失败卡片
     * @param graphNodes 图内节点（含 START / END，展示时会剔掉）
     * @param outgoing   节点 id → 出边；可空则循环框不分组
     * @param rows       该 run 的节点行（含历史轮次与 output_json / reply_text）
     * @param tail       收口说明（成功终答 / 失败原因 / 等待提示）；可空
     * @return 与直播收口同一形状的视图
     */
    public WorkflowRunView persisted(WorkflowRun run, List<WorkflowNode> graphNodes,
                                     Map<String, List<WorkflowEdge>> outgoing,
                                     List<WorkflowNodeRun> rows, String tail) {
        return persisted(run, graphNodes, outgoing, rows, List.of(), tail);
    }

    /**
     * 只凭已落库的 run、节点行与已经还原的子图范围现算一张卡片。
     *
     * <p>子图范围按父 → 子的先序补进同一张卡；每层内部的循环仍用原有循环框。
     *
     * @param run        运行实例；可空时按失败卡片
     * @param graphNodes 父图节点（SUBGRAPH 节点名已换成「被调工作流名 · vN」）
     * @param outgoing   父图节点 id → 出边；可空则循环框不分组
     * @param rows       该 run 的节点行（含各层子图与历史轮次）
     * @param scopes     已还原的子图范围，父 → 子先序；可空
     * @param tail       收口说明；可空
     * @return 与直播收口同一形状的视图
     */
    public WorkflowRunView persisted(WorkflowRun run, List<WorkflowNode> graphNodes,
                                     Map<String, List<WorkflowEdge>> outgoing,
                                     List<WorkflowNodeRun> rows, List<WorkflowScopeView> scopes,
                                     String tail) {
        runId = run == null ? null : run.getId();
        // 先按图铺节点：产出、循环框、状态都走直播的 toView，不另写一套映射
        runStarted(graphNodes, rows, outgoing);
        // 子图范围只补「已经发生」的那几层：没跑到的子图不提前占空框
        for (WorkflowScopeView scope : scopes == null ? List.<WorkflowScopeView>of() : scopes) {
            registerScope(scope);
        }
        Map<String, WorkflowNode> byId = new LinkedHashMap<>();
        for (WorkflowNode node : graphNodes == null ? List.<WorkflowNode>of() : graphNodes) {
            if (node != null && node.getId() != null) {
                byId.put(node.getId(), node);
            }
        }
        for (WorkflowScopeView scope : scopes == null ? List.<WorkflowScopeView>of() : scopes) {
            for (WorkflowNode node : scope.nodes()) {
                if (node != null && node.getId() != null) {
                    byId.putIfAbsent(node.getId(), node);
                }
            }
        }
        choice = null;
        for (WorkflowNodeRun row : orderedByStart(rows)) {
            WorkflowNode node = row == null ? null : byId.get(row.getNodeId());
            if (node != null) {
                appendNodeText(node, row);
            }
            WorkflowRunView.Choice one = choiceOf(row);
            if (one != null) {
                choice = one;
            }
        }
        // 回复的持久真源是 reply_text；直播续跑靠构造参数，这里不能改那条路径
        applyStoredReplies(rows);
        String status = run == null || run.getStatus() == null ? "FAILED" : run.getStatus();
        List<WorkflowRunView.Node> viewNodes = nodeViews();
        if ("CANCELLED".equalsIgnoreCase(status)) {
            viewNodes = viewNodes.stream().map(WorkflowCardProjector::settleCancelledNode).toList();
            choice = null;
        }
        boolean live = "CREATED".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status);
        WorkflowRunView.Choice shown = "WAITING_REPLY".equalsIgnoreCase(status) ? choice : null;
        WorkflowRunView.Limits limits = live ? pollLimits(run, rows, byId) : finishLimits(run);
        return new WorkflowRunView(nameOf(), codeOf(), status, settleAnswer(run, tail),
                viewNodes, shown, limits, runId);
    }

    /**
     * 按开始时刻排序，未开始的排在后面。只影响正文拼接顺序，不改库。
     *
     * @param rows 节点行；可空
     * @return 新列表
     */
    private static List<WorkflowNodeRun> orderedByStart(List<WorkflowNodeRun> rows) {
        List<WorkflowNodeRun> ordered = new ArrayList<>(rows == null ? List.of() : rows);
        ordered.sort((left, right) -> {
            if (left == null || left.getStartTime() == null) {
                return right == null || right.getStartTime() == null ? 0 : 1;
            }
            if (right == null || right.getStartTime() == null) {
                return -1;
            }
            return left.getStartTime().compareTo(right.getStartTime());
        });
        return ordered;
    }

    /**
     * 把 {@code reply_text} 挂回对应节点。直播续跑不走这里。
     *
     * @param rows 节点行；可空
     */
    private void applyStoredReplies(List<WorkflowNodeRun> rows) {
        if (rows == null) {
            return;
        }
        for (WorkflowNodeRun row : rows) {
            if (row == null || row.getNodeId() == null) {
                continue;
            }
            String replyText = row.getReplyText() == null ? "" : row.getReplyText().trim();
            if (replyText.isEmpty()) {
                continue;
            }
            String key = row.getNodeId() + "#" + attemptOf(row);
            WorkflowRunView.Node current = nodes.get(key);
            if (current == null || replyText.equals(current.reply())) {
                continue;
            }
            nodes.put(key, new WorkflowRunView.Node(current.code(), current.name(), current.type(),
                    current.status(), current.note(), current.output(), current.steps(), current.files(),
                    replyText, current.attempt(), current.group(), current.loopRound(), current.loopTotal(),
                    current.loopDecision(), current.displayCode()));
        }
    }

    /**
     * 轮询时的时限：只使用 run 上已经落库的截止，不发明当前节点的剩余。
     *
     * @param run   运行实例；可空
     * @param rows  节点行
     * @param byId  节点 id → 定义
     * @return 时限；没有整段截止时为空
     */
    private WorkflowRunView.Limits pollLimits(WorkflowRun run, List<WorkflowNodeRun> rows,
                                              Map<String, WorkflowNode> byId) {
        if (run == null || run.getRunDeadline() == null) {
            return null;
        }
        WorkflowNodeRun active = null;
        if (rows != null) {
            for (WorkflowNodeRun row : rows) {
                if (row != null && "RUNNING".equalsIgnoreCase(row.getStatus())) {
                    active = row;
                }
            }
        }
        String nodeCode = null;
        String nodeName = null;
        if (active != null) {
            // 子图节点行存的是范围前缀后的编码；时限行展示原始编码，不把内部前缀带给用户
            nodeCode = displayCodeByNodeId.getOrDefault(active.getNodeId(), active.getNodeCode());
            WorkflowNode node = byId.get(active.getNodeId());
            nodeName = node == null || node.getName() == null || node.getName().isBlank()
                    ? nodeCode : node.getName();
        }
        Long nodeLimit = run.getNodeLimitMs();
        return new WorkflowRunView.Limits(
                run.getRunLimitMs() == null ? 0L : run.getRunLimitMs(),
                epochOf(run.getRunDeadline()),
                epochOf(run.getWaitingSince()),
                nodeCode,
                nodeName,
                nodeLimit != null && nodeLimit > 0 ? nodeLimit : null,
                null);
    }

    /**
     * 收口：用 run 的最终状态与终答文本生成最后一份卡片。
     *
     * @param run    数据库读回的 run
     * @param answer 终答文本（成功摘要 / 失败原因 / 等待提示）
     * @return 终态视图
     */
    public WorkflowRunView finish(WorkflowRun run, String answer) {
        String status = run == null ? "FAILED" : run.getStatus();
        // 收口帧带运行主键：落库骨架靠它让刷新回放按 run 现算节点产出
        if (run != null && run.getId() != null) {
            runId = run.getId();
        }
        List<WorkflowRunView.Node> viewNodes = nodeViews();
        /*
         * 取消收口：库里已经把在途节点写成 CANCELLED，但本收集器是旁路，
         * 取消接口不会回写它的内存视图。若原样落库，刷新时卡片头是「已取消」、
         * 节点却仍显示「执行中」并转圈。这里按库里 cancelOpen 的同一口径补一次。
         */
        if ("CANCELLED".equalsIgnoreCase(status)) {
            viewNodes = viewNodes.stream().map(WorkflowCardProjector::settleCancelledNode).toList();
            // 已中断：选项不能再点
            choice = null;
        }
        /*
         * 只有真正停在等待回复时才带选项：成功 / 失败 / 人工等待都不该露出询问芯片，
         * 否则后续节点跑完、卡片已经结束，用户还能点到一次无效的选项。
         */
        WorkflowRunView.Choice shown = "WAITING_REPLY".equalsIgnoreCase(status) ? choice : null;
        return new WorkflowRunView(nameOf(), codeOf(), status, settleAnswer(run, answer), viewNodes, shown,
                finishLimits(run), runId);
    }

    /** 取消时把未收口节点落到与 {@code cancelOpen} 相同的终态；已完成 / 已失败的节点原样保留。 */
    private static WorkflowRunView.Node settleCancelledNode(WorkflowRunView.Node node) {
        String nodeStatus = node.status();
        if ("RUNNING".equals(nodeStatus) || "WAITING_HUMAN".equals(nodeStatus)
                || "WAITING_REPLY".equals(nodeStatus)) {
            return new WorkflowRunView.Node(node.code(), node.name(), node.type(), "CANCELLED", "已中断",
                    node.output(), node.steps(), node.files(), node.reply(), node.attempt(),
                    node.group(), node.loopRound(), node.loopTotal(), node.loopDecision(),
                    node.displayCode());
        }
        if ("PENDING".equals(nodeStatus)) {
            return new WorkflowRunView.Node(node.code(), node.name(), node.type(), "SKIPPED", "已跳过",
                    node.output(), node.steps(), node.files(), node.reply(), node.attempt(),
                    node.group(), node.loopRound(), node.loopTotal(), node.loopDecision(),
                    node.displayCode());
        }
        return node;
    }

    /**
     * 失败收口：run 可能根本没建起来，所以不看 run，直接沿用已收集的节点。
     *
     * @param reason 用户可见失败正文
     * @return 失败态视图
     */
    public WorkflowRunView finishFailed(String reason) {
        return new WorkflowRunView(nameOf(), codeOf(), "FAILED", merge(reason), nodeViews(), null,
                finishLimits(null), runId);
    }

    /**
     * 运行帧的时限：保留「当前节点」那一行，前端据此画该节点的上限与剩余。
     *
     * @return 时限视图；没有截止时为空
     */
    private WorkflowRunView.Limits runningLimits() {
        return limitsOf(limitSnapshot, true);
    }

    /**
     * 收口帧的时限：成功 / 失败 / 取消 / 挂起都**没有正在跑的节点**，
     * 因此节点行一律不带，避免卡片结束了还挂着一行「剩余」。
     *
     * <p>run 上带截止但账本为空（旧 run / 外部收口）时按 run 兜底，
     * 保证刷新回放仍能解释这一段的整段上限。
     *
     * @param run 收口时的 run；可空
     * @return 时限视图；没有截止时为空
     */
    private WorkflowRunView.Limits finishLimits(WorkflowRun run) {
        WorkflowCardLimits snapshot = limitSnapshot;
        if (snapshot == null && run != null && run.getRunDeadline() != null) {
            snapshot = new WorkflowCardLimits(
                    run.getRunLimitMs() == null ? 0L : run.getRunLimitMs(),
                    epochOf(run.getRunDeadline()),
                    epochOf(run.getWaitingSince()),
                    null, null, run.getNodeLimitMs(), null);
        }
        return limitsOf(snapshot, false);
    }

    /**
     * 账本 → 展示契约。
     *
     * @param snapshot 引擎上报的时限快照；可空
     * @param keepNode true 保留当前节点行（仅运行中）
     * @return 时限视图；没有截止时为空
     */
    private static WorkflowRunView.Limits limitsOf(WorkflowCardLimits snapshot, boolean keepNode) {
        if (snapshot == null || snapshot.runDeadlineAt() == null) {
            return null;
        }
        return new WorkflowRunView.Limits(snapshot.runLimitMs(), snapshot.runDeadlineAt(),
                snapshot.waitingAt(),
                keepNode ? snapshot.nodeCode() : null,
                keepNode ? snapshot.nodeName() : null,
                keepNode ? snapshot.nodeLimitMs() : null,
                keepNode ? snapshot.nodeDeadlineAt() : null);
    }

    /** LocalDateTime（服务器时区）→ epoch 毫秒；空值透传。 */
    private static Long epochOf(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 从一行运行态里取可点选项。
     *
     * <p>只认「正在等待回复」的节点：选项是引擎在挂起时写进该节点产出的，
     * 形状与 CHOICE 事件正文相同（{@code {"question","options":[{"id","label"}]}}）。
     * 其它状态（含等待人工）一律不带，避免把已经失效的询问画成按钮。
     *
     * @param row 运行态行；可空
     * @return 选项；没有或结构不合格时为 null
     */
    private WorkflowRunView.Choice choiceOf(WorkflowNodeRun row) {
        if (row == null || row.getOutputJson() == null || row.getOutputJson().isBlank()) {
            return null;
        }
        String status = row.getStatus() == null ? "" : row.getStatus().trim().toUpperCase();
        if (!"WAITING_REPLY".equals(status)) {
            return null;
        }
        try {
            JsonNode node = json.readTree(row.getOutputJson()).path("choice");
            if (!node.isObject()) {
                return null;
            }
            String question = node.path("question").asText("").trim();
            List<WorkflowRunView.Choice.Option> options = new ArrayList<>();
            for (JsonNode item : node.path("options")) {
                options.add(new WorkflowRunView.Choice.Option(
                        item.path("id").asText(""), item.path("label").asText("")));
            }
            WorkflowRunView.Choice parsed = new WorkflowRunView.Choice(question, options);
            return parsed.usable() ? parsed : null;
        } catch (Exception ex) {
            // 产出是展示旁路：解析失败当作「这一步没有选项」，不影响卡片其它部分
            return null;
        }
    }

    /**
     * 收尾正文：只增不减。
     *
     * <p>成功时以「已经流出的片段」为准：END 映射抽出来的那一段通常就是最后一个 Agent 节点的原文，
     * 再用它整段替换，页面上就是「文字清空 — 再整段倒出」——正是要收掉的毛病。
     * 但终答确实是已流出正文的<strong>延长版</strong>时（多节点拼接 / 终稿多出结尾）取更长那份，仍不缩。
     *
     * <p>挂起（等待人工 / 等待回复）与失败 / 取消同一档：把提示接在已流出正文后面，
     * 绝不因为进入等待就把用户已经看到的问句清掉。
     *
     * @param run    run；空按失败
     * @param answer 终答文本
     * @return 至少能展示一句话的正文
     */
    private String settleAnswer(WorkflowRun run, String answer) {
        String streamedText = clippedStreamed();
        String finalText = answer == null ? "" : answer.trim();
        // 一个字都没流过：整段回落（这不是「清空」，是首次落屏）
        if (streamedText.isEmpty()) {
            return finalText;
        }
        // 终答就是已流出正文的延长版：取更长那份，前缀一字不丢
        if (finalText.startsWith(streamedText)) {
            return finalText;
        }
        // 成功：已流出即终稿，绝不拿 END 映射整段替换
        if ("SUCCESS".equalsIgnoreCase(run == null ? null : run.getStatus())) {
            return streamedText;
        }
        // 失败 / 取消 / 等待人工 / 等待回复：原因接在已流出正文后面（空原因不清空正文）
        return merge(answer);
    }

    /** 把一段收尾说明接在已流出正文后面（没有片段时就用它本身）。 */
    private String merge(String tail) {
        String streamedText = clippedStreamed();
        String reason = tail == null ? "" : tail.trim();
        if (streamedText.isEmpty()) {
            return reason;
        }
        if (reason.isEmpty() || streamedText.contains(reason)) {
            return streamedText;
        }
        return streamedText + "\n\n" + reason;
    }

    /** 已流出正文拼成一段；每个节点一段，段间空行，前端按 Markdown 正常渲染。 */
    private String clippedStreamed() {
        String text = String.join("\n\n", streamed).trim();
        if (text.length() <= ANSWER_MAX) {
            return text;
        }
        return text.substring(0, ANSWER_MAX) + "\n\n（输出较长，已截断）";
    }

    /**
     * 节点产出正文时把它的正文追加进「已流出正文」。
     *
     * <p>只收 AGENT 节点：工具节点输出常是 JSON / 日志原文，倒进对话气泡等于把技术细节
     * 当答案；它们仍留在节点时间轴与 END 映射里。结构节点（条件 / 并行 / 人工）本来就没有正文。
     *
     * <p><b>等待回复的节点也要收</b>：本刀的核心就是「问句出现在对话正文里」。
     * 该节点状态是 {@code WAITING_REPLY} 而不是 SUCCESS，若只认 SUCCESS，问句就只留在
     * 自动化执行流里，对话里什么也看不到。
     *
     * <p><b>去重必须在成功追加之后</b>：节点状态会多次迁移，第一次 SUCCESS 时输出还没成形
     * （output_json 为空 / 抽不出文本）是常态。先登记 key 再抽文本，会让这个节点**永久**失去
     * 补正文的机会——正文就只能在收尾整段倒出。现在只有真的抽到文本并追加成功才登记。
     *
     * @param node 节点定义
     * @param row  运行态行
     */
    private void appendNodeText(WorkflowNode node, WorkflowNodeRun row) {
        if (row == null) {
            return;
        }
        String status = row.getStatus() == null ? "" : row.getStatus().trim().toUpperCase();
        // 等待回复的 AGENT 节点已经产出了问句，必须当作「本轮已流出的内容」
        if (!"SUCCESS".equals(status) && !"WAITING_REPLY".equals(status)) {
            return;
        }
        String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
        if (!"AGENT".equals(type)) {
            return;
        }
        String key = (node.getId() != null ? node.getId() : node.getCode()) + "#" + attemptOf(row);
        if (key == null) {
            return;
        }
        // 已经成功追加过：同一节点的后续迁移不再重复（先抽文本，抽不到就不占用去重位）
        if (streamedNodes.contains(key)) {
            return;
        }
        String text = textOfWaitingRow(status, row);
        if (text == null || text.isBlank()) {
            return;
        }
        // 硬顶：已经够长就不再追加，宁可截断，也不让对话框被一次灌满
        if (clippedStreamed().length() >= ANSWER_MAX) {
            return;
        }
        streamed.add(text.trim());
        // 追加成功才封节点：这一笔已经进入正文，后续迁移不得再追加一次
        streamedNodes.add(key);
    }

    /**
     * 节点产出的展示正文。
     *
     * <p>等待回复的节点优先读 {@code question}：那是引擎在挂起时显式落下的问句原文。
     * 节点若配了自定义 {@code outputMapping}，产出里可能没有 answer / text 这类常见键，
     * 只按通用口径抽会抽不到，对话正文就空了。读不到再退回通用口径。
     *
     * @param status 已大写节点状态
     * @param row    运行态行
     * @return 展示正文；没有为 null
     */
    private String textOfWaitingRow(String status, WorkflowNodeRun row) {
        return nodeText(status, row.getOutputJson(), json);
    }

    /**
     * 节点产出 → 可展示正文（实时卡片与刷新回放共用同一口径）。所属域：runtime 的 domain。
     *
     * <p>为什么抽成静态公开方法：落库骨架不再保存节点产出，刷新时由读侧按 {@code runId}
     * 从 {@code workflow_node_run.output_json} 现算。两边必须用同一段取文逻辑，
     * 否则「当时看到的」和「刷新后看到的」会是两段字。
     *
     * <p>等待回复的节点优先读 {@code question}：那是引擎在挂起时显式落下的问句原文。
     * 节点若配了自定义 {@code outputMapping}，产出里可能没有 answer / text 这类常见键，
     * 只按通用口径抽会抽不到，对话正文就空了。读不到再退回通用口径。
     *
     * @param status     节点状态（大小写不敏感）；可空
     * @param outputJson 节点产出 JSON；可空
     * @param json       解析用 ObjectMapper；可空时视为抽不到正文
     * @return 展示正文；没有时为空串
     */
    public static String nodeText(String status, String outputJson, ObjectMapper json) {
        if (outputJson == null || outputJson.isBlank() || json == null) {
            return "";
        }
        String code = status == null ? "" : status.trim().toUpperCase();
        if ("WAITING_REPLY".equals(code)) {
            String question = questionOf(outputJson, json);
            if (!question.isBlank()) {
                return question;
            }
        }
        String answer = WorkflowOutcomeTexts.extractAnswer(outputJson, json);
        return answer == null ? "" : answer;
    }

    /**
     * 取节点产出里的 {@code question} 字段；解析失败返回空串。
     *
     * @param outputJson 节点产出
     * @param json       解析用 ObjectMapper
     * @return 问句原文；没有为空串
     */
    private static String questionOf(String outputJson, ObjectMapper json) {
        if (outputJson == null || outputJson.isBlank() || json == null) {
            return "";
        }
        try {
            return json.readTree(outputJson).path("question").asText("").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    /**
     * 运行中的快照：状态固定 RUNNING，正文是已经流出的片段（可能为空）。
     *
     * <p>挂起态不在这里推：等待的用户可见状态由收口那一份 {@link #finish} 给出，
     * 中途每节点都推 RUNNING 才不会让卡片头在运行中忽蓝忽橙。
     */
    private void emitRunning() {
        if (progress == null) {
            return;
        }
        /*
         * 运行中也要带选项：等用户回复这一拍卡片头是 RUNNING（还没收口），
         * 若这里不带，直播时选项要等收尾那一份才出现——与「问句一起出」不符。
         */
        progress.workflow(new WorkflowRunView(nameOf(), codeOf(), "RUNNING", clippedStreamed(),
                nodeViews(), choice, runningLimits(), runId));
    }

    /**
     * 节点过程的降频推送：思考 / 正文逐字回调太密，整份快照按最小间隔攒一拍。
     */
    private void emitLiveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastLiveEmitAt < LIVE_EMIT_GAP_MS) {
            return;
        }
        lastLiveEmitAt = now;
        emitRunning();
    }

    /**
     * 当前节点快照：已收口的节点带库里产出，运行中的节点带实时思考 / 工具 / 正文。
     *
     * @return 不可变列表，顺序与时间轴一致
     */
    private List<WorkflowRunView.Node> nodeViews() {
        List<WorkflowRunView.Node> list = new ArrayList<>(nodes.size());
        /*
         * 整段都在锁里：withLive 会去读 liveSteps / liveOutput / nodeFiles，
         * 那些表是思考流式增量在别的线程上持续增长的；拆开锁会让排序跑在
         * 正在被追加的集合上，出现并发修改或读到半截。
         */
        synchronized (this) {
            List<Map.Entry<String, WorkflowRunView.Node>> entries = new ArrayList<>(nodes.entrySet());
            // 按开始时刻排，时刻相同按图顺序：循环体第 2 轮才会落在上一轮之后、下游节点之前
            entries.sort((left, right) -> {
                String leftId = left.getKey().substring(0, Math.max(0, left.getKey().lastIndexOf('#')));
                String rightId = right.getKey().substring(0, Math.max(0, right.getKey().lastIndexOf('#')));
                long leftAt = nodeStartMs.getOrDefault(left.getKey(), 0L);
                long rightAt = nodeStartMs.getOrDefault(right.getKey(), 0L);
                if (leftAt != rightAt) {
                    return Long.compare(leftAt, rightAt);
                }
                int leftIndex = nodeIndexById.getOrDefault(leftId, Integer.MAX_VALUE);
                int rightIndex = nodeIndexById.getOrDefault(rightId, Integer.MAX_VALUE);
                if (leftIndex != rightIndex) {
                    return Integer.compare(leftIndex, rightIndex);
                }
                // 同一节点的多轮：按轮次升序，保证第 1 轮在上、第 2 轮在下
                return Integer.compare(left.getValue().attempt(), right.getValue().attempt());
            });
            for (Map.Entry<String, WorkflowRunView.Node> entry : entries) {
                list.add(withLive(entry.getValue()));
            }
        }
        return List.copyOf(list);
    }

    /**
     * 一轮的开始时刻（epoch 毫秒）。没开始过（PENDING）按最大，排在已发生的轮次之后。
     *
     * @param row     运行态；可空
     * @param attempt 轮次（同一时刻时用它兜底排序）
     * @return 排序用的毫秒数
     */
    private static long startMsOf(WorkflowNodeRun row, int attempt) {
        if (row == null) {
            return Long.MAX_VALUE;
        }
        LocalDateTime start = row.getStartTime();
        if (start != null) {
            return start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        // 还没开始：一律排到最后，再由图顺序与轮次排定，未跑的节点不会挤进已发生的轮次中间
        return Long.MAX_VALUE;
    }

    /**
     * 把运行中的节点过程叠到已保存的节点视图上。
     *
     * <p>为什么不在每次增量时重建整张卡：节点状态只在迁移时变，思考 / 工具是高频增量；
     * 这里只复制该节点的少量字段，时间轴其它节点仍是同一份对象。
     *
     * @param node 已保存的节点视图
     * @return 带实时过程 / 正文 / 文件的节点视图
     */
    private WorkflowRunView.Node withLive(WorkflowRunView.Node node) {
        String key = node.code() + "#" + Math.max(1, node.attempt());
        List<WorkflowRunView.Step> steps = liveSteps.get(key);
        List<WorkflowRunView.NodeFile> files = nodeFiles.get(key);
        StringBuilder buffer = liveOutput.get(key);
        String output = node.output();
        // 库里产出是收口后的权威正文；还没有产出（运行中 / 工具轮）才回落实时增量
        if (output.isBlank() && buffer != null) {
            output = buffer.toString();
        }
        if ((steps == null || steps.isEmpty()) && (files == null || files.isEmpty()) && output.equals(node.output())) {
            return node;
        }
        return new WorkflowRunView.Node(node.code(), node.name(), node.type(), node.status(), node.note(),
                output,
                steps == null ? List.of() : List.copyOf(steps),
                files == null ? List.of() : List.copyOf(files),
                node.reply(), node.attempt(), node.group(), node.loopRound(), node.loopTotal(),
                node.loopDecision(), node.displayCode());
    }

    /** 取（或建）一个节点某一轮的过程步列表；调用方必须持有本对象锁。 */
    private List<WorkflowRunView.Step> stepList(String key) {
        return liveSteps.computeIfAbsent(key, ignored -> new ArrayList<>());
    }

    /**
     * 思考 / 工具回调只有节点编码。挂到当前正在跑的那一轮；没有运行中的就挂到该编码最新一轮。
     *
     * @param nodeCode 节点编码
     * @return {@code 编码#轮次}
     */
    private String liveKey(String nodeCode) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return "#1";
        }
        String code = nodeCode.trim();
        String fallback = code + "#1";
        int best = 0;
        for (WorkflowRunView.Node node : nodes.values()) {
            if (!code.equals(node.code())) {
                continue;
            }
            int attempt = Math.max(1, node.attempt());
            if ("RUNNING".equals(node.status())) {
                return code + "#" + attempt;
            }
            if (attempt >= best) {
                best = attempt;
                fallback = code + "#" + attempt;
            }
        }
        return fallback;
    }

    /**
     * @param row 运行态；空或未填按第 1 轮
     * @return 至少为 1 的轮次
     */
    private static int attemptOf(WorkflowNodeRun row) {
        if (row == null || row.getAttempt() == null || row.getAttempt() < 1) {
            return 1;
        }
        return row.getAttempt();
    }

    /** 展示名：优先配置名，其次编码；都没有时留空由前端兜底。 */
    private String nameOf() {
        return name == null || name.isBlank() ? codeOf() : name.trim();
    }

    private String codeOf() {
        return code == null ? "" : code.trim();
    }

    /** START / END 是边界标记，不是执行步骤。 */
    private static boolean isBoundary(WorkflowNode node) {
        String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
        return "START".equals(type) || "END".equals(type);
    }

    /**
     * 一行运行态 → 卡片步骤。
     *
     * <p>循环相关的三个展示字段（轮次 / 总次数 / 最近判定）读循环节点自己的产出 JSON，
     * 与调度写进去的是同一份事实；非循环节点一律留空，前端按「没有循环」处理。
     *
     * @param node 节点定义
     * @param row  运行态；缺行按「未开始」
     * @return 步骤视图
     */
    private WorkflowRunView.Node toView(WorkflowNode node, WorkflowNodeRun row) {
        // 子图节点在 registerScope 时登记过外层框；直播后续覆盖同一视图键仍要留在框里
        String group = node == null || node.getId() == null ? ""
                : scopeGroupByNodeId.getOrDefault(node.getId(), "");
        return toView(node, row, group);
    }

    /**
     * 一行运行态 → 卡片步骤，并指定该节点不在循环体里时的默认框（子图节点编码）。
     *
     * @param node         节点定义
     * @param row          运行态；缺行按「未开始」
     * @param defaultGroup 外层子图节点编码；根图为空串
     * @return 步骤视图
     */
    private WorkflowRunView.Node toView(WorkflowNode node, WorkflowNodeRun row, String defaultGroup) {
        String code = node.getCode() == null ? "" : node.getCode().trim();
        String name = node.getName() == null || node.getName().isBlank() ? code : node.getName().trim();
        String type = node.getType() == null ? "" : node.getType().trim().toUpperCase();
        String status = row == null || row.getStatus() == null ? "PENDING" : row.getStatus().trim().toUpperCase();
        String error = row == null || row.getErrorSummary() == null ? "" : row.getErrorSummary().trim();
        String output = outputOf(code, type, status, row);
        String displayCode = node.getId() == null ? "" : displayCodeByNodeId.getOrDefault(node.getId(), "");
        WorkflowRunView.Node view = new WorkflowRunView.Node(code, name, type, status,
                noteOf(status, error), output, List.of(), List.of(), replyOf(row),
                row == null ? 1 : attemptOf(row), "", 0, 0, "", displayCode);
        return withLoop(view, node, row, defaultGroup);
    }

    /**
     * 给节点视图补上循环展示字段：所属循环（体节点）或自己的轮次（循环节点）。
     *
     * <p>「体节点」只带 {@code group}（哪个循环的框），轮次由它自己的 {@code attempt} 表达；
     * 「循环节点」带 {@code group}（嵌套时归外层）、{@code loopRound} / {@code loopTotal} /
     * {@code loopDecision}，前端据此写「循环 · 第 1/3 轮 · 进行中」并决定框是否收口。
     *
     * @param view         已建好的节点视图
     * @param node         节点定义
     * @param row          运行态；可空
     * @param defaultGroup 不在循环体里时归到哪只子图框；根图为空串
     * @return 补好循环字段的视图；不在循环里的节点原样返回
     */
    private WorkflowRunView.Node withLoop(WorkflowRunView.Node view, WorkflowNode node,
                                          WorkflowNodeRun row, String defaultGroup) {
        String nodeId = node.getId();
        String group = nodeId == null ? "" : loopGroupByNode.getOrDefault(nodeId, "");
        boolean isLoop = "LOOP".equals(view.type());
        if (group.isEmpty() && defaultGroup != null && !defaultGroup.isBlank()) {
            // 子图里的普通节点 / 内层循环 / 内层子图都先归到本层子图框；循环体节点自己的 group 优先
            group = defaultGroup;
        }
        if (!isLoop && group.isEmpty()) {
            return view;
        }
        int round = 0;
        int total = 0;
        String decision = "";
        if (isLoop) {
            JsonNode output = parseOutput(row);
            if (output != null) {
                round = output.path("iteration").asInt(0);
                total = output.path("maxIterations").asInt(0);
                decision = output.path("decision").asText("");
            }
        }
        return new WorkflowRunView.Node(view.code(), view.name(), view.type(), view.status(),
                view.note(), view.output(), view.steps(), view.files(), view.reply(),
                view.attempt(), group, round, total, decision, view.displayCode());
    }

    /**
     * 解析运行态里的产出 JSON。
     *
     * <p>展示旁路：解析失败当作「这一拍没有产出」，不让脏数据把整张卡打挂。
     *
     * @param row 运行态；可空
     * @return 产出对象；没有 / 不是对象 / 解析失败时为 null
     */
    private JsonNode parseOutput(WorkflowNodeRun row) {
        if (row == null || row.getOutputJson() == null || row.getOutputJson().isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = json.readTree(row.getOutputJson());
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 用户对这一节点询问的回复。
     *
     * <p>只挂在「产出里带 choice」的询问节点上：等待时选项还在，续跑把节点收成 SUCCESS 后
     * 选项清掉，但产出里的 {@code choice} 结构仍在，据此认回同一步。其它节点一律为空，
     * 首次发起（构造时 reply 为空）也不会多出这一行。
     *
     * @param row 运行态行；可空
     * @return 用户回复；不是询问节点或没有回复时为空串
     */
    private String replyOf(WorkflowNodeRun row) {
        if (reply.isBlank() || row == null || row.getOutputJson() == null || row.getOutputJson().isBlank()) {
            return "";
        }
        try {
            JsonNode node = json.readTree(row.getOutputJson());
            if (node == null || !node.path("choice").isObject()) {
                return "";
            }
            return reply;
        } catch (Exception ex) {
            // 产出是展示旁路：解析失败按「这一步没有询问」处理，不影响卡片其它部分
            return "";
        }
    }

    /**
     * 节点自己的正文：优先取库里产出（收口后的权威文本），运行中回落实时增量。
     *
     * <p>只给 AGENT 节点抽文本：工具 / 条件节点产出常是机器 JSON，
     * 倒进节点正文等于把技术细节当答案。等待回复的节点仍优先读 question，
     * 与卡级正文同一口径，避免自定义 outputMapping 把问句抽丢。
     *
     * @param code   节点编码
     * @param type   节点类型（已大写）
     * @param status 节点状态（已大写）
     * @param row    运行态行；可空
     * @return 展示正文；没有为空串
     */
    private String outputOf(String code, String type, String status, WorkflowNodeRun row) {
        String output = "";
        if ("AGENT".equals(type) && row != null
                && ("SUCCESS".equals(status) || "WAITING_REPLY".equals(status))) {
            output = textOfWaitingRow(status, row);
        }
        if (output == null || output.isBlank()) {
            synchronized (this) {
                StringBuilder buffer = liveOutput.get(code + "#" + (row == null ? 1 : attemptOf(row)));
                output = buffer == null ? "" : buffer.toString();
            }
        }
        // 单节点正文也设硬顶：多节点代码生成时，一张卡不该因为每个子 Agent 的全文而无界膨胀
        return output == null ? "" : clip(output, OUTPUT_MAX);
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
            case "WAITING_REPLY" -> "等待你的回复";
            case "CANCELLED" -> "已取消";
            default -> "等待前置节点";
        };
    }

    private static String clip(String text, int max) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
