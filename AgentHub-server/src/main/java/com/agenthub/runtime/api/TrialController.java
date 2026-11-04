package com.agenthub.runtime.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.runtime.api.dto.AgentOptionResp;
import com.agenthub.runtime.api.dto.ChildPlanPendingResp;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.MessageReq;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.dto.PlanResp;
import com.agenthub.runtime.api.dto.SessionCreateReq;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.api.dto.SessionTitleReq;
import com.agenthub.runtime.application.TrialRunAppService;
import com.agenthub.runtime.application.TrialSessionAppService;
import com.agenthub.runtime.api.sse.ServletSseFrameSink;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 控制台试跑接口。所属域：runtime。身份是平台登录用户，不接 apiKey。
 * 装配和 AgentScope 调用不在这里。
 */
@RestController
@RequestMapping("/api/runtime/trial")
public class TrialController {

    private final TrialSessionAppService sessions;
    private final TrialRunAppService runs;
    private final ObjectMapper json;

    /**
     * @param sessions 会话查询、改名、清空和删除
     * @param runs     发送一轮，含 SSE
     * @param json     SSE data 收成一行
     */
    public TrialController(TrialSessionAppService sessions, TrialRunAppService runs, ObjectMapper json) {
        this.sessions = sessions;
        this.runs = runs;
        this.json = json;
    }

    /**
     * 试跑下拉只需要试跑权限，不另要 agent:list。
     *
     * @return 启用中的 Agent
     */
    @SaCheckPermission(value = {RuntimePermissions.LIST, RuntimePermissions.RUN}, mode = SaMode.OR)
    @GetMapping("/agents")
    public Result<List<AgentOptionResp>> agents() {
        return Result.ok(sessions.enabledAgents());
    }

    /**
     * 单个 Agent 的多模态能力。抽屉按 agentId 打开时用它判断上传按钮是否可用，
     * 不必为了一个布尔值把整个下拉列表拉一遍。
     *
     * @param agentId Agent
     * @return 能力视图
     */
    @SaCheckPermission(value = {RuntimePermissions.LIST, RuntimePermissions.RUN}, mode = SaMode.OR)
    @GetMapping("/agents/{agentId}/capability")
    public Result<AgentOptionResp> capability(@PathVariable Long agentId) {
        return Result.ok(sessions.visionCapability(agentId));
    }

