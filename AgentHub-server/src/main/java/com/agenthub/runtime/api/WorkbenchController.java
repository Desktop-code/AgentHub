package com.agenthub.runtime.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageQuery;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.MessageReq;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.dto.PlanResp;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.api.dto.SessionTitleReq;
import com.agenthub.runtime.api.dto.ChildPlanPendingResp;
import com.agenthub.runtime.api.dto.WorkbenchChoiceReq;
import com.agenthub.runtime.api.dto.WorkbenchConfigResp;
import com.agenthub.runtime.api.dto.WorkbenchRouteOptionsResp;
import com.agenthub.runtime.api.dto.WorkbenchRouteReq;
import com.agenthub.runtime.api.sse.ServletSseFrameSink;
import com.agenthub.runtime.api.sse.SseBatchOptions;
import com.agenthub.runtime.application.TrialMessageIdempotency;
import com.agenthub.runtime.application.TrialRunAppService;
import com.agenthub.runtime.application.WorkbenchChatAppService;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台正式对话接口。所属域：runtime。
 *
 * <p>与 {@code /api/runtime/trial/**} 的分工：
 * <ul>
 *   <li>本接口是<b>业务入口</b>：会话一律按服务端默认接待方案创建，忽略前端 agentId / maId；</li>
 *   <li>调试接口保持原语义与权限，供管理员在 Agent 抽屉 / 多智能体运行页排查；</li>
 *   <li>两者共用 {@code agent_run_session}，靠 {@code entry_channel} 隔离列表与访问。</li>
 * </ul>
 *
 * <p>控制器零业务：就绪判定、渠道校验在 {@link WorkbenchChatAppService}，
 * 执行与事件落库仍走既有 Trial 服务，不另起一套引擎。
 */
@RestController
@RequestMapping("/api/runtime/workbench")
public class WorkbenchController {

    private final WorkbenchChatAppService chats;
    private final TrialRunAppService runs;
    private final ObjectMapper json;
    /** SSE 正文合批参数，与试跑接口同一套配置 */
    private final SseBatchOptions sseBatch;

    /**
     * @param chats            工作台门面（就绪、会话、渠道校验）
     * @param runs             既有运行服务（发消息 / SSE / 计划批准）
     * @param json             SSE data 收成一行
     * @param sseBatchWindowMs 正文合批时间窗（毫秒）
     * @param sseBatchMaxBytes 单帧正文上限（UTF-8 字节）
     */
    public WorkbenchController(WorkbenchChatAppService chats, TrialRunAppService runs, ObjectMapper json,
                               @Value("${agenthub.runtime.sse.batch-window-ms:50}") long sseBatchWindowMs,
                               @Value("${agenthub.runtime.sse.batch-max-bytes:2048}") int sseBatchMaxBytes) {
        this.chats = chats;
        this.runs = runs;
        this.json = json;
        this.sseBatch = new SseBatchOptions(sseBatchWindowMs, sseBatchMaxBytes);
    }

    /**
     * 工作台就绪状态与默认接待候选。
     *
     * @return 就绪标记与候选名单
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/config")
    public Result<WorkbenchConfigResp> config() {
        return Result.ok(chats.config());
    }

    /**
     * 新建工作台会话。请求体不接收 agentId / maId。
     *
     * @return 新会话
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions")
    public Result<SessionResp> create() {
        return Result.ok(chats.createSession());
    }

    /**
     * 当前用户的工作台会话列表；只含工作台渠道。
     *
     * @param req 页码与页大小
     * @return 会话页
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions")
    public Result<PageResult<SessionResp>> page(@Valid PageQuery req) {
        return Result.ok(chats.pageSessions(req.getCurrent(), req.getSize()));
    }

    /**
     * @param id 会话主键
     * @return 会话元数据
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}")
    public Result<SessionResp> get(@PathVariable Long id) {
        return Result.ok(chats.get(id));
    }

    /**
     * 本会话的可选线路与当前沿用线路（刀3 换线下拉）。
     *
     * @param id 会话主键
     * @return 当前线路 + 本会话快照方案的启用候选
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}/routes")
    public Result<WorkbenchRouteOptionsResp> routes(@PathVariable Long id) {
        return Result.ok(chats.routes(id));
    }

    /**
     * 手动换线（刀3）：写沿用线路并置一次性强制标记，只作用下一轮。
     *
     * @param id  会话主键
     * @param req 目标候选 route_key
     * @return 更新后的会话
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PutMapping("/sessions/{id:\\d+}/route")
    public Result<SessionResp> changeRoute(@PathVariable Long id, @Valid @RequestBody WorkbenchRouteReq req) {
        return Result.ok(chats.changeRoute(id, req.getRouteKey()));
    }

    /**
     * 取消本次 @ 指定：只丢弃「下一轮强制走这条线」的一次性标记。
     *
     * <p>为什么需要独立接口：选定时会登记强制标记，用户若在提及片上点 ×，
     * 不撤销就会让下一句新问题仍被钉给那位专家，与「取消后走入口」相悖。
     * 不动沿用线路（sticky），也不改任何消息与事件。
     *
     * @param id 会话主键
     * @return 空结果
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @DeleteMapping("/sessions/{id:\\d+}/route")
    public Result<Void> cancelRoute(@PathVariable Long id) {
        chats.clearRouteForce(id);
        return Result.ok(null);
    }

    /**
     * @param id 会话主键
     * @return 已落库事件
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}/events")
    public Result<List<EventResp>> events(@PathVariable Long id) {
        return Result.ok(chats.events(id));
    }

    /**
     * @param id 会话主键
     * @return 进行中一轮的实时快照
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}/live-events")
    public Result<List<EventResp>> liveEvents(@PathVariable Long id) {
        return Result.ok(chats.liveEvents(id));
    }

    /**
     * 同步发送一轮。失败时 HTTP 仍是成功包，data.status=FAILED。
     *
     * @param id             会话主键
     * @param req            用户输入
     * @param idempotencyKey 客户端幂等键，可空
     * @return 助手预览和本轮事件
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/messages")
    public Result<MessageResp> send(@PathVariable Long id, @Valid @RequestBody MessageReq req,
                                    @RequestHeader(value = TrialMessageIdempotency.HEADER, required = false)
                                    String idempotencyKey) {
        Long checked = chats.requireSession(id);
        chats.assertReceptionReady();
        return Result.ok(runs.send(checked, req.trimmedContent(), req.getImages(), req.filePayloads(),
                req.getAttachmentOrder(), idempotencyKey));
    }

    /**
     * 边跑边推 SSE。仍在当前请求线程执行，登录态不用另传。
     *
     * @param id             会话主键
     * @param req            用户输入
     * @param idempotencyKey 客户端幂等键，可空
     * @param response       直接写 SSE
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping(value = "/sessions/{id:\\d+}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable Long id, @Valid @RequestBody MessageReq req,
                       @RequestHeader(value = TrialMessageIdempotency.HEADER, required = false)
                       String idempotencyKey,
                       HttpServletResponse response) throws IOException {
        Long checked = chats.requireSession(id);
        chats.assertReceptionReady();
        ServletSseFrameSink.prepare(response);
        TrialChunkWriter writer = new TrialChunkWriter(response, json, sseBatch);
        writer.bindSession(checked);
        try {
            runs.stream(checked, req.trimmedContent(), req.getImages(), req.filePayloads(),
                    req.getAttachmentOrder(), writer, writer, idempotencyKey);
        } finally {
            // 与试跑接口同一收尾纪律：任何路径都要补一帧结束，内部 CAS 保证只发一次
            writer.ensureFinished();
        }
    }

    /**
     * 点选上一轮回答里的可点选项：断点续作，由同一位专家接着执行。
     *
     * <p>为什么单独一条：这是「已选好，继续」而不是新的问题。走 {@code /messages} 会重新判路由，
     * 把用户的回答当成孤立新问题（典型症状：「1」被判定为诉求不清）。
     *
     * @param id  会话主键
     * @param req 选中的选项标识
     * @return 本轮助手预览和事件
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/choice")
    public Result<MessageResp> choose(@PathVariable Long id, @Valid @RequestBody WorkbenchChoiceReq req) {
        Long checked = chats.requireSession(id);
        return Result.ok(runs.choose(checked, req.getOptionId().trim()));
    }

    /**
     * 点选可点选项的边跑边推版本（与 {@code /choice} 同一套续作语义）。
     *
     * @param id       会话主键
     * @param req      选中的选项标识
     * @param response 直接写 SSE
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping(value = "/sessions/{id:\\d+}/choice/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chooseStream(@PathVariable Long id, @Valid @RequestBody WorkbenchChoiceReq req,
                             HttpServletResponse response) throws IOException {
        Long checked = chats.requireSession(id);
        ServletSseFrameSink.prepare(response);
        TrialChunkWriter writer = new TrialChunkWriter(response, json, sseBatch);
        writer.bindSession(checked);
        try {
            runs.chooseStream(checked, req.getOptionId().trim(), writer, writer);
        } finally {
            // 与发消息同一收尾纪律：任何路径都补一帧结束，内部 CAS 保证只发一次
            writer.ensureFinished();
        }
    }

    /**
     * @param id  会话主键
     * @param req 新标题
     * @return 更新后的会话
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/title")
    public Result<SessionResp> rename(@PathVariable Long id, @Valid @RequestBody SessionTitleReq req) {
        return Result.ok(chats.rename(id, req.getTitle().trim()));
    }

    /**
     * @param id 会话主键
     * @return 重置后的会话
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/clear")
    public Result<SessionResp> clear(@PathVariable Long id) {
        return Result.ok(chats.clear(id));
    }

    /**
     * @param id 会话主键
     * @return 取消后的会话
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/cancel")
    public Result<SessionResp> cancel(@PathVariable Long id) {
        return Result.ok(chats.cancel(id));
    }

    /**
     * @param id 会话主键
     * @return 空成功
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @DeleteMapping("/sessions/{id:\\d+}")
    public Result<Void> remove(@PathVariable Long id) {
        chats.remove(id);
        return Result.ok(null);
    }

    /**
     * 待批准计划；没有时 data 为 null。
     *
     * @param id 会话主键
     * @return 计划视图或 null
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}/plan/pending")
    public Result<PlanResp> pendingPlan(@PathVariable Long id) {
        return Result.ok(chats.pendingPlan(id).map(this::toPlanResp).orElse(null));
    }

    /**
     * 编排根下当前待批的子专家计划；没有时 data 为 null。
     *
     * <p>页面刷新后 SSE 已断，候选子若开了规划确认，只能靠这条接口恢复「批准 / 拒绝」。
     *
     * @param id 根会话主键
     * @return 子计划视图或 null
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @GetMapping("/sessions/{id:\\d+}/plan/child-pending")
    public Result<ChildPlanPendingResp> pendingChildPlan(@PathVariable Long id) {
        return Result.ok(chats.pendingChildPlan(id).map(this::toChildPlanResp).orElse(null));
    }

    /**
     * 批准待执行计划。
     *
     * @param id       会话主键
     * @param routeKey 子路由键，可空
     * @return 本轮结果
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/plan/approve")
    public Result<MessageResp> approvePlan(@PathVariable Long id,
                                           @RequestParam(required = false) String routeKey) {
        return Result.ok(chats.approvePlan(id, routeKey));
    }

    /**
     * 拒绝待执行计划。
     *
     * @param id       会话主键
     * @param routeKey 子路由键，可空
     * @return 本轮结果
     */
    @SaCheckPermission(RuntimePermissions.CHAT)
    @PostMapping("/sessions/{id:\\d+}/plan/reject")
    public Result<MessageResp> rejectPlan(@PathVariable Long id,
                                          @RequestParam(required = false) String routeKey) {
        return Result.ok(chats.rejectPlan(id, routeKey));
    }

    /** 领域计划视图 → 接口响应，字段与调试接口一致。 */
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
     * 子计划视图 → 接口响应。字段与调试接口 / SSE {@code ma_child_plan_pending} 一致。
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
}
