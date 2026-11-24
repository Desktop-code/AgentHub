package com.agenthub.runtime.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.AgentOptionResp;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.SessionPageReq;
import com.agenthub.runtime.api.dto.SessionResp;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.workflow.application.WorkflowRunAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 运行会话门面：对外保持原有调用面，内部按「取消 / 查询 / 生命周期」分工。所属域：runtime。
 *
 * <p>本类只保留取消与中断（跨工作流取消、ReAct 中断、快照封口、trace 收口）；
 * 查询委托 {@link TrialSessionQueryService}，新建 / 改名 / 线路粘性 / 清空 / 删除委托
 * {@link TrialSessionLifecycleService}。删除 RUNNING 前经 closer 批抽 LONG；清空只丢热窗口不抽。
 */
@Service
public class TrialSessionAppService {

    private static final Logger log = LoggerFactory.getLogger(TrialSessionAppService.class);

    private final RunSessionRepository sessions;
    private final TrialAccess access;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialFlight flight;
    private final ReActTrialExecutor react;
    private final TrialSessionCloser closer;
    private final TrialLiveBuffer live;
    private final PlanPendingRegistry pendingPlans;
    private final ChildPlanApprovalRegistry childApprovals;
    /**
     * 工作流取消端：停止工作台一轮时，除中断 ReAct 外还要停掉同会话正在跑的工作流实例。
     * 反向依赖不存在（workflow 不依赖本类），不会形成循环。
     */
    private final WorkflowRunAppService workflowRuns;
    /**
     * 工作流对话停等的停止端：等待期间请求线程已结束，
     * 停止走这里取消 run 并补中断闭环，不把会话收成取消。
     */
    private final WorkflowReplyResumer workflowReplies;
    /**
     * 方案子专家询问停等的停止端：等待期间没有请求线程，
     * 停止走这里取消等待行并补中断闭环（回复态选项 + 「已中断」），不把会话收成取消。
     */
    private final MaReplyResumer maReplies;
    /**
     * 本轮 trace 收口：停止工作台一轮时把仍为 RUNNING 的 trace / 步骤收成 CANCELLED，
     * 避免右上角明细里残留「执行中」。只对工作台入口调用，不改管理端运行 / 多智能体的口径。
     */
    private final TrialObsHooks obs;
    /** 只读查询：下拉、能力、分页、详情、回放与实时快照 */
    private final TrialSessionQueryService query;
    /** 写侧生命周期：新建、改名、线路粘性、清空、删除 */
    private final TrialSessionLifecycleService lifecycle;

    /**
     * @param sessions        会话
     * @param access          仅会话所属用户
     * @param operatorContext 当前用户
     * @param operLogWriter   只记动作，不记对话
     * @param flight          与发送共用的单飞
     * @param react           中断本轮模型 / 工具循环
     * @param closer          取消结案：写 CANCELLED 后再抽
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询与取消封口
     * @param pendingPlans    待批准计划；取消时一并丢弃，避免批准到已废弃上下文
     * @param childApprovals  编排根下的子计划待批；取消时一并拒绝，避免线程悬挂
     * @param workflowRuns    工作流取消端：停止一轮时连带取消同会话正在跑的 run
     * @param workflowReplies 工作流对话停等的停止端：等待中的运行按现网中断收口，会话仍可用
     * @param maReplies       方案子专家询问停等的停止端；与工作流等待互斥
     * @param obs             trace 收口：停止工作台一轮时把残留 RUNNING 的 trace / 步骤收成 CANCELLED
     * @param query           只读查询服务
     * @param lifecycle       写侧生命周期服务
     */
    public TrialSessionAppService(RunSessionRepository sessions, TrialAccess access,
                                  OperatorContext operatorContext, OperLogWriter operLogWriter,
                                  TrialFlight flight, ReActTrialExecutor react, TrialSessionCloser closer,
                                  TrialLiveBuffer live, PlanPendingRegistry pendingPlans,
                                  ChildPlanApprovalRegistry childApprovals,
                                  WorkflowRunAppService workflowRuns, WorkflowReplyResumer workflowReplies,
                                  MaReplyResumer maReplies, TrialObsHooks obs,
                                  TrialSessionQueryService query, TrialSessionLifecycleService lifecycle) {
        this.sessions = sessions;
        this.access = access;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.flight = flight;
        this.react = react;
        this.closer = closer;
        this.live = live;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.workflowRuns = workflowRuns;
        this.workflowReplies = workflowReplies;
        this.maReplies = maReplies;
        this.obs = obs;
        this.query = query;
        this.lifecycle = lifecycle;
    }