    /**
     * @param req 必填 agentId。只返回当前登录用户的会话
     * @return 会话页
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions")
    public Result<PageResult<SessionResp>> page(@Valid SessionPageReq req) {
        return Result.ok(sessions.page(req));
    }

    /**
     * @param id 会话主键
     * @return 元数据
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions/{id}")
    public Result<SessionResp> get(@PathVariable Long id) {
        return Result.ok(sessions.get(id));
    }

    /**
     * @param id 会话主键
     * @return 事件时间线
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions/{id}/events")
    public Result<List<EventResp>> events(@PathVariable Long id) {
        return Result.ok(sessions.events(id));
    }

    /**
     * 进行中一轮的实时快照。
     * 刷新页面后前端失去 SSE，events 又只有整轮结束才落库，本接口补上「边跑边看」的最后一块。
     * 只在当前进程有效；没有在跑的一轮时返回空列表，前端据此回退到 events。
     *
     * @param id 会话主键
     * @return seq 升序的实时事件
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions/{id}/live-events")
    public Result<List<EventResp>> liveEvents(@PathVariable Long id) {
        return Result.ok(sessions.liveEvents(id));
    }

    /**
     * @param req Agent
     * @return 新会话
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions")
    public Result<SessionResp> create(@Valid @RequestBody SessionCreateReq req) {
        return Result.ok(sessions.create(req.getAgentId()));
    }

    /**
     * 同步执行。失败时 HTTP 仍是成功包，data.status=FAILED。
     *
     * @param id  会话主键
     * @param req 用户输入
     * @return 助手预览和本轮事件
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/messages")
    public Result<MessageResp> send(@PathVariable Long id, @Valid @RequestBody MessageReq req) {
        return Result.ok(runs.send(id, req.getContent().trim(), req.getImages()));
    }

    /**
     * 边跑边推 phase / reasoning / tool / assistant / done。仍在当前请求线程执行，登录态不用另传。
     *
     * @param id       会话主键
     * @param req      用户输入
     * @param response 直接写 SSE，避免等整轮结束才返回
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping(value = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable Long id, @Valid @RequestBody MessageReq req, HttpServletResponse response)
            throws IOException {
        // 统一设头：漏掉 no-transform 会让中间层缓冲，前端收不到增量
        ServletSseFrameSink.prepare(response);
        TrialChunkWriter writer = new TrialChunkWriter(response, json);
        writer.bindSession(id);
        try {
            runs.stream(id, req.getContent().trim(), req.getImages(), writer, writer);
        } finally {
            // 兜底：业务侧任何路径没写结束帧（异常、线程中断、上游直接断），这里补一帧再关连接。
            // 内部有 CAS，重复调用不会产生第二帧。
            writer.ensureFinished();
        }
    }

    /**
     * @param id  会话主键
     * @param req 新标题
     * @return 更新后的会话
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/title")
    public Result<SessionResp> rename(@PathVariable Long id, @Valid @RequestBody SessionTitleReq req) {
        return Result.ok(sessions.rename(id, req.getTitle().trim()));
    }

    /**
     * 清空该会话已落库事件，保留会话和标题。
     *
     * @param id 会话主键
     * @return 重置后的会话
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/clear")
    public Result<SessionResp> clear(@PathVariable Long id) {
        return Result.ok(sessions.clear(id));
    }

    /**
     * 逻辑删除。列表只查 deleted=0。
     *
     * @param id 会话主键
     * @return 空成功
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @DeleteMapping("/sessions/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        sessions.remove(id);
        return Result.ok(null);
    }

    /**
     * 计划执行模式：批准待执行的计划，续跑剩余步骤并汇总终答。
     * 与发送同一套落库语义，返回本轮新事件；没有待批准计划时返回业务错误。
     *
     * <p>带 {@code routeKey} 时走「编排根下的子计划批准」：不续跑根侧步骤，
     * 只唤醒等待中的 P&E 子线程，根 SSE 连接会继续推送后续增量。
     *
     * @param id       会话主键
     * @param routeKey 子路由键，可空；空＝单 Agent 计划批准
     * @return 本轮事件（单 Agent）或轻量确认（子计划）
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/plan/approve")
    public Result<MessageResp> approvePlan(@PathVariable Long id,
                                          @RequestParam(required = false) String routeKey) {
        if (routeKey != null && !routeKey.isBlank()) {
            return Result.ok(runs.approveChildPlan(id, routeKey.trim()));
        }
        return Result.ok(runs.approvePlan(id));
    }

    /**
     * 计划执行模式：取消待执行的计划。不执行任何步骤，只落一条「计划已取消」。
     *
     * <p>带 {@code routeKey} 时拒绝的是编排根下的某个子计划：该子失败占位，其它子继续。
     *
     * @param id       会话主键
     * @param routeKey 子路由键，可空
     * @return 本轮事件（单 Agent）或轻量确认（子计划）
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/plan/reject")
    public Result<MessageResp> rejectPlan(@PathVariable Long id,
                                          @RequestParam(required = false) String routeKey) {
        if (routeKey != null && !routeKey.isBlank()) {
            return Result.ok(runs.rejectChildPlan(id, routeKey.trim()));
        }
        return Result.ok(runs.rejectPlan(id));
    }

    /**
     * 编排根下当前待批的子计划。刷新页面后靠它恢复「批准 / 拒绝」按钮；
     * 没有待批子（未开规划确认、已批准/拒绝、已超时）时 data 为 null。
     *
     * @param id 根会话主键
     * @return 子计划视图或 null
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions/{id}/plan/child-pending")
    public Result<ChildPlanPendingResp> pendingChildPlan(@PathVariable Long id) {
        return Result.ok(runs.pendingChildPlan(id).map(this::toChildPlanResp).orElse(null));
    }

    /**
     * 待批准计划。刷新页面后前端靠它恢复「批准 / 取消」按钮；
     * 没有待批准计划（已批准、已取消或已过期）时 data 为 null。
     *
     * @param id 会话主键
     * @return 计划视图或 null
     */
    @SaCheckPermission(RuntimePermissions.LIST)
    @GetMapping("/sessions/{id}/plan/pending")
    public Result<PlanResp> pendingPlan(@PathVariable Long id) {
        return Result.ok(runs.pendingPlan(id).map(this::toPlanResp).orElse(null));
    }

    /**
     * 领域计划视图 → 接口响应。字段与 PLAN 事件正文一致，前端一套解析即可。
     *
     * @param plan 计划视图
     * @return 接口对象
     */
    private PlanResp toPlanResp(PlanView plan) {
        PlanResp resp = new PlanResp();
        resp.setPlanId(plan.planId());
        resp.setState(plan.state().name());
        resp.setSummary(plan.summary());
        List<PlanResp.Step> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            PlanResp.Step one = new PlanResp.Step();
            one.setSeq(step.seq());
            one.setTitle(step.title());
            one.setDetail(step.detail());
            steps.add(one);
        }
        resp.setSteps(steps);
        return resp;
    }

    /**
     * 子计划视图 → 接口响应。与 SSE {@code ma_child_plan_pending} 同一套字段。
     *
     * @param view 子计划待批视图
     * @return 接口对象
     */
    private ChildPlanPendingResp toChildPlanResp(ChildPlanPendingView view) {
        ChildPlanPendingResp resp = new ChildPlanPendingResp();
        resp.setRouteKey(view.routeKey());
        resp.setChildAgentId(view.childAgentId());
        resp.setChildAgentName(view.childAgentName());
        resp.setPlanId(view.planId());
        resp.setState(view.state());
        resp.setRunState(view.runState());
        resp.setSummary(view.summary());
        List<PlanResp.Step> steps = new ArrayList<>();
        for (PlanStep step : view.steps()) {
            PlanResp.Step one = new PlanResp.Step();
            one.setSeq(step.seq());
            one.setTitle(step.title());
            one.setDetail(step.detail());
            steps.add(one);
        }
        resp.setSteps(steps);
        return resp;
    }

    /**
     * @param id 会话主键
     * @return 取消后的会话
     */
    @SaCheckPermission(RuntimePermissions.RUN)
    @PostMapping("/sessions/{id}/cancel")
    public Result<SessionResp> cancel(@PathVariable Long id) {
        return Result.ok(sessions.cancel(id));
    }
}
