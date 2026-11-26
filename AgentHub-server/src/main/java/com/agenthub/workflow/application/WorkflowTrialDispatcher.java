package com.agenthub.workflow.application;

import cn.dev33.satoken.stp.StpUtil;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import com.agenthub.workflow.domain.service.WorkflowRunListener;
import com.agenthub.workflow.domain.service.WorkflowStateMachine;
import com.agenthub.workflow.domain.service.WorkflowTrialLive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 把试跑引擎挪到后台线程。所属域：workflow 的 application。
 *
 * <p>职责：run 落库后立刻返回的那段空档里，由本类带着已捕获的操作人与租户继续
 * {@link WorkflowEngine#execute}。请求线程不再等整段跑完。
 *
 * <p>边界：
 * <ul>
 *   <li>不新建 run、不改触发类型、不写 {@code workbench_session_id}；</li>
 *   <li>队列满时把仍是 CREATED 的 run 收成取消，避免永远停在「已创建」；</li>
 *   <li>终态 run 再次投递直接跳过（幂等）；</li>
 *   <li>租户以提交时捕获的值为准，和库里不一致就不调度。</li>
 * </ul>
 *
 * <p>线程：提交发生在请求线程；执行发生在 {@code workflowTrialExecutor}。
 */
@Component
public class WorkflowTrialDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTrialDispatcher.class);

    private final WorkflowEngine engine;
    private final WorkflowRunRepository runs;
    private final Executor executor;
    /**
     * 直播挂载口；由 runtime 实现，这里只按需取。
     * <p>用 {@link ObjectProvider} 而不是直接注入：调度属于 workflow 域，
     * 不能因为 runtime 侧还要反向依赖 workflow 的只读入口而形成构造环。
     */
    private final ObjectProvider<WorkflowTrialLive> trialLive;

    /**
     * 单测构造：不接直播，行为与接入前一致。
     *
     * @param engine   调度引擎（在调用它的线程上同步跑完）
     * @param runs     运行仓储（拒绝入队时收口、执行前重读）
     * @param executor 调试调度池；单测可换成当前线程
     */
    public WorkflowTrialDispatcher(WorkflowEngine engine, WorkflowRunRepository runs,
                                   @Qualifier("workflowTrialExecutor") Executor executor) {
        this(engine, runs, executor, null);
    }

    /**
     * @param engine    调度引擎（在调用它的线程上同步跑完）
     * @param runs      运行仓储（拒绝入队时收口、执行前重读）
     * @param executor  调试调度池；单测可换成当前线程
     * @param trialLive 调试试跑直播挂载口；可空
     */
    @Autowired
    public WorkflowTrialDispatcher(WorkflowEngine engine, WorkflowRunRepository runs,
                                   @Qualifier("workflowTrialExecutor") Executor executor,
                                   ObjectProvider<WorkflowTrialLive> trialLive) {
        this.engine = engine;
        this.runs = runs;
        this.executor = executor;
        this.trialLive = trialLive;
    }

    /**
     * 提交一次已落库的试跑。调用方应在返回后自己再读详情。
     *
     * @param runId      已插入的 run
     * @param operatorId 请求线程上取到的操作人
     * @param tenantId   请求线程上取到的租户
     */
    public void submit(Long runId, long operatorId, long tenantId) {
        submit(runId, operatorId, tenantId, null);
    }

    /**
     * 提交一次已落库的试跑 / 续跑，正常返回后引擎在池线程继续。
     *
     * @param runId      已落库（续跑时已回到 RUNNING）的 run
     * @param operatorId 请求线程上取到的操作人
     * @param tenantId   请求线程上取到的租户
     * @param reply      用户对等待中询问的回复；新建 / 人工通过时为空，直播投影据此把回复挂回询问节点
     */
    public void submit(Long runId, long operatorId, long tenantId, String reply) {
        if (runId == null) {
            return;
        }
        // 请求属性不可继承到池线程；在还处在请求线程时抓一份，执行时再装回去
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        try {
            executor.execute(() -> execute(runId, operatorId, tenantId, reply, attrs));
        } catch (RejectedExecutionException ex) {
            log.warn("调试队列已满，本次未开始调度 runId={}", runId);
            refuse(runId, operatorId);
        }
    }

    /**
     * 在调用线程上把直播快照按库里的终态结算。
     *
     * <p>取消等不经过引擎线程的收口必须显式调这里：否则快照会停在最后一帧「运行中」，
     * 前端即使库里已经是终态也要继续转圈等引擎线程的 finally。没有挂载直播时安全空跑。
     *
     * @param runId 运行主键；可空
     */
    public void settle(Long runId) {
        WorkflowTrialLive live = liveOrNull();
        if (live == null || runId == null) {
            return;
        }
        try {
            live.finish(runId);
        } catch (RuntimeException ex) {
            log.warn("调试直播结算失败 runId={} type={}", runId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 队列满：不能把调用方堵在请求线程，但也不能留下永远不动的「已创建 / 运行中」。
     *
     * <p>新建（CREATED）按取消表达「没开始」；续跑（RUNNING）已经没有等待回复的语义可回退，
     * 按失败收口并写明原因。条件更新保证与取消 / 引擎并发时以库为准。
     *
     * @param runId      run 主键
     * @param operatorId 操作人
     */
    private void refuse(Long runId, long operatorId) {
        try {
            WorkflowRun fresh = runs.findById(runId).orElse(null);
            WorkflowRunStatus current = fresh == null ? null : WorkflowRunStatus.parse(fresh.getStatus());
            if (current == WorkflowRunStatus.CREATED) {
                WorkflowStateMachine.requireRun(WorkflowRunStatus.CREATED, WorkflowRunStatus.CANCELLED);
                runs.updateStatusIfMatches(runId, WorkflowRunStatus.CREATED.name(),
                        WorkflowRunStatus.CANCELLED.name(),
                        "调试队列已满，这次没有开始，请稍后再试",
                        null, LocalDateTime.now(), operatorId);
            } else if (current == WorkflowRunStatus.RUNNING) {
                WorkflowStateMachine.requireRun(WorkflowRunStatus.RUNNING, WorkflowRunStatus.FAILED);
                runs.updateStatusIfMatches(runId, WorkflowRunStatus.RUNNING.name(),
                        WorkflowRunStatus.FAILED.name(),
                        "调试队列已满，续跑没能开始，请稍后再试",
                        null, LocalDateTime.now(), operatorId);
            }
        } catch (RuntimeException ex) {
            // 收口失败只记日志：调用方仍能拿到详情，按库里的状态展示
            log.warn("调试队列已满且收口失败 runId={} type={}", runId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 后台执行体：先对上租户与操作人，再跑引擎。
     *
     * @param runId      run 主键
     * @param operatorId 捕获的操作人
     * @param tenantId   捕获的租户
     * @param reply      用户回复；新建 / 人工通过时为空
     * @param attrs      请求属性；可空（单测 / 请求已结束）
     */
    private void execute(Long runId, long operatorId, long tenantId, String reply, RequestAttributes attrs) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        boolean switched = false;
        try {
            if (attrs != null) {
                RequestContextHolder.setRequestAttributes(attrs, false);
            }
            switched = switchOperator(operatorId);
            WorkflowRun fresh = runs.findById(runId).orElse(null);
            if (fresh == null) {
                return;
            }
            // 租户以提交时的快照为准：后台线程上的配置读数不能换成别人的 run
            if (fresh.getTenantId() == null || fresh.getTenantId() != tenantId) {
                log.warn("后台试跑租户不一致，跳过调度 runId={}", runId);
                return;
            }
            WorkflowRunStatus status = WorkflowRunStatus.parse(fresh.getStatus());
            if (status == null || status.terminal()) {
                return;
            }
            if (status != WorkflowRunStatus.CREATED && status != WorkflowRunStatus.RUNNING) {
                return;
            }
            WorkflowTrialLive live = liveOrNull();
            WorkflowRunListener listener = openLive(live, fresh, reply);
            try {
                engine.execute(fresh, operatorId, listener);
            } finally {
                // 与 open 同线程成对收口：直播最后一帧按库里终态落定，节点过程范围同时释放
                finishLive(live, listener, runId);
            }
        } catch (RuntimeException ex) {
            log.warn("后台试跑调度失败 runId={} type={}", runId, ex.getClass().getSimpleName());
        } finally {
            if (switched) {
                endSwitch();
            }
            if (previous != null) {
                RequestContextHolder.setRequestAttributes(previous, false);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /**
     * 取直播实现；没有实现（单测 / 未装配）时为空。
     *
     * @return 实现或 {@code null}
     */
    private WorkflowTrialLive liveOrNull() {
        if (trialLive == null) {
            return null;
        }
        try {
            return trialLive.getIfAvailable();
        } catch (RuntimeException ex) {
            log.warn("调试直播实现不可用 type={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 挂直播观察者。挂载失败按「不接直播」继续跑，绝不因为展示旁路打断调度。
     *
     * @param live  直播实现；可空
     * @param run   运行实例
     * @param reply 用户对等待中询问的回复；新建 / 人工通过时为空
     * @return 节点观察者；不接直播时为空
     */
    private WorkflowRunListener openLive(WorkflowTrialLive live, WorkflowRun run, String reply) {
        if (live == null) {
            return null;
        }
        try {
            return live.open(run, reply);
        } catch (RuntimeException ex) {
            log.warn("调试直播挂载失败 runId={} type={}", run.getId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 收口直播。只收本次真正挂上的那条，避免把别的执行线程的范围关掉。
     *
     * @param live     直播实现；可空
     * @param listener 本次挂上的观察者；为空表示没接
     * @param runId    运行主键
     */
    private void finishLive(WorkflowTrialLive live, WorkflowRunListener listener, Long runId) {
        if (live == null || listener == null) {
            return;
        }
        try {
            live.finish(runId);
        } catch (RuntimeException ex) {
            log.warn("调试直播收口失败 runId={} type={}", runId, ex.getClass().getSimpleName());
        }
    }

    /**
     * 把登录身份装到当前线程。没有 Web 上下文时 Sa-Token 会拒绝切换，
     * 引擎仍使用捕获的操作人 id，不因此中断调度。
     *
     * @param operatorId 操作人
     * @return true 表示需要在 finally 里 {@code endSwitch}
     */
    private static boolean switchOperator(long operatorId) {
        try {
            StpUtil.switchTo(operatorId);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 结束登录切换；失败只忽略，避免掩盖调度本身的收尾。 */
    private static void endSwitch() {
        try {
            StpUtil.endSwitch();
        } catch (RuntimeException ex) {
            // 切换没真正生效时 endSwitch 也会抛，不影响 run 结果
        }
    }
}