    /**
     * @return 启用中的 Agent 下拉（含多模态 / 语音能力）
     */
    public List<AgentOptionResp> enabledAgents() {
        return query.enabledAgents();
    }

    /**
     * @param agentId Agent 主键
     * @return 单个 Agent 的多模态 / 语音能力
     */
    public AgentOptionResp capability(Long agentId) {
        return query.capability(agentId);
    }

    /**
     * @param req 运行页会话分页请求
     * @return 当前用户在指定 Agent 或方案下的会话页
     */
    public PageResult<SessionResp> page(SessionPageReq req) {
        return query.page(req);
    }

    /**
     * @param current 页码
     * @param size    页大小
     * @return 工作台左栏会话页
     */
    public PageResult<SessionResp> pageWorkbench(long current, long size) {
        return query.pageWorkbench(current, size);
    }

    /**
     * @param id 会话主键
     * @return 会话详情
     */
    public SessionResp get(Long id) {
        return query.get(id);
    }

    /**
     * @param id 会话主键
     * @return 已落库事件（工作流卡片按节点运行态补全）
     */
    public List<EventResp> events(Long id) {
        return query.events(id);
    }

    /**
     * @param id 会话主键
     * @return 进行中一轮的内存快照
     */
    public List<EventResp> liveEvents(Long id) {
        return query.liveEvents(id);
    }

    /**
     * @param agentId 定义主键
     * @return 新建的会话，状态 RUNNING
     */
    public SessionResp create(Long agentId) {
        return lifecycle.create(agentId);
    }

    /**
     * @param maId 方案主键
     * @return 新建的方案会话，状态 RUNNING
     */
    public SessionResp createFromMa(Long maId) {
        return lifecycle.createFromMa(maId);
    }

    /**
     * @param spec 默认接待方案解析结果
     * @return 新建的工作台会话，状态 RUNNING
     */
    public SessionResp createForWorkbench(MaRunSpec spec) {
        return lifecycle.createForWorkbench(spec);
    }

    /**
     * @param id    会话主键
     * @param title 用户输入的标题
     * @return 更新后的会话
     */
    public SessionResp rename(Long id, String title) {
        return lifecycle.rename(id, title);
    }

    /**
     * @param id       会话主键
     * @param routeKey 沿用的 route_key；null 表示清空
     * @param agentId  该线路的子 Agent；清空时传 null
     * @return 更新后的会话
     */
    public SessionResp bindStickyRoute(Long id, String routeKey, Long agentId) {
        return lifecycle.bindStickyRoute(id, routeKey, agentId);
    }

    /**
     * @param id 会话主键
     * @return 清空后的会话
     */
    public SessionResp clear(Long id) {
        return lifecycle.clear(id);
    }

    /**
     * @param id 会话主键
     */
    public void remove(Long id) {
        lifecycle.remove(id);
    }

