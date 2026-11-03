package com.agenthub.workflow.application;

import cn.dev33.satoken.stp.StpUtil;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.workflow.api.dto.WorkflowHumanTaskItemResp;
import com.agenthub.workflow.domain.enums.HumanTaskStatus;
import com.agenthub.workflow.domain.enums.HumanTimeoutPolicy;
import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowHumanTask;
import com.agenthub.workflow.domain.model.WorkflowNodeRun;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowHumanTaskRepository;
import com.agenthub.workflow.domain.repository.WorkflowNodeRunRepository;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowAssigneeRule;
import com.agenthub.workflow.domain.service.WorkflowStateMachine;
import com.agenthub.workflow.domain.service.WorkflowTenantProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人工待办处理用例。所属域：workflow 的 application。
 * <p>职责：approve / reject 人工卡点，批准后把 run 从 PAUSED_HUMAN 拉回 RUNNING 并续跑；
 * 以及「懒超时」：GET run 详情或人工接口调用时把到期待办按策略自动结算。
 * <p>为什么续跑同步做：试跑是控制台单用户场景，同步能立即把节点状态回给画布；
 * 生产异步执行器是后续刀的事。
 * <p>权限：接口层要求 {@code workflow:human:act}；这里再按 assigneeRule（USER/ROLE）校验处理人。
 */
@Service
public class WorkflowHumanTaskAppService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHumanTaskAppService.class);

    private final WorkflowHumanTaskRepository humanTasks;
    private final WorkflowNodeRunRepository nodeRuns;
    private final WorkflowRunRepository runs;
    private final WorkflowEngine engine;
    private final WorkflowGraphCodec codec;
    private final WorkflowRunAssembler assembler;
    private final WorkflowTenantProvider tenantProvider;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final WorkflowDefRepository defs;
    private final ObjectMapper json;

    /**
     * @param humanTasks      待办仓储
     * @param nodeRuns        节点运行态
     * @param runs            run 仓储
     * @param engine          调度引擎（批准后续跑）
     * @param codec           图解析（取 HUMAN 节点出边）
     * @param assembler       详情组装
     * @param tenantProvider  当前租户
     * @param operatorContext 当前操作人
     * @param operLogWriter   操作日志
     * @param defs            工作流定义（待办列表展示工作流名）
     * @param json            全站 ObjectMapper
     */
    public WorkflowHumanTaskAppService(WorkflowHumanTaskRepository humanTasks,
                                       WorkflowNodeRunRepository nodeRuns,
                                       WorkflowRunRepository runs,
                                       WorkflowEngine engine,
                                       WorkflowGraphCodec codec,
                                       WorkflowRunAssembler assembler,
                                       WorkflowTenantProvider tenantProvider,
                                       OperatorContext operatorContext,
                                       OperLogWriter operLogWriter,
                                       WorkflowDefRepository defs,
                                       ObjectMapper json) {
        this.humanTasks = humanTasks;
        this.nodeRuns = nodeRuns;
        this.runs = runs;
        this.engine = engine;
        this.codec = codec;
        this.assembler = assembler;
        this.tenantProvider = tenantProvider;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.defs = defs;
        this.json = json;
    }

    /**
     * 通过人工待办并续跑。
     *
     * @param taskId 待办 id
     * @param note   审批备注，可空
     * @return 续跑后的 run 详情
     * @throws BizException 待办不存在 / 已处理 / 无处理权限 / run 不在等待人工
     */
    public WorkflowRunDetail approve(Long taskId, String note) {
        long operatorId = operatorContext.requireUserId();
        return decide(taskId, true, note, null, operatorId);
    }

    /**
     * 驳回人工待办：默认整 run FAILED；HUMAN 节点若画了 ERROR 边，则走错误边继续。
     *
     * @param taskId 待办 id
     * @param note   驳回原因，可空
     * @return 结算后的 run 详情
     */
    public WorkflowRunDetail reject(Long taskId, String note) {
        long operatorId = operatorContext.requireUserId();
        return decide(taskId, false, note, null, operatorId);
    }

    /**
     * 懒超时结算：只处理当前 run 已到期的 OPEN 待办。
     * <p>ESCALATE 本刀无升级通道：记 WARN 日志后按拒绝处理（交付说明已列明）。
     *
     * @param run run（调用方已做租户校验）
     */
    public void resolveExpired(WorkflowRun run) {
        if (WorkflowRunStatus.parse(run.getStatus()) != WorkflowRunStatus.PAUSED_HUMAN) {
            return;
        }
        List<WorkflowHumanTask> expired = humanTasks.listExpiredOpen(run.getId(), LocalDateTime.now());
        for (WorkflowHumanTask task : expired) {
            HumanTimeoutPolicy policy = HumanTimeoutPolicy.parse(task.getTimeoutPolicy());
            long operator = task.getCreatedBy() == null ? 0L : task.getCreatedBy();
            try {
                if (policy == HumanTimeoutPolicy.AUTO_APPROVE) {
                    decide(task.getId(), true, "超时自动通过", "TIMEOUT_AUTO_APPROVE", operator);
                } else {
                    // AUTO_REJECT 与 ESCALATE 同口径：本刀没有升级通道，先按拒绝收口
                    if (policy == HumanTimeoutPolicy.ESCALATE) {
                        log.warn("人工待办超时升级策略未接线，按拒绝处理 taskId={} runId={}",
                                task.getId(), run.getId());
                    }
                    decide(task.getId(), false, "超时自动拒绝", "TIMEOUT_AUTO_REJECT", operator);
                }
            } catch (RuntimeException ex) {
                // 自动结算失败不能让详情接口 500：记录后由用户手动处理，避免卡住页面
                log.warn("人工待办超时结算失败 taskId={} type={}",
                        task.getId(), ex.getClass().getSimpleName(), ex);
            }
        }
    }

    /**
     * 待办中心列表（WF-P0 刀3）。
     *
     * <p>口径：
     * <ul>
     *   <li>{@code mine=true}：只返回<b>当前用户此刻能处理</b>的 OPEN 待办
     *       （规则命中 + run 仍 PAUSED_HUMAN）；此时忽略 status，恒按 OPEN 查；</li>
     *   <li>{@code mine=false}：按 status 过滤（空=不过滤），OPEN 排在最前；</li>
     *   <li>列表前先做一次「懒超时」结算，避免已到期的任务还显示成可操作 OPEN。</li>
     * </ul>
     *
     * @param mine   true=只看待我处理
     * @param status OPEN / DONE / EXPIRED；空表示全部
     * @return 待办行（含工作流名与中文规则描述）
     */
    public List<WorkflowHumanTaskItemResp> list(boolean mine, String status) {
        long tenantId = tenantProvider.currentTenantId();
        String effectiveStatus = mine ? HumanTaskStatus.OPEN.name() : normalizeStatus(status);
        List<WorkflowHumanTask> candidates = humanTasks.listByTenant(tenantId, effectiveStatus, 100);
        // 懒超时：到期 OPEN 先按节点策略结算；失败只记日志，不阻断列表
        boolean settled = settleExpired(candidates);
        if (settled) {
            candidates = humanTasks.listByTenant(tenantId, effectiveStatus, 100);
        }

        long operatorId = operatorContext.requireUserId();
        List<String> roles;
        try {
            roles = StpUtil.getRoleList();
        } catch (RuntimeException ex) {
            roles = List.of();
        }

        Map<Long, WorkflowRun> runCache = new HashMap<>();
        Map<Long, WorkflowDef> defCache = new HashMap<>();
        List<WorkflowHumanTaskItemResp> items = new ArrayList<>();
        for (WorkflowHumanTask task : candidates) {
            WorkflowRun run = cachedRun(runCache, task.getRunId());
            boolean runWaiting = run != null
                    && WorkflowRunStatus.parse(run.getStatus()) == WorkflowRunStatus.PAUSED_HUMAN;
            boolean assigned = WorkflowAssigneeRule.matches(task.getAssigneeRule(), operatorId, roles);
            boolean actionable = HumanTaskStatus.parse(task.getStatus()) == HumanTaskStatus.OPEN
                    && runWaiting && assigned;
            if (mine && !actionable) {
                continue;
            }
            items.add(toItem(task, run, cachedDef(defCache, run), actionable));
        }
        // OPEN 优先，其次按待办 id 倒序（新的在前），让待办中心首屏就是待处理
        items.sort((left, right) -> {
            boolean leftOpen = HumanTaskStatus.OPEN.name().equals(left.getStatus());
            boolean rightOpen = HumanTaskStatus.OPEN.name().equals(right.getStatus());
            if (leftOpen != rightOpen) {
                return leftOpen ? -1 : 1;
            }
            return Long.compare(right.getId(), left.getId());
        });
        return items;
    }

    /**
     * 对列表里的到期 OPEN 待办做一次懒结算。
     *
     * @param tasks 本次查出的待办
     * @return true 表示至少尝试结算过一条（调用方需要重查）
     */
    private boolean settleExpired(List<WorkflowHumanTask> tasks) {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> runIds = new LinkedHashSet<>();
        for (WorkflowHumanTask task : tasks) {
            if (HumanTaskStatus.parse(task.getStatus()) != HumanTaskStatus.OPEN) {
                continue;
            }
            if (task.getExpireTime() != null && !task.getExpireTime().isAfter(now)) {
                runIds.add(task.getRunId());
            }
        }
        if (runIds.isEmpty()) {
            return false;
        }
        for (Long runId : runIds) {
            try {
                runs.findById(runId).ifPresent(this::resolveExpired);
            } catch (RuntimeException ex) {
                log.warn("待办列表懒超时结算失败 runId={} type={}",
                        runId, ex.getClass().getSimpleName(), ex);
            }
        }
        return true;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        HumanTaskStatus parsed = HumanTaskStatus.parse(status);
        if (parsed == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "待办状态只能是 OPEN / DONE / EXPIRED，当前为 " + status);
        }
        return parsed.name();
    }

    private WorkflowRun cachedRun(Map<Long, WorkflowRun> cache, Long runId) {
        if (runId == null) {
            return null;
        }
        if (cache.containsKey(runId)) {
            return cache.get(runId);
        }
        WorkflowRun run = runs.findById(runId).orElse(null);
        cache.put(runId, run);
        return run;
    }

    private WorkflowDef cachedDef(Map<Long, WorkflowDef> cache, WorkflowRun run) {
        if (run == null || run.getWorkflowId() == null) {
            return null;
        }
        Long workflowId = run.getWorkflowId();
        if (cache.containsKey(workflowId)) {
            return cache.get(workflowId);
        }
        WorkflowDef def = defs.findById(workflowId).orElse(null);
        cache.put(workflowId, def);
        return def;
    }

    private WorkflowHumanTaskItemResp toItem(WorkflowHumanTask task, WorkflowRun run,
                                             WorkflowDef def, boolean actionable) {
        WorkflowHumanTaskItemResp resp = new WorkflowHumanTaskItemResp();
        resp.setId(task.getId());
        resp.setRunId(task.getRunId());
        resp.setWorkflowId(run == null ? null : run.getWorkflowId());
        resp.setWorkflowCode(def == null ? "" : def.getCode());
        resp.setWorkflowName(def == null ? "" : def.getName());
        resp.setReleaseVersion(run == null ? null : run.getReleaseVersion());
        resp.setRunStatus(run == null ? null : run.getStatus());
        resp.setNodeCode(task.getNodeCode());
        resp.setStatus(task.getStatus());
        resp.setAssigneeRule(task.getAssigneeRule());
        resp.setAssigneeText(WorkflowAssigneeRule.describe(task.getAssigneeRule()));
        resp.setTimeoutPolicy(task.getTimeoutPolicy());
        resp.setExpireTime(task.getExpireTime());
        resp.setCreateTime(task.getCreateTime());
        resp.setActionable(actionable);
        return resp;
    }

    /**
     * @param runId run 主键
     * @return 该 run 的 OPEN 待办（取消时用于批量收口）
     */
    public List<WorkflowHumanTask> openTasks(Long runId) {
        return humanTasks.listOpenByRunId(runId);
    }

    /**
     * 统一决定入口。
     *
     * @param taskId         待办 id
     * @param approved       true 通过 / false 驳回
     * @param note           备注
     * @param decidedByPolicy 自动策略标识；人工操作传 null
     * @param operatorId     操作人（自动策略时为 run 创建人）
     * @return run 详情
     */
    private WorkflowRunDetail decide(Long taskId, boolean approved, String note,
                                     String decidedByPolicy, long operatorId) {
        long tenantId = tenantProvider.currentTenantId();
        WorkflowHumanTask task = humanTasks.findById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "人工待办不存在"));
        if (task.getTenantId() == null || task.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND, "人工待办不存在");
        }
        if (HumanTaskStatus.parse(task.getStatus()) != HumanTaskStatus.OPEN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该人工待办已处理，请刷新后查看最新状态");
        }
        if (decidedByPolicy == null) {
            requireAssignee(task, operatorId);
        }
        WorkflowRun run = runs.findById(task.getRunId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "运行实例不存在"));
        if (WorkflowRunStatus.parse(run.getStatus()) != WorkflowRunStatus.PAUSED_HUMAN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "运行实例不在等待人工状态，无法处理待办");
        }
        WorkflowNodeRun nodeRun = nodeRuns.findById(task.getNodeRunId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "人工节点运行态不存在"));
        if (WorkflowNodeRunStatus.parse(nodeRun.getStatus()) != WorkflowNodeRunStatus.WAITING_HUMAN) {
            throw new BizException(ErrorCode.BAD_REQUEST, "人工节点不在等待状态，待办已失效");
        }

        String resultJson = resultJson(approved, note, decidedByPolicy);
        String taskStatus = decidedByPolicy == null
                ? HumanTaskStatus.DONE.name() : HumanTaskStatus.EXPIRED.name();
        boolean changed = humanTasks.decide(taskId, taskStatus, resultJson, operatorId, operatorId);
        if (!changed) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该人工待办已处理，请刷新后查看最新状态");
        }

        if (approved) {
            approveNode(run, nodeRun, note, operatorId);
            operLogWriter.write("workflow", "human_approve", "workflow_human_task",
                    String.valueOf(taskId), "人工通过 run=" + run.getId());
        } else {
            rejectNode(run, nodeRun, note, operatorId);
            operLogWriter.write("workflow", "human_reject", "workflow_human_task",
                    String.valueOf(taskId), "人工驳回 run=" + run.getId());
        }
        WorkflowRun fresh = runs.findById(run.getId()).orElse(run);
        return assembler.detail(fresh);
    }

    /**
     * 通过：节点 SUCCESS，按 NORMAL/COND/DEFAULT 出边继续；run 回 RUNNING 后同步续跑。
     */
    private void approveNode(WorkflowRun run, WorkflowNodeRun nodeRun, String note, long operatorId) {
        List<WorkflowEdge> selected = normalEdges(run, nodeRun);
        nodeRun.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.WAITING_HUMAN, WorkflowNodeRunStatus.SUCCESS).name());
        nodeRun.setEndTime(LocalDateTime.now());
        nodeRun.setSelectedEdges(joinIds(selected));
        ObjectNode output = json.createObjectNode();
        output.put("approved", true);
        output.put("note", note == null ? "" : note);
        nodeRun.setOutputJson(output.toString());
        nodeRuns.update(nodeRun);

        resume(run, operatorId);
        engine.execute(run, operatorId);
    }

    /**
     * 驳回：HUMAN 有 ERROR 出边则走错误边续跑，否则整 run FAILED。
     */
    private void rejectNode(WorkflowRun run, WorkflowNodeRun nodeRun, String note, long operatorId) {
        List<WorkflowEdge> errorEdges = errorEdges(run, nodeRun);
        nodeRun.setStatus(WorkflowStateMachine.requireNode(
                WorkflowNodeRunStatus.WAITING_HUMAN, WorkflowNodeRunStatus.FAILED).name());
        nodeRun.setEndTime(LocalDateTime.now());
        nodeRun.setErrorSummary(truncate(note == null || note.isBlank() ? "人工驳回" : "人工驳回：" + note));
        nodeRun.setSelectedEdges(joinIds(errorEdges));
        nodeRuns.update(nodeRun);

        if (!errorEdges.isEmpty()) {
            // 与普通节点失败同语义：错误边接管，run 继续
            resume(run, operatorId);
            engine.execute(run, operatorId);
            return;
        }
        WorkflowStateMachine.requireRun(WorkflowRunStatus.PAUSED_HUMAN, WorkflowRunStatus.FAILED);
        run.setStatus(WorkflowRunStatus.FAILED.name());
        run.setErrorSummary(nodeRun.getErrorSummary());
        run.setEndTime(LocalDateTime.now());
        runs.updateStatus(run.getId(), WorkflowRunStatus.FAILED.name(), run.getErrorSummary(),
                null, run.getEndTime(), operatorId);
        // 其余未走分支统一收口，画布上不再出现「一直转圈」
        nodeRuns.cancelOpen(run.getId());
    }

    private void resume(WorkflowRun run, long operatorId) {
        WorkflowStateMachine.requireRun(WorkflowRunStatus.PAUSED_HUMAN, WorkflowRunStatus.RUNNING);
        run.setStatus(WorkflowRunStatus.RUNNING.name());
        runs.updateStatus(run.getId(), WorkflowRunStatus.RUNNING.name(), null, null, null, operatorId);
    }

    /**
     * 取 HUMAN 节点的 NORMAL/COND/DEFAULT 出边；图损坏时按「无出边」处理（后续由引擎判停滞）。
     */
    private List<WorkflowEdge> normalEdges(WorkflowRun run, WorkflowNodeRun nodeRun) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (WorkflowEdge edge : outgoing(run, nodeRun)) {
            if (WorkflowEdgeType.parseOrDefault(edge.getType()) != WorkflowEdgeType.ERROR) {
                edges.add(edge);
            }
        }
        return edges;
    }

    private List<WorkflowEdge> errorEdges(WorkflowRun run, WorkflowNodeRun nodeRun) {
        return outgoing(run, nodeRun).stream()
                .filter(edge -> WorkflowEdgeType.parseOrDefault(edge.getType()) == WorkflowEdgeType.ERROR)
                .toList();
    }

    private List<WorkflowEdge> outgoing(WorkflowRun run, WorkflowNodeRun nodeRun) {
        try {
            WorkflowGraph graph = codec.parse(run.getGraphJson());
            WorkflowRuntimeGraph runtime = WorkflowRuntimeGraph.of(graph);
            return runtime.outgoing(nodeRun.getNodeId());
        } catch (RuntimeException ex) {
            log.warn("读取 HUMAN 节点出边失败 runId={} nodeId={} type={}",
                    run.getId(), nodeRun.getNodeId(), ex.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 处理人规则：空=有按钮权限即可；USER:1,2 指定用户；ROLE:code 角色命中其一。
     *
     * <p>W4：不支持「管理员无条件绕过」——避免把 human:act 变成越权通道；
     * 运维要换人请改图重新发布（assignee 随发布快照生效）。
     *
     * @param task       待办
     * @param operatorId 当前用户
     * @throws BizException FORBIDDEN 表示不是处理人；BAD_REQUEST 表示规则非法
     */
    private void requireAssignee(WorkflowHumanTask task, long operatorId) {
        String rule = WorkflowAssigneeRule.normalize(task.getAssigneeRule());
        WorkflowAssigneeRule.Parsed parsed = WorkflowAssigneeRule.parse(rule);
        if (parsed.problem() != null) {
            // 规则非法属于配置损坏：不放行，也不冒充「无权限」，给出可定位的中文原因
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "该人工待办的处理人规则非法：" + parsed.problem());
        }
        if (parsed.kind() == WorkflowAssigneeRule.Kind.EMPTY) {
            return;
        }
        List<String> roles;
        try {
            roles = StpUtil.getRoleList();
        } catch (RuntimeException ex) {
            // 无登录上下文（自动策略 / 单测）或 Sa-Token 未初始化：按无角色处理
            roles = List.of();
        }
        if (WorkflowAssigneeRule.matches(rule, operatorId, roles)) {
            return;
        }
        String need = parsed.kind() == WorkflowAssigneeRule.Kind.ROLE
                ? "（需要角色 " + parsed.roleCode() + "）"
                : "（仅限名单内用户）";
        throw new BizException(ErrorCode.FORBIDDEN, "你不是该节点处理人" + need);
    }

    private String resultJson(boolean approved, String note, String decidedByPolicy) {
        ObjectNode result = json.createObjectNode();
        result.put("approved", approved);
        result.put("note", note == null ? "" : note);
        if (decidedByPolicy != null) {
            result.put("decidedByPolicy", decidedByPolicy);
        }
        return result.toString();
    }

    private String joinIds(List<WorkflowEdge> edges) {
        StringBuilder builder = new StringBuilder();
        for (WorkflowEdge edge : edges) {
            if (edge.getId() == null || edge.getId().isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(edge.getId());
        }
        return builder.toString();
    }

    private String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }
}