    /**
     * 这一轮还在执行时，走中断：停掉模型和工具，会话保持可继续发送。
     * 没在执行的 RUNNING 会话仍按原取消入口收成 CANCELLED。
     *
     * @param id 会话主键
     * @return 中断或取消后的会话
     */
    @Transactional
    public SessionResp cancel(Long id) {
        RunSession session = access.require(id);
        /*
         * 工作流在对话里停等：这一步请求线程早已结束（没有 ReAct 在跑），普通分支会拒绝或把整条
         * 会话收成 CANCELLED，用户之后就无法接着聊了。等待中的运行按现网中断口径单独停掉，
         * 会话本身保持可用——这正是「停在等待时点停止」的语义。
         */
        if (!flight.running(id)
                && EntryChannel.WORKBENCH.name().equals(session.getEntryChannel())
                && (workflowReplies.stopWaiting(id, session.getUserId())
                || maReplies.stopWaiting(id, session.getUserId()))) {
            return lifecycle.respOf(id);
        }
        if (flight.running(id)) {
            /*
             * 先按会话取消工作流实例，再中断 ReAct：
             * 工作流是同步引擎，只有库里变 CANCELLED 才会在下一节点前退出；
             * 只中断 ReAct 会让工作流卡保持「运行中」、节点继续往下跑。
             * 用独立短事务提交，避免引擎线程在本次大事务提交前读不到取消状态。
             * 放在 interrupt 之前：只有工作流子（没有 ReAct 线程登记）的一轮也能真正停下。
             */
            int stoppedRuns = 0;
            RuntimeException workflowFailure = null;
            try {
                stoppedRuns = workflowRuns.cancelActiveByWorkbenchSession(id);
            } catch (RuntimeException ex) {
                // 先记下失败但不在这里抛：宿主线程的模型 / 工具必须先停，否则停一次卡一次
                workflowFailure = ex;
                log.warn("停止时取消工作流失败 sessionId={} type={} cause={}",
                        id, ex.getClass().getSimpleName(), ex.getMessage());
            }
            // 含「根停在等待子计划批准」：请求线程还在，但没有登记成可中断的模型/工具循环时仍拒绝；
            // 已经确认停掉工作流子时按「停整轮」处理，不再要求必须命中 ReAct 登记
            boolean interrupted = react.interrupt(id);
            if (!interrupted && stoppedRuns == 0) {
                throw new BizException(ErrorCode.BAD_REQUEST, "本轮正在执行，请等待结束后再取消");
            }
            if (workflowFailure != null) {
                // 工作流没收掉：如实说明，不把这一轮标成已停后又不封口。
                // 只有真的命中了 ReAct 中继时才说「模型已中断」，纯工作流子的一轮不冒充
                String cause = workflowFailure.getMessage() == null
                        ? workflowFailure.getClass().getSimpleName() : workflowFailure.getMessage();
                String prefix = interrupted ? "模型已中断，但工作流取消失败：" : "工作流取消失败：";
                throw new BizException(ErrorCode.BAD_REQUEST, prefix + cause);
            }
            /*
             * 把内存快照封成中断终态：执行线程可能还要一会儿才退出（工作流节点收尾 / 其它并行子），
             * 不封口的话刷新时仍会把最后一张 RUNNING 快照画成运行中、并重新进入续订。
             * 快照里已出现的过程与工作流进度保留，只把进行态改成中断终态。
             *
             * 只对工作台正式对话封口：管理端运行 / 多智能体运行共用同一套取消与快照，
             * 本刀不改变那些入口的现有回放口径；它们仍走原来的「中断写 error」收口。
             */
            if (EntryChannel.WORKBENCH.name().equals(session.getEntryChannel())) {
                // 先把 trace / 步骤收成 CANCELLED：明细里残留的 RUNNING 会让用户以为还在执行。
                // 执行线程稍后收口时 finishIfRunning 不会把已取消的 trace 再改回其它终态。
                try {
                    obs.cancel(id);
                } catch (RuntimeException ex) {
                    // 埋点是旁路：收不回也不能把一次已经生效的停止改成失败
                    log.warn("停止时取消 trace 失败 sessionId={} type={}",
                            id, ex.getClass().getSimpleName());
                }
                live.sealCancelled(id);
            }
            operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id),
                    "中断运行 " + id + (stoppedRuns > 0 ? "，已取消工作流 " + stoppedRuns + " 个" : ""));
            return lifecycle.respOf(id);
        }
        if (!RunStatus.RUNNING.name().equals(session.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能取消尚未完成的会话");
        }
        // 请求线程已不在（重启 / 异常收尾）也要把仍在跑的工作流实例收掉，避免节点继续执行；
        // 工作流取消失败不挡会话收口，否则会留下一个永远「运行中」的会话
        try {
            workflowRuns.cancelActiveByWorkbenchSession(id);
        } catch (RuntimeException ex) {
            log.warn("取消会话时连带取消工作流失败 sessionId={} type={} cause={}",
                    id, ex.getClass().getSimpleName(), ex.getMessage());
        }
        /*
         * 请求线程已不在时也要把工作台这一轮的内存快照封成终态：
         * 执行线程可能已经不登记 ReAct，但快照里最后一张工作流卡仍是 RUNNING；
         * 刷新后会先按运行中出站。trace / 步骤由 TrialSessionCloser.cancel 统一收口，
         * 这里只补工作台快照，保持其它入口口径不变。
         */
        if (EntryChannel.WORKBENCH.name().equals(session.getEntryChannel())) {
            live.sealCancelled(id);
        }
        // 取消即放弃待批准计划，避免之后还能批到一个已作废的会话上
        pendingPlans.drop(id);
        // 兜底清理可能残留的子计划待批与批准槽（根取消后不得再有线程等在那里）
        childApprovals.rejectAll(id);
        closer.cancel(session, operatorContext.requireUserId());
        operLogWriter.write("runtime", "cancel", "agent_run_session", String.valueOf(id), "取消运行会话 " + id);
        return lifecycle.respOf(id);
    }
}
