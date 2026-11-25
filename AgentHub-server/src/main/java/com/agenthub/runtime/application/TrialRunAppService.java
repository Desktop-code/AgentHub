package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.ChoiceCapture;
import com.agenthub.runtime.domain.model.EntryChannel;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PreprocessView;
import com.agenthub.runtime.domain.service.PreprocessJson;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RouteHint;
import com.agenthub.runtime.domain.model.RouteSticky;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;
import com.agenthub.runtime.domain.model.TrialIdemGuard;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.repository.RunSessionRepository;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ChoiceCaptureHolder;
import com.agenthub.runtime.domain.service.ChoiceContinue;
import com.agenthub.runtime.domain.service.EntryMaterialHolder;
import com.agenthub.runtime.domain.service.LeafChoiceHolder;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import com.agenthub.runtime.domain.service.MeterTurnHolder;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.SessionCancelFace;
import com.agenthub.runtime.domain.service.TrialSendGate;
import com.agenthub.runtime.domain.service.TurnStillOpen;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import com.agenthub.runtime.domain.service.UserMessageImages;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import com.agenthub.runtime.infra.storage.TrialUserImageStorage;
import com.agenthub.runtime.domain.service.TrialTurnOutcome;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.VisionOcrTexts;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.runtime.application.router.MaPauseSignal;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import com.agenthub.workflow.domain.model.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
/**
 * 发送一轮试跑。所属域：runtime。模型调用在事务外，成功或失败后再落事件。
 * 会话在多轮期间保持 RUNNING，不写结束时间。业务失败写 error 事件后仍保持 RUNNING，方便续聊。
 * 只有一轮没走到成功或失败收尾就逃出时，才经 {@link StaleRunCloser} 收成取消。
 * 操作日志只记会话，不记用户原文、助手正文和工具实参。
 */
@Service
public class TrialRunAppService {

    private static final Logger log = LoggerFactory.getLogger(TrialRunAppService.class);

    private final RunSessionRepository sessions;
    private final RunEventRepository events;
    private final AgentDefRepository agents;
    private final TrialAccess access;
    private final TrialBindingLoader loader;
    private final ReActTrialExecutor react;
    private final RouterTrialExecutor router;
    private final PlanTrialExecutor planRunner;
    private final PlanPendingRegistry pendingPlans;
    private final ChildPlanApprovalRegistry childApprovals;
    private final TrialEventWriter writer;
    /** 本轮结果摊平成时间线事件（计划帧 / Router 分段 / 思考与工具 / 文件 / 助手答复） */
    private final TrialTimelineWriter timeline;
    private final TrialFlight flight;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final TrialObsHooks obs;
    private final TrialMemoryCoordinator memory;
    private final TrialLiveBuffer live;
    private final VisionOcrExecutor vision;
    private final SessionTitleGeneratorAppService titleGenerator;
    /** 方案读源：虚拟根与绑根都按 ma_def 执行；为空时才回落 Agent 旧配置 */
    private final MaTrialSpecLoader maSpecs;
    /** 发消息幂等：同会话 + 同 Idempotency-Key 只真正执行一次 */
    private final TrialMessageIdempotency idempotency;
    /** 默认接待判路由模型回落：仅工作台虚拟根会话使用，调试会话语义不变 */
    private final ReceptionSchemeResolver reception;
    /** 工作台手动换线的一次性强制标记；调试路径从不读取 */
    private final WorkbenchRouteForceRegistry routeForce;

    /**
     * 工作流「对话停等」的续跑接线：本会话有等待回复的运行时，把用户下一句送回同一条 run，
     * 不新建运行、也不经入口判路由。只在工作台会话生效。
     */
    private final WorkflowReplyResumer workflowReplies;
    /**
     * 方案「子专家询问」停等的续跑接线：本会话有等待回复的方案运行时，把用户下一句
     * （点选项或输入框原文）送回同一条运行、同一位成员后续跑，不新开运行、不再经入口判路由。
     * 只在工作台会话生效；与 {@link #workflowReplies} 互斥（同一会话同时只会有一条等待）。
     */
    private final MaReplyResumer maReplies;
    /** 用户停止后的取消补写：只在 live.cancelled 时执行，异常不外抛 */
    private final TrialCancellationRecorder cancellationRecorder;
    /** 子计划批准与待批查询：只读写批准槽 / 登记表，不续跑根侧步骤 */
    private final TrialPlanApprovalService planApprovals;
    /** 一轮没能正常收尾时，把仍是 RUNNING 且没被占用的会话收成取消 */
    private final StaleRunCloser staleRuns;
    /** 用户附图落盘。无图时不参与。 */
    private final TrialUserImageStorage userImages;
    /** 用户非图片附件落盘。无文件时不参与。 */
    private final TrialUserFileStorage userFiles;
    /** 管理端对话把附件正文交给模型。工作台 / 入口不调用。 */
    private final TrialAttachmentReader attachmentReader;
    /** 入口 Router 意图前预处理。首页与 MA 运行入口共用。 */
    private final EntryPreprocess entryPreprocess;

    /**
     * @param sessions        会话
     * @param events          序号
     * @param agents          定义
     * @param access          仅会话所属用户
     * @param loader          ReAct 装配
     * @param react           官方 ReAct
     * @param router          一层分发
     * @param planRunner      计划执行外层编排
     * @param pendingPlans    待批准计划的内存登记表
     * @param childApprovals  编排根下「子计划待批准」槽与登记（同一根同时仅 1 个）
     * @param writer          摘要入库
     * @param flight          单飞
     * @param operatorContext 当前用户
     * @param operLogWriter   动作日志
     * @param obs             调用追踪。失败不影响本轮试跑
     * @param memory          窗口、记忆注入和显式记住
     * @param live            进行中一轮的内存快照，供刷新后的只读轮询
     * @param vision          图片文字提取；无图时完全不参与
     * @param titleGenerator  首轮结束后异步生成会话标题；失败不影响本轮
     * @param maSpecs         方案读源；虚拟根没有 Agent 时由它提供模板、候选与判路由模型
     * @param idempotency     发消息幂等闸门；无键请求不改变原行为
     * @param routeForce      工作台手动换线的一次性强制标记（进程内，只作用一轮）
     * @param workflowReplies 工作流对话停等的续跑接线；等待中的运行优先吃掉下一句
     * @param maReplies       方案子专家询问停等的续跑接线；与工作流等待互斥
     * @param staleRuns      异常逃出时收口遗留 RUNNING；正常成功 / 业务失败不走这里
     * @param userImages     用户附图落盘；无图时不调用
     * @param userFiles         用户非图片附件落盘；无文件时不调用
     * @param attachmentReader  管理端附件正文；工作台 / 入口不使用
     * @param entryPreprocess   入口意图前预处理；没有附件时不调用
     */
    public TrialRunAppService(RunSessionRepository sessions, RunEventRepository events, AgentDefRepository agents,
                              TrialAccess access, TrialBindingLoader loader, ReActTrialExecutor react,
                              RouterTrialExecutor router, PlanTrialExecutor planRunner,
                              PlanPendingRegistry pendingPlans, ChildPlanApprovalRegistry childApprovals,
                              TrialEventWriter writer, TrialFlight flight,
                              OperatorContext operatorContext, OperLogWriter operLogWriter, TrialObsHooks obs,
                              TrialMemoryCoordinator memory, TrialLiveBuffer live, VisionOcrExecutor vision,
                              SessionTitleGeneratorAppService titleGenerator, MaTrialSpecLoader maSpecs,
                              TrialMessageIdempotency idempotency, ReceptionSchemeResolver reception,
                              WorkbenchRouteForceRegistry routeForce, StaleRunCloser staleRuns,
                              TrialUserImageStorage userImages, TrialUserFileStorage userFiles,
                              TrialAttachmentReader attachmentReader, EntryPreprocess entryPreprocess,
                              WorkflowReplyResumer workflowReplies, MaReplyResumer maReplies) {
        this.sessions = sessions;
        this.events = events;
        this.agents = agents;
        this.access = access;
        this.loader = loader;
        this.react = react;
        this.router = router;
        this.planRunner = planRunner;
        this.pendingPlans = pendingPlans;
        this.childApprovals = childApprovals;
        this.writer = writer;
        this.timeline = new TrialTimelineWriter(writer);
        this.flight = flight;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.obs = obs;
        this.memory = memory;
        this.live = live;
        this.vision = vision;
        this.titleGenerator = titleGenerator;
        this.maSpecs = maSpecs;
        this.idempotency = idempotency;
        this.reception = reception;
        this.routeForce = routeForce;
        this.staleRuns = staleRuns;
        this.cancellationRecorder = new TrialCancellationRecorder(live, events, writer);
        this.planApprovals = new TrialPlanApprovalService(access, operatorContext, pendingPlans, childApprovals,
                operLogWriter);
        this.userImages = userImages;
        this.userFiles = userFiles;
        this.attachmentReader = attachmentReader;
        this.entryPreprocess = entryPreprocess;
        this.workflowReplies = workflowReplies;
        this.maReplies = maReplies;
    }

    /**
     * 记录带 cause 的 {@link TrialFailure}。
     *
     * <p>为什么不一律当「预期内失败」静默：{@code TrialFailure} 既可能是「撞限」「未绑模型」这类
     * 正常业务拒绝，也可能是上游真正炸了才被包成人话。前者不需要栈，后者必须能查。
     * 因此只在<b>挂了 cause</b> 时打 WARN 全栈，纯业务拒绝保持安静。
     *
     * @param label   日志用标签，如「试跑失败」
     * @param scope   定位用 id（会话 / Agent）
     * @param failure 已映射的失败
     * @param message 已脱敏的人话说明，只用于日志前缀
     */
    private void logTrialFailure(String label, Object scope, TrialFailure failure, String message) {
        if (failure == null || failure.getCause() == null) {
            return;
        }
        log.warn("{} id={} type={} msg={} cause={}", label, scope, failure.getClass().getSimpleName(),
                message, CauseDigest.of(failure.getCause()), failure);
    }

    /**
     * 同步跑完一轮。业务失败也返回 status=FAILED，便于页面展示 error_summary。
     *
     * @param sessionId 会话
     * @param content   用户输入，已通过校验
     * @return 本轮新事件和助手预览
     * @throws BizException 会话已取消，或上一轮还没结束。历史 SUCCESS 不挡。
     */
    public MessageResp send(Long sessionId, String content) {
        return executeTurn(sessionId, content, null, null, null);
    }

    /**
     * 同步跑完一轮（可带图片）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片 data URI 或 http(s) 地址，可空
     * @return 本轮新事件和助手预览
     * @throws BizException 会话已取消、上一轮未结束，或图片缺少视觉模型
     */
    public MessageResp send(Long sessionId, String content, List<String> images) {
        return executeTurn(sessionId, content, images, null, null);
    }

    /**
     * 同步跑完一轮（带幂等键）。
     *
     * <p>幂等语义见 {@link TrialMessageIdempotency}：同会话 + 同键只真正执行一次，
     * 重复请求返回首次结果的等价响应（{@code replayed=true}，不带事件）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param idemKey   客户端幂等键，可空；空＝保持现网直发行为
     * @return 本轮新事件和助手预览，或重放结果
     * @throws BizException 会话已取消、上一轮未结束、键冲突，或首轮仍在执行
     */
    public MessageResp send(Long sessionId, String content, List<String> images, String idemKey) {
        return executeTurn(sessionId, content, images, null, idemKey);
    }

    /**
     * 同步跑完一轮（图片、文件和添加顺序一起提交）。
     *
     * <p>没有文件时与 {@link #send(Long, String, List, String)} 相同，纯图片仍走 ahimg。
     * 文件不进入视觉模型；只发文件也不要求绑定视觉模型。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param files     非图片文件，可空
     * @param order     添加顺序，元素为 image 或 file，可空
     * @param idemKey   客户端幂等键，可空
     * @return 本轮新事件和助手预览，或重放结果
     * @throws BizException 会话已取消、上一轮未结束、键冲突，或首轮仍在执行
     */
    public MessageResp send(Long sessionId, String content, List<String> images,
                            List<UserMessageFiles.NamedPayload> files, List<String> order, String idemKey) {
        return executeTurn(sessionId, content, images, files, order, null, idemKey);
    }

    /**
     * 边跑边推增量，结束后仍按同步路径落库。失败时写 error 事件，不把堆栈推给页面。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     */
    public void stream(Long sessionId, String content, TrialProgress progress, TrialStreamEnd end) {
        stream(sessionId, content, null, progress, end);
    }

    /**
     * 边跑边推增量（可带图片）。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片 data URI 或 http(s) 地址，可空
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     */
    public void stream(Long sessionId, String content, List<String> images,
                       TrialProgress progress, TrialStreamEnd end) {
        stream(sessionId, content, images, progress, end, null);
    }

    /**
     * 边跑边推增量（可带图片 + 幂等键）。
     *
     * <p>SSE 重连 / 客户端重试带同一个键时不会重跑：命中终态直接补一帧 done
     * （重放响应不带正文增量），前端收尾后按 sessionId 回拉事件即可看到完整一轮。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     * @param idemKey   客户端幂等键，可空
     */
    public void stream(Long sessionId, String content, List<String> images,
                       TrialProgress progress, TrialStreamEnd end, String idemKey) {
        stream(sessionId, content, images, null, null, progress, end, idemKey);
    }

    /**
     * 边跑边推增量（图片、文件和添加顺序一起提交）。
     *
     * <p>没有文件时行为与原来的图片流一致。文件只落盘并写进用户事件摘要，不送进视觉模型。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param files     非图片文件，可空
     * @param order     添加顺序，可空
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     * @param idemKey   客户端幂等键，可空
     */
    public void stream(Long sessionId, String content, List<String> images,
                       List<UserMessageFiles.NamedPayload> files, List<String> order,
                       TrialProgress progress, TrialStreamEnd end, String idemKey) {
        try {
            progress.phase("planning");
            end.done(executeTurn(sessionId, content, images, files, order, progress, idemKey));
        } catch (BizException ex) {
            end.fail(ex.getMessage());
        } catch (TrialFailure ex) {
            // 这里极少走到（executeTurn 内部已把 TrialFailure 收成 FAILED 响应）；
            // 万一走到，文案保持与原来一致，只在带 cause 时补栈
            logTrialFailure("试跑流失败（已映射）", sessionId, ex, ex.getMessage());
            end.fail("试跑失败");
        } catch (RuntimeException ex) {
            // 流式路径的兜底 catch：带栈打，否则 SSE 断了以后控制台只剩一行类名
            log.warn("试跑流失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            end.fail("试跑失败");
        }
    }

    /**
     * 点选上一轮回答里的可点选项：断点续作，<b>不</b>重走入口判路由。
     *
     * <p>与 {@link #send(Long, String)} 的分工：
     * <ul>
     *   <li>用户另起一句新问题 → 走 send，入口判定按前文 + 新问题重新选线路；</li>
     *   <li>用户点了上一轮给的可点选项 → 走本方法，由<b>同一位</b>专家接着执行，线路不换也不清。</li>
     * </ul>
     *
     * <p>安全边界：只认上一轮真实落库回答里的 {@code agenthub-choice} 块。
     * 前端回传的 optionId 必须在该块内命中，展示文案以后端登记为准，
     * 不采信客户端传来的 label，避免借选项接口往模型上下文里塞任意文本。
     *
     * @param sessionId 会话
     * @param optionId  用户点中的选项标识（上一轮块内唯一）
     * @return 本轮新事件与终答预览
     * @throws BizException 会话不可发、上一轮没有可点选项、选项已处理，或当前线路不是单专家
     */
    public MessageResp choose(Long sessionId, String optionId) {
        return continueChoice(sessionId, optionId, null);
    }

    /**
     * 边跑边推的断点续作（点选项后由同一位专家继续）。
     *
     * <p>失败收口与 {@link #stream} 完全一致：不把堆栈推给页面，只在服务端留栈。
     *
     * @param sessionId 会话
     * @param optionId  用户点中的选项标识
     * @param progress  增量出口，不可空
     * @param end       结束出口，不可空
     */
    public void chooseStream(Long sessionId, String optionId, TrialProgress progress, TrialStreamEnd end) {
        try {
            progress.phase("planning");
            end.done(continueChoice(sessionId, optionId, progress));
        } catch (BizException ex) {
            end.fail(ex.getMessage());
        } catch (TrialFailure ex) {
            logTrialFailure("运行流续作失败（已映射）", sessionId, ex, ex.getMessage());
            end.fail("运行失败");
        } catch (RuntimeException ex) {
            log.warn("运行流续作失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            end.fail("运行失败");
        }
    }

    /**
     * 断点续作的实际执行体：校验选项 → 落 USER 事件 → 由既有专家继续跑 → 收尾。
     *
     * <p>为什么不在 choose 分支里做：choose 是「选谁」的编排决策，而这里是「已选好，同一位继续」。
     * 塞进 choose 会让入口判路由与续作共用一条分支，后续想再改任一方的语义都会互相牵扯。
     *
     * @param sessionId 会话
     * @param optionId  选项标识
     * @param progress  增量出口，可空（同步路径）
     * @return 本轮结果
     */
    private MessageResp continueChoice(Long sessionId, String optionId, TrialProgress progress) {
        RunSession session = access.require(sessionId);
        // 先做只读校验再占单飞：选项不合法时不该在会话上留下任何痕迹
        assertSendable(session);
        /*
         * 方案停等询问的选项：与输入框提交共用同一条续跑（同一成员、同一条运行），
         * 不落 USER 事件，选择写进该子专家卡。与工作流等待互斥。
         */
        Optional<MaPauseSnapshot> waitingMa = maReplies.waitingRun(sessionId);
        if (waitingMa.isPresent()) {
            MaPauseSnapshot state = waitingMa.get();
            Optional<AgentChoice.Option> option = state.getChoice() == null
                    ? Optional.empty() : state.getChoice().find(optionId);
            if (option.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该选项不属于当前等待的询问，请重新选择或直接输入");
            }
            MessageResp resp = chooseMaReply(session, state, option.get().label(), progress);
            if (resp != null) {
                return resp;
            }
            // 并发已收口：落到下面工作流 / 单专家路径（通常会明确提示没有可续选项）
        }
        /*
         * 工作流停等询问的选项：与输入框提交走同一条续跑写入（input.reply）。
         * 必须排在「单专家续作」之前——工作流目标没有 sticky 专家，
         * 落到下面那条分支只会被拒成「当前线路不是单个专家」。
         * 命中即返回；没有等待中的运行 / 选项不属于这一步时继续走原单专家路径。
         */
        Optional<WorkflowRun> waitingWorkflow = workflowReplies.waitingRun(sessionId);
        if (waitingWorkflow.isPresent()) {
            WorkflowRun waiting = waitingWorkflow.get();
            Optional<String> label = workflowReplies.waitingOptionLabel(waiting, optionId);
            if (label.isEmpty()) {
                // 已经停在等待却点了不属于这一步的选项：明确拒绝，不静默吞掉
                throw new BizException(ErrorCode.BAD_REQUEST, "该选项不属于当前等待的询问，请重新选择或直接输入");
            }
            String userText = label.get();
            MessageResp resp = chooseWorkflowReply(session, waiting, userText, progress);
            if (resp != null) {
                return resp;
            }
            // 并发已收口：落到下面按单专家路径处理（通常会明确提示没有可续选项）
        }
        ChoiceAnchor anchor = requireChoice(sessionId, optionId);
        Long stickyAgentId = session.getStickyAgentId();
        if (stickyAgentId == null) {
            // 方案目标 / 工作流目标没有单专家：这类线路不支持按选项续作，明确拒绝而不是随便挑一位
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "当前线路不是单个专家，无法按选项续作；请直接输入你的选择");
        }
        AgentDef agent = agents.findById(stickyAgentId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "上一位专家已不存在，请直接输入你的选择"));
        if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一位专家已停用，请直接输入你的选择");
        }
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        // 续作轮固定是「同一位专家接着干」：允许它再次让用户拍板（多步确认场景）
        ChoiceCaptureHolder.begin();
        // 与 runTurn 同一收尾纪律：只有没走到成功 / 业务失败就逃出时才收成取消
        boolean settled = false;
        // 用户气泡与 USER 事件都用选项文案（服务端登记的权威文案），而不是整段续作说明
        String label = anchor.option().label();
        try {
            trace = obs.begin(session, label);
            int seq = events.nextSeq(sessionId);
            int userSeq = seq;
            // 用户点选项就是这一轮的用户输入：落 USER 事件，后续「最近对话」才会把它当成用户话
            written.add(writer.append(sessionId, userSeq, EventType.USER, label, null, null));
            seq++;
            TrialProgress mirrored = progress;
            if (progress != null) {
                live.begin(sessionId, userSeq, label, null);
                mirrored = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            }
            TrialTurn turn = runChoice(session, agent, anchor, written, seq, mirrored);
            obs.finishOk(trace);
            MessageResp done = succeed(session, turn, written, label);
            settled = true;
            return done;
        } catch (TrialFailure ex) {
            logTrialFailure("续作失败", sessionId, ex, ex.getMessage());
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason(), ex.getCause());
            MessageResp failed = fail(session, written, ex.getMessage(), label);
            settled = true;
            return failed;
        } catch (RuntimeException ex) {
            log.warn("续作失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            obs.finishError(trace, "运行失败", null, ex);
            MessageResp failed = fail(session, written, "运行失败", label);
            settled = true;
            return failed;
        } finally {
            PromptInjectionHolder.clear();
            ChoiceCaptureHolder.clear();
            obs.clear();
            // 用户停止时先把快照里的过程与工作流卡片按中断终态补落库，再丢快照
            cancellationRecorder.persistIfCancelled(sessionId);
            live.clear(sessionId);
            flight.finish(sessionId);
            if (!settled) {
                staleRuns.abandonIfIdle(sessionId);
            }
        }
    }

    /**
     * 点等待中工作流的选项：续跑同一条 run → 收尾。
     *
     * <p>为什么不落 USER 事件：这是对询问节点的回复，不是新的一轮提问。
     * 时间线里只保留最初那句提问；选项文案由投影写进询问节点（{@code reply}），
     * 用户气泡不再为它多出一条。
     *
     * <p>不占单飞、不建 trace：等待期间没有请求线程在跑，这里只做一次同步续跑；
     * 引擎自身的条件迁移已经能挡住并发重复投递。
     *
     * @param session 当前会话
     * @param run     等待中的 run
     * @param userText 选项的服务端权威文案
     * @param progress 增量出口；可空
     * @return 本轮响应；运行已被并发收口时返回 null
     */
    private MessageResp chooseWorkflowReply(RunSession session, WorkflowRun run, String userText,
                                            TrialProgress progress) {
        Long sessionId = session.getId();
        List<RunEvent> written = new ArrayList<>();
        /*
         * 直播快照与同文件其它续跑路径对齐（方案点选、输入框续跑、普通一轮都有这一段）。
         *
         * <p>为什么起点取「下一条待写序号」而不是 +1：工作流点选续跑不落 USER 事件，
         * 落库第一条就是身份 begin，与方案点选「先把已答复 CHOICE 写在 userSeq」不同，
         * 因此快照第一条必须与 begin 同序号，合并时才不会与已落库的等待卡抢位、
         * 也不会凭空多出一条用户气泡。
         *
         * <p>为什么必须有：续跑期间事件要等整条运行收口才批量落库，缺了这份镜像
         * {@code /live-events} 一直是空的，中途刷新只能读到骨架里的 WAITING_REPLY
         * 与选项，回放就会把已经答过的询问卡重新画成待选。
         */
        TrialProgress sink = progress;
        boolean liveStarted = false;
        if (progress != null) {
            live.beginAt(sessionId, events.nextSeq(sessionId));
            sink = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            liveStarted = true;
        }
        try {
            Optional<WorkflowReplyResumer.Resumed> resumed =
                    workflowReplies.resumeWithLabel(sessionId, run, userText, session.getUserId(), sink);
            if (resumed.isEmpty()) {
                // 并发已收口：没有可续运行，也没有用户消息可留；按失败说明收口
                MessageResp failed = fail(session, written, "这条运行刚刚被取消或被别人继续，请重新提问", userText);
                return failed;
            }
            written.addAll(resumed.get().events());
            MessageResp resp = succeed(session, resumed.get().turn(), written, userText);
            return resp;
        } finally {
            // 续跑同步结束：权威数据已是刚落库的事件，快照使命完成（与方案点选同一收尾）
            if (liveStarted) {
                live.clear(sessionId);
            }
        }
    }

    /**
     * 点等待中方案询问的选项：续跑同一条运行 → 收尾。
     *
     * <p>不落 USER 事件：对询问的回复由投影写进发问子专家的卡（CHOICE.reply），
     * 右侧时间线仍只有最初那句提问。
     *
     * <p>与输入框提交一样占本会话单飞：两条路径都会跑模型，不能让它们并发续跑同一条运行；
     * 等待行的条件收口（markDone）仍负责「重复投递只有第一次生效」，二者职责不重叠。
     *
     * @param session  当前会话
     * @param state    等待中的方案快照
     * @param userText 选项的服务端权威文案
     * @param progress 增量出口；可空
     * @return 本轮响应；运行已被并发收口时返回 null
     */
    private MessageResp chooseMaReply(RunSession session, MaPauseSnapshot state, String userText,
                                      TrialProgress progress) {
        Long sessionId = session.getId();
        // 续作同样要单飞：与输入框提交 / 其它点选互斥，避免同一会话并发起两条续跑
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        boolean liveStarted = false;
        try {
            List<RunEvent> written = new ArrayList<>();
            TrialProgress sink = progress;
            if (progress != null) {
                /*
                 * 点选续跑同样镜像内存快照：刷新 / 离开再进时能边跑边看，不必等整轮落库。
                 * 第一条落库事件是「已答复」CHOICE，增量从它的下一条起，避免同序号合并时覆盖回复行。
                 */
                live.beginAt(sessionId, events.nextSeq(sessionId) + 1);
                sink = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
                liveStarted = true;
            }
            Optional<MaReplyResumer.Resumed> resumed = maReplies.resumeWaiting(session, state, userText, sink);
            if (resumed.isEmpty()) {
                return null;
            }
            written.addAll(resumed.get().events());
            return succeed(session, resumed.get().turn(), written, userText);
        } finally {
            // 续跑同步结束：快照使命完成，权威数据是刚落库的事件
            if (liveStarted) {
                live.clear(sessionId);
            }
            flight.finish(sessionId);
        }
    }

    /**
     * 从已落库事件里找回「上一轮的可点选项」，并校验用户点的那一项确实存在。
     *
     * <p>数据来源有两档，优先结构化：
     * <ol>
     *   <li>本轮正文之后紧跟的 {@link EventType#CHOICE} 事件（正常路径）；</li>
     *   <li>历史会话正文里的旧机器块（{@code ```agenthub-choice} 围栏），只做确定性解析。</li>
     * </ol>
     *
     * <p>只认最后一轮：更早的选项已经失效（用户若想改选，应当直接输入）。
     * 末尾若已经出现 USER 事件，说明这一份断点已经被续过，重复点会重复执行同一任务。
     *
     * <p>禁止对正文列表做启发式猜测：那套规则在不同模型的写法上一轮一轮对不上，
     * 已经下线；认不出结构就明确拒绝，让用户直接输入。
     *
     * <p>同时带回<b>上一轮的问句与终答</b>：续作要把三者一起交给同一位专家，
     * 否则模型只看到一句孤立的选项文案，只能回一句「已完成作答」。
     *
     * @param sessionId 会话
     * @param optionId  选项标识
     * @return 选项（权威展示文案）＋上一轮问句 / 终答
     * @throws BizException 没有可续的选项块、选项不存在，或该断点已处理
     */
    private ChoiceAnchor requireChoice(Long sessionId, String optionId) {
        List<RunEvent> rows = events.listBySessionId(sessionId);
        // 第一步：从尾部找回「上一轮正文」的位置；中途撞到 USER 就说明断点已经被续过
        int answerIndex = -1;
        for (int i = rows.size() - 1; i >= 0; i--) {
            RunEvent row = rows.get(i);
            if (row == null || row.getEventType() == null) {
                continue;
            }
            String type = row.getEventType();
            // 末尾的 USER 事件＝上一份断点已经被续过：不允许同一份选项被点两次
            if (EventType.USER.code().equalsIgnoreCase(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "该选项已处理，请直接输入你的选择");
            }
            if (EventType.ASSISTANT.code().equalsIgnoreCase(type) && row.getContentText() != null
                    && !row.getContentText().isBlank()) {
                answerIndex = i;
                break;
            }
        }
        if (answerIndex < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上一轮没有可点选项，请直接输入你的选择");
        }
        String answer = rows.get(answerIndex).getContentText();
        /*
         * 第二步：结构化选项是权威来源——本轮正文之后紧跟一条 CHOICE 事件，正文里不再有机器块。
         * 只在「这份正文之后」找，避免把更早一轮的选项当成本轮断点。
         */
        AgentChoice choice = null;
        for (int i = answerIndex + 1; i < rows.size(); i++) {
            RunEvent row = rows.get(i);
            if (row == null || row.getEventType() == null) {
                continue;
            }
            if (!EventType.CHOICE.code().equalsIgnoreCase(row.getEventType())) {
                continue;
            }
            choice = WorkbenchChoiceJson.parseJson(row.getContentText()).orElse(null);
            if (choice == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上一轮的可点选项已失效，请直接输入你的选择");
            }
        }
        // 第三步：没有 CHOICE 事件时只认历史会话的旧机器块（确定性解析，不猜正文列表）
        String body = answer;
        if (choice == null) {
            WorkbenchChoiceJson.Parsed parsed = WorkbenchChoiceJson.parse(answer).orElse(null);
            if (parsed == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "上一轮回答没有可点选项，请直接输入你的选择");
            }
            choice = parsed.choice();
            body = parsed.text();
        }
        AgentChoice.Option option = choice.find(optionId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST,
                        "该选项已失效，请直接输入你的选择"));
        // 上一轮的用户问句：续作要把它一起交给专家，否则专家只看得到一句选项文案
        return new ChoiceAnchor(previousQuestionOf(rows, answerIndex), WorkbenchChoiceJson.strip(body), option);
    }

    /**
     * 找「上一轮正文之前的最后一条用户问句」。
     *
     * <p>续作必须把原问句带回给专家：用户点的那一下是「对上一问的回答」，
     * 只把选项文案交给模型，它会当成一句孤立的话，只能回一句「已完成作答」。
     *
     * @param rows        会话事件（按序号升序）
     * @param answerIndex 上一轮正文所在下标
     * @return 原问句；找不到（历史数据缺失）时为空串
     */
    private static String previousQuestionOf(List<RunEvent> rows, int answerIndex) {
        for (int i = answerIndex - 1; i >= 0; i--) {
            RunEvent row = rows.get(i);
            if (row == null || row.getEventType() == null) {
                continue;
            }
            if (!EventType.USER.code().equalsIgnoreCase(row.getEventType())) {
                continue;
            }
            String text = UserMessageImages.questionText(row.getContentText(), row.getSummary());
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    /**
     * 由同一位专家接着执行（不判路由、不改线路）。
     *
     * <p><b>续作入参</b>：用户点选只是「对上一问的回答」，只把选项文案交给模型，
     * 它会把这一句当成孤立发言，只能回「已完成作答」或又把问题问一遍。因此这里把
     * <b>原问句 + 上一轮终答 + 所选选项</b> 一起拼成一条明确的续作说明交给同一位专家，
     * 并附平台锁定的「接着做完、给完整方案」要求（见 {@code ChoiceContinue}）。
     *
     * <p>落库与页面气泡仍是选项文案：USER 事件、实时帧、记忆窗口都用 {@code label}，
     * 用户看到的就是「我说了这句话」。方案会话（有 ma_def_id）仍不写记忆窗口，
     * 与主链路 {@code schemeTurn} 的跳过口径一致。
     *
     * @param session  当前会话
     * @param agent    上一轮作答的专家
     * @param anchor   上一轮问句 / 终答 + 用户选中的选项（展示文案以服务端为准）
     * @param written  本轮已写事件
     * @param seq      下一条事件序号
     * @param progress 增量出口，可空
     * @return 本轮终答
     */
    private TrialTurn runChoice(RunSession session, AgentDef agent, ChoiceAnchor anchor, List<RunEvent> written,
                                int seq, TrialProgress progress) {
        long userId = session.getUserId();
        String label = anchor.option().label();
        // 只喂模型：问句 + 上一轮完整回答 + 选择 + 「接着做完」要求，专家据此给出完整方案
        String modelUser = ChoiceContinue.text(anchor.question(), anchor.answer(), label);
        // 记忆召回仍按本轮用户话（选项文案）做语义召回：它是当前真正要处理的那句话
        TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(session, label);
        // choiceEnabled=true：续作轮仍允许该专家再次让用户拍板（多步确认场景）
        TrialPlan plan = loader.loadReact(agent, label, recalled.memoryBlock(), true);
        plan.setToolArgsFacts(recalled.injectedFacts());
        memory.publishInjection(plan);
        ObsTraceScope trace = ObsTraceHolder.current();
        Long agentSpan = null;
        if (trace != null) {
            agentSpan = trace.open(SpanType.AGENT, agent.getName(), agent.getId(), null, null);
            trace.bindParent(agentSpan);
        }
        try {
            TrialTurn turn = react.execute(plan, modelUser, userId, session.getId(), progress);
            if (trace != null) {
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(ObsAttrs.withBrief(null, "执行 Agent"));
                trace.close(agentSpan, agentOk);
            }
            // 终稿写在这一位的思考 / 工具之后、文件之前，不再另补一条贴到轮末
            int next = timeline.appendParticipantTurn(session.getId(), seq, turn, written);
            // 续作轮同样可能再次需要拍板（多步确认），选项紧跟本轮正文落库
            appendChoice(session.getId(), next, written);
            // 方案会话不给记忆开写窗口：它在主链路上也被跳过，续作不能悄悄补一次写
            if (session.getMaDefId() == null) {
                memory.afterAssistant(session, turn.getAssistantText());
            }
            return turn;
        } catch (RuntimeException ex) {
            log.warn("续作执行失败 agentId={} type={} cause={}",
                    agent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "Agent 失败"), CauseDigest.of(ex)));
                trace.close(agentSpan, agentErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 批准待执行的计划：接着把已落库的那份计划串行执行完并汇总。
     *
     * <p>用户原文与已注入记忆的用户侧文本都来自登记项，不重新拼一遍——
     * 重拼会把窗口滚动摘要推进两次，同一轮被计两次摘要。
     *
     * @param sessionId 会话（必须是当前用户自己的，且确实停在待批准）
     * @return 本轮新事件与终答预览
     * @throws BizException 没有待批准计划、计划已过期，或该会话正在执行另一轮
     */
    public MessageResp approvePlan(Long sessionId) {
        RunSession session = access.require(sessionId);
        assertSendable(session);
        long userId = operatorContext.requireUserId();
        PendingPlan pending = pendingPlans.take(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有待批准的计划（可能已过期，请重新提问）"));
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        // 只有没走到 succeed / fail 就逃出时才收口；业务失败仍保持 RUNNING
        boolean settled = false;
        try {
            trace = obs.begin(session, pending.userText());
            AgentDef agent = session.getAgentId() == null ? null
                    : agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
            if (agent == null) {
                // 虚拟根是 ROUTER，本不该产生单 Agent 待批计划；兜底拒绝而不是 NPE
                throw new TrialFailure("该会话没有 Agent，无法续跑计划；请重新提问");
            }
            if (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED) {
                throw new TrialFailure("Agent 未启用");
            }
            int seq = events.nextSeq(sessionId);
            // 批准不是新的用户输入：不再落 USER 事件，直接续执行并推进后续序号。
            // 同时把这一轮镜像进内存快照（从这条 seq 起），页面才有办法在执行中途看到
            // 「第 N 步进行中」——否则批准后要一直干等接口返回，界面上只剩一个转圈按钮。
            live.beginAt(sessionId, seq);
            PlanProgressRecorder recorder = new PlanProgressRecorder(live.recorder(sessionId));
            TrialTurn turn = executePendingPlan(agent, pending, session, written, seq, recorder);
            obs.finishOk(trace);
            // 同一轮的续跑：不重复累加 message_count
            MessageResp done = succeed(session, turn, written, pending.userText(), false);
            settled = true;
            return done;
        } catch (TrialFailure ex) {
            logTrialFailure("批准计划后试跑失败", sessionId, ex, ex.getMessage());
            // 有 cause 时把脱敏摘要提到 trace 级：面板第一行就能看到真正原因
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason(), ex.getCause());
            MessageResp failed = fail(session, written, ex.getMessage(), pending.userText(), false);
            settled = true;
            return failed;
        } catch (RuntimeException ex) {
            // 批准续跑失败：带栈 + 脱敏 cause，便于区分「模型出站失败」与「装配缺失」
            log.warn("批准计划后执行失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            obs.finishError(trace, "试跑失败", null, ex);
            MessageResp failed = fail(session, written, "试跑失败", pending.userText(), false);
            settled = true;
            return failed;
        } finally {
            PromptInjectionHolder.clear();
            obs.clear();
            // 用户停止时先把快照里的过程与工作流卡片按中断终态补落库，再丢快照
            cancellationRecorder.persistIfCancelled(sessionId);
            // 本轮结束：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
            // fail() 自己抛错时错误事件可能没写上，会话会停在 RUNNING，刷新后左栏一直转
            if (!settled) {
                staleRuns.abandonIfIdle(sessionId);
            }
        }
    }

    /**
     * 取消待执行的计划：落一条 REJECTED 计划事件，本轮就此收束。
     * 没有任何步骤被执行，因此不写 assistant、不动记忆窗口。
     *
     * @param sessionId 会话
     * @return 本轮新事件（只有一条计划状态事件）
     * @throws BizException 没有待批准计划
     */
    public MessageResp rejectPlan(Long sessionId) {
        RunSession session = access.require(sessionId);
        long userId = operatorContext.requireUserId();
        PendingPlan pending = pendingPlans.take(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "没有待批准的计划（可能已过期）"));
        List<RunEvent> written = new ArrayList<>();
        int seq = events.nextSeq(sessionId);
        PlanView rejected = new PlanView(pending.planId(), PlanState.REJECTED, List.of());
        written.add(writer.append(sessionId, seq, EventType.PLAN, "计划已取消",
                PlanJson.planPayload(rejected), null, null));
        // 同一轮收束：用户轮次已在发问时计过，这里不再累加
        recordTurn(session, "", "", false);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(sessionId),
                "试跑会话 " + sessionId + " 计划已取消");
        MessageResp resp = response(session, "计划已取消", written);
        resp.setStatus(RunStatus.SUCCESS.name());
        return resp;
    }

    /**
     * 查询该会话是否有待批准计划。前端刷新后靠它恢复「批准 / 取消」按钮。
     *
     * @param sessionId 会话
     * @return 有待批准计划时为计划视图（PENDING）；否则为空
     */
    public Optional<PlanView> pendingPlan(Long sessionId) {
        return planApprovals.pendingPlan(sessionId);
    }

    /**
     * 编排根下的子计划待批准：批准并唤醒等待中的子线程。
     *
     * <p>与单 Agent 的 {@link #approvePlan(Long)} 分开：这条路径不续跑任何根侧步骤，
     * 只是把控制权还给「已规划未执行」的 P&E 子线程；根终答仍由 Router 等齐后统一汇总。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认（无新事件，根 SSE 会继续推后续增量）
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp approveChildPlan(Long sessionId, String routeKey) {
        return planApprovals.approveChildPlan(sessionId, routeKey);
    }

    /**
     * 编排根下的子计划待批准：拒绝。该子以失败占位继续，其它子不受影响。
     *
     * @param sessionId 根会话
     * @param routeKey  待批子的路由键；空表示当前唯一待批
     * @return 轻量确认
     * @throws BizException 没有待批子计划、已失效，或不属于当前用户
     */
    public MessageResp rejectChildPlan(Long sessionId, String routeKey) {
        return planApprovals.rejectChildPlan(sessionId, routeKey);
    }

    /**
     * 查当前待批的子计划。刷新页面后前端靠它恢复「批准 / 拒绝」按钮；
     * 与根 SSE 的 {@code ma_child_plan_pending} 载荷同构。
     *
     * @param sessionId 根会话
     * @return 有待批子时为其视图；否则为空
     */
    public Optional<ChildPlanPendingView> pendingChildPlan(Long sessionId) {
        return planApprovals.pendingChildPlan(sessionId);
    }

    /**
     * 一轮发消息的外层闸门：归属校验 → 幂等占位 → 执行 → 回写终态。
     *
     * <p>幂等放在这里而不是 Controller：SSE 路径与同步路径共用同一处判定，
     * 且「占位 → 执行 → 留终态」必须紧贴真正跑模型的那一段，中间不能插入别的写库动作。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param progress  增量出口，可空（同步 send）
     * @param idemKey   客户端幂等键，可空
     * @return 本轮结果，或命中重放时的等价结果
     * @throws BizException 会话不可发、幂等键冲突，或首轮仍在执行
     */
    private MessageResp executeTurn(Long sessionId, String content, List<String> images, TrialProgress progress,
                                    String idemKey) {
        return executeTurn(sessionId, content, images, null, null, progress, idemKey);
    }

    /**
     * 一轮发消息的外层闸门：归属校验 → 幂等占位 → 执行 → 回写终态。
     *
     * <p>没有文件时，交给幂等闸门的正文与原来逐字相同，避免同键重放被误判成另一条内容。
     * 有文件时把文件名和 data URI 按长度前缀拼进指纹，换一个文件不能命中上一次的结果。
     *
     * @param sessionId 会话
     * @param content   用户输入
     * @param images    图片，可空
     * @param files     非图片文件，可空
     * @param order     添加顺序，可空
     * @param progress  增量出口，可空（同步 send）
     * @param idemKey   客户端幂等键，可空
     * @return 本轮结果，或命中重放时的等价结果
     * @throws BizException 会话不可发、幂等键冲突，或首轮仍在执行
     */
    private MessageResp executeTurn(Long sessionId, String content, List<String> images,
                                    List<UserMessageFiles.NamedPayload> files, List<String> order,
                                    TrialProgress progress, String idemKey) {
        RunSession session = access.require(sessionId);
        // 仅用户取消和没写完就被打断的一轮不可再发。已经写完却被启动收口标成取消的，按仍可继续。
        assertSendable(session);
        // 幂等闸门放在归属校验之后：别人的会话不该在幂等表里留下占位行
        TrialIdemGuard guard = idempotency.begin(sessionId, idemKey, fingerprintContent(content, files), images);
        if (guard.hasReplay()) {
            // 同键同内容已跑完：不插消息、不调模型、不写流水，直接返回等价结果
            return guard.replay();
        }
        // 本轮幂等键进计量上下文：同一轮若被重复执行，账本会拿到同一批 dedupe_key
        MeterTurnHolder.begin(guard.managed() ? guard.idemKey() : null);
        try {
            MessageResp resp = runTurn(session, content, images, files, order, progress);
            // 成功与业务失败都是结论，必须落终态；否则同键重试会被判成「进行中」
            idempotency.complete(guard, resp);
            return resp;
        } catch (RuntimeException ex) {
            // 未形成结论就抛出（会话正忙、图片非法等）：释放占位，允许同键重试
            idempotency.release(guard);
            throw ex;
        } finally {
            // 线程池复用：必须清掉本轮 turnKey，避免污染下一轮账本去重键
            MeterTurnHolder.clear();
        }
    }

    /**
     * 真正跑一轮：单飞 → 落用户事件 → 按运行模式分派 → 落助手与收尾。
     *
     * @param session  已校验归属的会话（幂等闸门已通过）
     * @param content  用户输入
     * @param images   图片，可空
     * @param files    非图片文件，可空。不进视觉模型
     * @param order    图和文件的添加顺序，可空
     * @param progress 增量出口，可空
     * @return 本轮结果
     */
    private MessageResp runTurn(RunSession session, String content, List<String> images,
                                List<UserMessageFiles.NamedPayload> files, List<String> order,
                                TrialProgress progress) {
        Long sessionId = session.getId();
        if (!flight.tryStart(sessionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该会话正在执行");
        }
        // 图片边界校验放在最外层：超量/超大属于请求本身不合法，应在建 trace、写事件之前就拒掉
        List<String> accepted = VisionOcrTexts.normalize(images);
        List<RunEvent> written = new ArrayList<>();
        ObsTraceScope trace = null;
        // 结构化选项：只有工作台会话才允许本轮出 chips；调试 / 试跑的口径与接线保持不变
        if (EntryChannel.isWorkbench(session.getEntryChannel())) {
            ChoiceCaptureHolder.begin();
        }
        // 正常成功 / 业务失败都算收尾；只有没走到那两支就逃出才把会话收成取消
        boolean settled = false;
        // 只发图时左栏预览用短标签；失败收口也要看得见，不能写成空预览
        String titleSeed = content == null ? "" : content;
        try {
            // 新提问即放弃上一条待批准的计划：旧计划基于旧上下文，继续执行会张冠李戴
            pendingPlans.drop(sessionId);
            // 同理清掉上一轮遗留的子计划待批与批准槽：否则排队中的 P&E 子会被旧槽挡住
            childApprovals.rejectAll(sessionId);
            // 先落 RUNNING，页面在本轮结束前就能看到进行中的调用
            trace = obs.begin(session, content);
            // 虚拟根没有 Agent：agent 可空，但必须有方案（下面 resolve 会校验）
            AgentDef agent = session.getAgentId() == null ? null
                    : agents.findById(session.getAgentId())
                    .orElseThrow(() -> new TrialFailure("Agent 不存在"));
            if (agent != null && (agent.getStatus() == null || agent.getStatus() != AgentStatus.ENABLED)) {
                throw new TrialFailure("Agent 未启用");
            }
            // 执行读源：有 ma_def_id 一律读方案；绑根按「当前启用方案」实时解析；
            // 都没有才回落 Agent 旧配置（在 run() 里写可见提醒）
            MaRunSpec spec = maSpecs.resolve(session, agent);
            // 工作台虚拟根：方案没配判路由模型时回落平台 ROUTER / CHAT 槽；
            // 调试会话（DEBUG）不经过这一步，/api/runtime/trial/** 语义保持原样
            spec = applyWorkbenchJudge(session, spec);
            if (agent == null && spec == null) {
                throw new TrialFailure("该会话既没有 Agent 也没有可用的编排方案，无法执行");
            }
            boolean entryRouter = EntryPreprocess.forEntry(spec);
            // 非入口的虚拟根没有视觉槽：仍明确拒绝。入口走预处理，失败写在步骤上而不是这里吞掉。
            if (!accepted.isEmpty() && session.getAgentId() == null && !entryRouter) {
                throw new TrialFailure("虚拟根试跑暂不支持图片输入，请改用文字提问");
            }
            // 文件先校验：类型不对或清单放不进摘要时，不写事件，也不为了文件去跑视觉模型。
            // 只发文件没有图片，下面的视觉分支不会进，因此不会被「没有视觉模型」拦住。
            List<UserMessageFiles.Draft> acceptedFiles = UserMessageFiles.normalize(files);
            // 图和文件各有单列上限，这里再卡合计，避免 9 张图再加 9 个文件
            if (accepted.size() + acceptedFiles.size() > UserMessageFiles.MAX_ATTACHMENTS) {
                throw new TrialFailure("一次最多发送 " + UserMessageFiles.MAX_ATTACHMENTS + " 个附件");
            }
            UserMessageFiles.assertFits(sessionId, order, accepted.size(), acceptedFiles);
            // 前置拦截：带图却没有 VISION 槽时，必须在写任何事件之前就用明确文案拒绝，
            // 否则用户会看到一条莫名其妙的「模型调用失败」。文件不参与这次校验。
            // 入口不在这里拦：预处理步骤要能看见失败原因。
            String visionNote = null;
            if (!accepted.isEmpty() && !entryRouter) {
                if (progress != null) {
                    // OCR 是整轮里最慢的一段（视觉模型首字延迟高）：先推一个阶段，
                    // 否则用户会盯着「任务规划中」不知道后端在做什么
                    progress.phase("vision");
                }
                visionNote = ocrNote(agent, accepted, session);
                if (progress != null) {
                    // 识别结果立刻推给前端：用户能先确认「这张图提对了没有」，再看主模型怎么答
                    progress.vision(visionNote);
                }
            }
            int seq = events.nextSeq(sessionId);
            int userSeq = seq;
            // 识别成功后再落盘：失败的轮次不留半截文件。地址写进用户事件摘要，正文仍是原话。
            // 纯图片继续写 ahimg，已有回看不改口径。一旦带了文件，改写 ahfile，图和文件按添加顺序排。
            String spoken = content == null ? "" : content;
            String imageManifest = null;
            List<UserMessageFiles.Ref> storedFiles = List.of();
            if (acceptedFiles.isEmpty()) {
                if (!accepted.isEmpty()) {
                    imageManifest = UserMessageImages.manifest(userImages.persist(sessionId, accepted));
                }
            } else {
                List<String> imageUrls = accepted.isEmpty()
                        ? List.of()
                        : userImages.persist(sessionId, accepted);
                storedFiles = userFiles.persist(sessionId, acceptedFiles);
                imageManifest = UserMessageFiles.manifest(order, imageUrls, storedFiles);
            }
            // 只发图或只发文件时左栏不能是空白预览；气泡本身不写这个短标签
            if (spoken.isBlank() && imageManifest != null) {
                titleSeed = accepted.isEmpty()
                        ? UserMessageFiles.FILE_ONLY_LABEL
                        : UserMessageImages.IMAGE_ONLY_LABEL;
            }
            /*
             * 工作流在对话里停等：这一句是对询问节点的回复，不是新的用户消息。
             * 先定位等待中的运行；续跑成功就不落 USER 事件，时间线里仍只有最初那句提问，
             * 选择结果由投影写进询问节点。没有等待中的运行 / 续跑未命中时，仍按普通新问题落用户消息。
             * 只在工作台会话尝试；调试会话没有这条续跑语义。
             */
            Optional<WorkflowRun> waitingRun = EntryChannel.isWorkbench(session.getEntryChannel())
                    && !spoken.isBlank()
                    ? workflowReplies.waitingRun(sessionId)
                    : Optional.empty();
            /*
             * 方案在对话里停等（子专家询问）：同样把这一句当回复而不是新问题。
             * 与工作流等待互斥；带附件时按普通新问题处理，不把附件静默丢掉。
             * 续跑成功后不落 USER 事件，选项清掉、选择写进该子专家卡。
             */
            Optional<MaPauseSnapshot> waitingMa = EntryChannel.isWorkbench(session.getEntryChannel())
                    && !spoken.isBlank() && accepted.isEmpty() && acceptedFiles.isEmpty()
                    ? maReplies.waitingRun(sessionId)
                    : Optional.empty();
            // 同一批增量同时镜像到内存快照：刷新页面后前端靠它继续看「边跑边写」，不必等整轮落库。
            // 只在有真实出站时镜像：progress 为 null 是同步 send 路径，collector 会走「结束时一次性补发」
            // 的分支，塞一个非空 progress 进去会改变那条路径的行为，属于不必要的副作用。
            TrialProgress mirrored = progress;
            if ((waitingRun.isPresent() || waitingMa.isPresent()) && progress != null) {
                /*
                 * 续跑轮没有新的用户事件：beginAt 只对齐序号，避免内存快照多出一个用户气泡。
                 * 方案续跑会先把「已答复」的 CHOICE 写在 userSeq 上，再开始跑模型；
                 * 内存增量必须从 userSeq+1 起，否则刷新合并时同序号会覆盖掉这条回复。
                 */
                live.beginAt(sessionId, waitingMa.isPresent() ? userSeq + 1 : userSeq);
                mirrored = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            }
            if (waitingMa.isPresent()) {
                Optional<MaReplyResumer.Resumed> resumed = maReplies.resumeWaiting(
                        session, waitingMa.get(), spoken, mirrored);
                if (resumed.isPresent()) {
                    written.addAll(resumed.get().events());
                    obs.finishOk(trace);
                    MessageResp done = succeed(session, resumed.get().turn(), written, titleSeed);
                    settled = true;
                    return done;
                }
                // 并发收口：这条等待已不可续，退回普通新问题路径，用户这句话不丢
                live.clear(sessionId);
                mirrored = progress;
            }
            if (waitingRun.isPresent()) {
                Optional<WorkflowReplyResumer.Resumed> resumed = workflowReplies.resumeWaiting(
                        sessionId, waitingRun.get(), spoken, session.getUserId(), mirrored);
                if (resumed.isPresent()) {
                    WorkflowReplyResumer.Resumed result = resumed.get();
                    written.addAll(result.events());
                    obs.finishOk(trace);
                    MessageResp done = succeed(session, result.turn(), written, titleSeed);
                    settled = true;
                    return done;
                }
                /*
                 * 并发收口（刚被取消 / 别人已回复）：不能把用户这句话丢掉。
                 * 退回普通新问题路径补落 USER 事件，并把快照重置成带用户事件的这一轮。
                 */
                live.clear(sessionId);
                mirrored = progress;
            }
            // 无附件仍走「摘要=正文」的原入口，避免把纯文本提问改成另一条重载。
            if (imageManifest == null) {
                written.add(writer.append(sessionId, seq++, EventType.USER, spoken, null, null));
            } else {
                written.add(writer.append(sessionId, seq++, EventType.USER, imageManifest, spoken, null, null));
            }
            // OCR 作为独立事件下发：时间线上要能看出「这张图提了哪些字」，
            // 否则用户只看到模型答了些什么，无法判断是识别错了还是模型答错了
            if (visionNote != null && !visionNote.isBlank()) {
                written.add(writer.append(sessionId, seq++, EventType.VISION, visionNote, null, null));
            }
            if (progress != null) {
                // 快照从用户事件那一序号起，附图清单与识别结果都带上：
                // 刷新后 SSE 已断，快照会按序号盖住已落库的用户事件，清单必须在快照里。
                live.begin(sessionId, userSeq, spoken, imageManifest, visionNote);
                mirrored = TrialLiveBuffer.tee(progress, live.recorder(sessionId));
            }
            // 气泡和事件正文仍是用户原话。管理端把附件正文拼进送给模型的那一版，不铺进气泡。
            // 入口（首页 / MA 运行入口）有附件时先预处理，再把识别正文交给意图分流。
            String modelContent = spoken;
            String memoryText = spoken;
            boolean adminFiles = !acceptedFiles.isEmpty() && !entryRouter;
            if (entryRouter && EntryPreprocess.needed(accepted, storedFiles)) {
                try {
                    EntryPreprocess.Result prepared = entryPreprocess.run(
                            spec, session, spoken, accepted, storedFiles, mirrored);
                    // 落库正文多带一份 material：跨轮回放要从这里把票面 / 抽字正文拼回用户轮
                    written.add(writer.append(sessionId, seq++, EventType.PREPROCESS,
                            PreprocessJson.summaryOf(prepared.view()),
                            PreprocessJson.payload(prepared.view(), prepared.materialText()), null, null));
                    modelContent = prepared.intentText();
                    memoryText = prepared.memoryText();
                    // 素材要一路跟到被分到的专家：判路由会把 sub_query 写成一句概述，
                    // 若不在子输入里单独补回，票面 / 抽字正文就随问句一起被替换掉了
                    EntryMaterialHolder.set(prepared.materialText());
                    visionNote = null;
                } catch (TrialFailure ex) {
                    PreprocessView failed = PreprocessView.failed(List.of(), ex.getMessage());
                    entryPreprocess.emit(mirrored, failed);
                    written.add(writer.append(sessionId, seq++, EventType.PREPROCESS,
                            PreprocessJson.summaryOf(failed), PreprocessJson.payload(failed), null, null));
                    throw ex;
                }
            } else if (adminFiles) {
                TrialAttachmentReader.Outcome read = attachmentReader.apply(
                        agent, session, spoken, storedFiles, mirrored);
                modelContent = read.modelContent();
                if (!read.notices().isEmpty()) {
                    written.add(writer.append(sessionId, seq++, EventType.SYSTEM,
                            String.join("\n", read.notices()), null, null));
                }
            } else if (spoken.isBlank() && accepted.isEmpty() && !acceptedFiles.isEmpty()) {
                modelContent = UserMessageFiles.FILE_ONLY_LABEL;
                memoryText = modelContent;
            }
            if (spoken.isBlank() && accepted.isEmpty() && !acceptedFiles.isEmpty()
                    && (modelContent == null || modelContent.isBlank())) {
                modelContent = UserMessageFiles.FILE_ONLY_LABEL;
            }
            TrialTurn turn = run(agent, spec, modelContent, memoryText, visionNote, session, written, seq, mirrored);
            obs.finishOk(trace);
            MessageResp done = succeed(session, turn, written, titleSeed);
            settled = true;
            return done;        } catch (TrialFailure ex) {
            // 用户点停止：终态是「已中断」，不按失败刷一条试跑失败
            if ("cancelled".equals(ex.getFinishReason())) {
                log.info("运行已中断 sessionId={}", sessionId);
            } else {
                // TrialFailure 映射处：文案已是人话，挂了 cause 时补一次全栈，
                // 否则「连通测成功、试跑失败」在控制台仍只剩一句业务短句
                logTrialFailure("试跑失败", sessionId, ex, ex.getMessage());
            }
            obs.finishError(trace, ex.getMessage(), ex.getFinishReason(), ex.getCause());
            MessageResp failed = fail(session, written, ex.getMessage(), titleSeed);
            settled = true;
            return failed;
        } catch (RuntimeException ex) {
            // 同步路径兜底 catch：带栈打，保证「再传图失败」时控制台有完整栈
            log.warn("试跑失败 sessionId={} type={} cause={}",
                    sessionId, ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            obs.finishError(trace, "试跑失败", null, ex);
            MessageResp failed = fail(session, written, "试跑失败", titleSeed);
            settled = true;
            return failed;
        } finally {
            PromptInjectionHolder.clear();
            // 采集槽必须清：池线程复用会把上一轮的槽带到下一轮，可能凭空多出一次整理出站
            ChoiceCaptureHolder.clear();
            // 入口素材同理：不清就会把上一轮的票面 / 抽字正文带进下一个人的会话
            EntryMaterialHolder.clear();
            obs.clear();
            // 用户停止时先把快照里的过程与工作流卡片按中断终态补落库，再丢快照
            cancellationRecorder.persistIfCancelled(sessionId);
            // 整轮结束（无论成败）：内存快照完成使命，权威数据是刚落库的事件
            live.clear(sessionId);
            flight.finish(sessionId);
            // 请求被掐断、fail 写库再抛：错误事件可能没落上，不收口的话刷新后会一直续订
            if (!settled) {
                staleRuns.abandonIfIdle(sessionId);
            }
        }
    }

    /**
     * 多模态预处理：把图片交给 VISION 槽模型提文字，返回可插进用户侧的识别结果。
     *
     * <p>为什么在进入 ReAct 之前做：主链路（ReAct / Router / 计划执行）只认纯文本，
     * OCR 收口在这里，三套编排都不必各自处理图片。
     *
     * <p>失败策略：VISION 槽缺失 → 抛业务异常直接拒绝（前端已置灰上传，这里是最后一道闸）；
     * 识别本身失败 → 抛 {@link TrialFailure}，不带着空图继续让主模型盲答。
     *
     * @param agent   已启用的 Agent 定义
     * @param images  已校验的图片列表
     * @param session 当前会话
     * @return 汇总后的 OCR 文本，永不为 null
     * @throws TrialFailure 未绑定 VISION 槽，或识别失败
     */
    private String ocrNote(AgentDef agent, List<String> images, RunSession session) {
        Long visionInstanceId = loader.requireVisionModel(agent.getId());
        MeterScope scope = MeterScope.console(agent.getId(), visionInstanceId,
                session.getId(), operatorContext.requireUserId());
        // 视觉请求是阻塞的，本方法在请求线程上跑：整个试跑本来就是同步语义，这里不必再切线程。
        // 失败不在这里打日志：VisionOcrExecutor 已带 Throwable 打过一次全栈，
        // 外层 TrialFailure 分支也会再打一次；此处重复打同一份栈只会把控制台刷乱
        String text = vision.extract(images, visionInstanceId, scope);
        if (text == null || text.isBlank()) {
            throw new TrialFailure("未能从图片中提取到文字，请换一张更清晰的图片重试");
        }
        return text;
    }

    private TrialTurn run(AgentDef agent, MaRunSpec spec, String content, String memoryText, String ocrText,
                          RunSession session, List<RunEvent> written, int seq, TrialProgress progress) {
        long userId = session.getUserId();
        // 方案会话（spec 非空）以方案自己为根：没有 Agent 记忆策略，不给它开写窗口
        // （afterAssistant 仍跳过）；但本轮仍要拼会话前文，否则「上一轮问了技术栈、这轮答 1」
        // 会让入口判定与专家都只看得到一句孤立的「1」。只读预览不写 Redis、不做摘要。
        // 历史绑根会话即便还带 agentId 也按此口径。
        boolean schemeTurn = spec != null;
        // 没有附件时仍走原来的两参准备，显式记住和窗口用同一段原话。
        // 有附件正文时窗口留下全文，显式记住只看用户打的字。
        String modelUser;
        if (schemeTurn || agent == null) {
            modelUser = memory.previewUser(session, content);
        } else if (memoryText == null || memoryText.equals(content)) {
            modelUser = memory.prepareUser(session, content);
        } else {
            modelUser = memory.prepareUser(session, content, memoryText);
        }
        // 兜底：预览拿不到内容时退回本轮原文。宁可少一段前文，也不能把空值送给入口判定与专家
        if (modelUser == null || modelUser.isBlank()) {
            modelUser = content;
        }
        // 多模态：识别结果只拼到「送给模型的那一版」，用户事件与标题仍保持原文
        String query = VisionOcrTexts.composeQuery(ocrText, content);
        modelUser = VisionOcrTexts.applyToModelUser(modelUser, ocrText, content);
        // 运行模式：方案会话固定 ROUTER（方案只有一层编排语义），其余按 Agent / 会话快照
        String runMode = schemeTurn ? AgentRunMode.ROUTER.name()
                : (agent != null ? agent.getRunMode() : session.getRunMode());
        if (AgentRunMode.ROUTER.name().equals(runMode)) {
            if (spec == null) {
                // 绑根但没有启用方案：回落 Agent 旧配置，必须在时间线留痕，避免用户以为读的是方案
                written.add(writer.append(session.getId(), seq++, EventType.SYSTEM,
                        "该编排根未绑定启用中的方案，本轮按 Agent 侧旧配置执行；到「多智能体」为它启用方案后，"
                                + "执行读源才会切到方案（模板 / 候选 / 判路由模型）", null, null));
            }
            // 方案就是根：不再合成 AgentDef 壳，执行器直接以方案（spec）为根跑；
            // 只有旧 Agent 试跑路径（spec 为空）才把真实 Agent 传进去。
            AgentDef root = schemeTurn ? null : agent;
            // 路由判定也要看得到图片里的文字，否则「这张发票什么时候到期」会判不出意图。
            // 可点选项开关只对工作台开：调试 / 试跑轮的提示词与旧口径逐字一致，不注入选项块说明。
            // 走线程上下文而不是加 execute 参数：那是三处入口共用的公开签名。
            LeafChoiceHolder.set(EntryChannel.isWorkbench(session.getEntryChannel()));
            RouterTrialExecutor.RoutedTurn routed;
            try {
                routed = router.execute(root, spec, query, modelUser, userId,
                        session.getId(), progress, workbenchHint(session, modelUser));
            } catch (MaPauseSignal pause) {
                /*
                 * 方案停在某位子专家询问处：落等待态并把已跑成员补写成标准事件。
                 * 这不是失败——本轮以等待说明收尾，下一句回复由 maReplies 直接续跑。
                 */
                return maReplies.pauseRun(session.getId(), pause.state(), seq, written);
            } finally {
                LeafChoiceHolder.clear();
            }
            // 刀3：单意图成功轮写回沿用线路，多意图轮清空；失败 / 取消走不到这里，不改旧值
            applyStickyRoute(session, routed);
            // 按子分段落库：决策块 + 每子「路由到」+ 该子 traces，禁止拼成单条时间线
            seq = timeline.appendRouterSegments(session.getId(), seq, routed, written);
            seq = appendNotices(session.getId(), seq, routed.notices(), written);
            /*
             * 各参与者的终稿已经写在自己的分段里。这里只补「方案结论」之后的那一段：
             * 真汇总是汇总模型的正文，多问并行是整轮收口（各方案要点，不是拼接稿）。
             * 它挂在刚落下的结论身份之后，不会再贴回前面的专家卡或最后一个车间。
             */
            if (TrialTimelineWriter.owesRoundAssistant(routed)) {
                boolean expanded = routed.segments() != null && !routed.segments().isEmpty();
                // 子段已经各自记过用量时，汇总正文只补文字，不再把合计 token 写第二遍
                written.add(timeline.assistant(session.getId(), seq, routed.turn(), !expanded));
                seq++;
            }
            // 选项（若本轮收口出了结构）紧跟助手正文落库：chips 在气泡下方，刷新后也按同一顺序回放
            appendChoice(session.getId(), seq, written);
            if (!schemeTurn && agent != null) {
                memory.afterAssistant(session, routed.turn().getAssistantText());
            }
            return routed.turn();
        }
        if (agent == null) {
            // 方案试跑只支持一层编排；其它模式没有 Agent 时无法装配，直接中文失败
            throw new TrialFailure("方案试跑只支持 ROUTER 编排，请检查方案模板与绑定关系");
        }
        if (AgentRunMode.PLAN_AND_EXECUTE.name().equals(agent.getRunMode())) {
            return runPlan(agent, query, modelUser, session, written, seq, progress);
        }
        // 记忆段在装配时进链（位于工具目录之前），不再往装配结果尾部追加。
        // 传本轮 query（含 OCR 文本）：LONG 走语义召回，取不到就自动回落 MySQL Top N
        TrialMemoryCoordinator.Augmented recalled = memory.recallDurable(session, query);
        TrialPlan plan = loader.loadReact(agent, query, recalled.memoryBlock());
        plan.setToolArgsFacts(recalled.injectedFacts());
        memory.publishInjection(plan);
        seq = appendNotices(session.getId(), seq, plan.getNotices(), written);
        // 纯 ReAct 挂一个 AGENT 根，模型和工具都做它的子步骤
        ObsTraceScope trace = ObsTraceHolder.current();
        Long agentSpan = null;
        if (trace != null) {
            agentSpan = trace.open(SpanType.AGENT, agent.getName(), agent.getId(), null, null);
            trace.bindParent(agentSpan);
        }
        try {
            TrialTurn turn = react.execute(plan, modelUser, userId, session.getId(), progress);
            if (trace != null) {
                // AGENT 只写短摘要，不落长输入输出
                SpanClose agentOk = new SpanClose();
                agentOk.setStatus(ObsStatus.OK);
                agentOk.setOutputText("执行完成");
                agentOk.setAttrsJson(ObsAttrs.withBrief(null, "执行 Agent"));
                trace.close(agentSpan, agentOk);
            }
            // 单专家没有分段：终稿与思考、工具、文件写在同一段，不再在过程之后另补一条
            seq = timeline.appendParticipantTurn(session.getId(), seq, turn, written);
            memory.afterAssistant(session, turn.getAssistantText());
            return turn;
        } catch (RuntimeException ex) {
            // AGENT 根步骤失败：先打全栈，再带脱敏 cause 关 span，页面与日志两侧都能定位
            log.warn("Agent 执行失败 agentId={} type={} cause={}",
                    agent.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            if (trace != null) {
                SpanClose agentErr = new SpanClose();
                agentErr.setStatus(ObsStatus.ERROR);
                agentErr.setErrorSummary(ex.getMessage());
                agentErr.setOutputText(ex.getMessage());
                agentErr.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, "Agent 失败"), CauseDigest.of(ex)));
                trace.close(agentSpan, agentErr);
            }
            throw ex;
        } finally {
            if (trace != null) {
                trace.bindParent(null);
            }
        }
    }

    /**
     * 工作台方案会话的判路由模型回落。
     *
     * <p>方案自己配了判路由模型就尊重方案；没配才用平台 ROUTER → CHAT 槽。
     * 只对 {@code entry_channel=WORKBENCH} 的会话生效，调试入口的
     * 「未配置 → 使用第一条候选」行为完全不变。
     *
     * @param session 当前会话
     * @param spec    方案执行来源，可空
     * @return 可能补了判路由模型的执行来源
     */
    private MaRunSpec applyWorkbenchJudge(RunSession session, MaRunSpec spec) {
        if (spec == null || spec.judgeModelInstanceId() != null) {
            return spec;
        }
        if (!EntryChannel.isWorkbench(session.getEntryChannel())) {
            return spec;
        }
        return spec.withJudgeModel(reception.platformJudgeModel().orElse(null));
    }

    /**
     * 组装本轮的线路提示（刀3）。所属域：runtime。
     *
     * <p>只有工作台正式对话会返回非空：
     * <ul>
     *   <li>调试会话返回 {@code null}，判路由链路与刀3 之前完全一致；</li>
     *   <li>有一次性强制标记时消费掉它（{@link WorkbenchRouteForceRegistry#take(Long)}），
     *       保证「手动换线」只作用一轮；</li>
     *   <li>没有强制标记但有沿用线路时，只把「优先沿用」注入判路由提示词。</li>
     * </ul>
     *
     * @param session   当前会话
     * @param judgeInput 判路由模型实际读到的用户侧文本（含会话前文）；与专家作答同一份
     * @return 线路提示；无提示时为空
     */
    private RouteHint workbenchHint(RunSession session, String judgeInput) {
        if (!EntryChannel.isWorkbench(session.getEntryChannel())) {
            return null;
        }
        String sticky = session.getStickyRouteKey();
        String forced = routeForce.take(session.getId()).orElse(null);
        // 没有沿用线路也没有强制标记：仍把前文交给判定，否则「1」会被判成诉求不清
        if (forced == null) {
            return RouteHint.sticky(sticky, judgeInput);
        }
        return RouteHint.forced(sticky, forced, judgeInput);
    }

    /**
     * 回写线路粘性（刀3）。所属域：runtime。
     *
     * <p>边界：
     * <ul>
     *   <li>只有工作台会话写；调试会话不因试跑而改变线路；</li>
     *   <li>待批准（本轮没有终答）不写：这一轮还没结束，不能把「已定线」提前落库；</li>
     *   <li>{@code sticky == null}（固定模板 / 评审环 / 竞争选优）保持原值不动。</li>
     * </ul>
     *
     * @param session 当前会话
     * @param routed  本轮路由结果
     */
    private void applyStickyRoute(RunSession session, RouterTrialExecutor.RoutedTurn routed) {
        RouteSticky sticky = routed.sticky();
        if (sticky == null || !EntryChannel.isWorkbench(session.getEntryChannel())) {
            return;
        }
        if (routed.turn() != null && routed.turn().isAssistantSuppressed()) {
            return;
        }
        sessions.updateStickyRoute(session.getId(), sticky.routeKey(), sticky.agentId(), session.getUserId());
        // 同步本地对象：同一请求内后续若再读会话，不会拿到旧线路
        session.setStickyRouteKey(sticky.routeKey());
        session.setStickyAgentId(sticky.agentId());
    }

    private int appendNotices(Long sessionId, int seq, List<String> notices, List<RunEvent> written) {
        if (notices == null) {
            return seq;
        }
        for (String notice : notices) {
            written.add(writer.append(sessionId, seq++, EventType.SYSTEM, notice, null, null));
        }
        return seq;
    }

    /**
     * 把本轮收口的结构化选项写成 {@link EventType#CHOICE} 事件。
     *
     * <p>为什么要单独落一条事件（而不是塞进助手正文）：正文是给人读的 Markdown，
     * 机器结构一旦混进去就会在页面上露出裸 JSON；事件化之后前端只按它渲染 chips，
     * 刷新与断点续作也都能拿到同一份服务端权威选项。
     *
     * <p>边界：本轮没有装配选项能力（调试 / 多专家 / 方案汇总 / 工作流目标）或模型与后端整理
     * 都没给出合法结构时，什么都不写——宁可不显示 chip 栏，也不编造一组按钮。
     *
     * @param sessionId 会话主键
     * @param seq       事件序号（紧跟本轮 ASSISTANT 之后）
     * @param written   本轮已写事件收集器
     */
    private void appendChoice(Long sessionId, int seq, List<RunEvent> written) {
        ChoiceCapture capture = ChoiceCaptureHolder.current();
        if (capture == null || !capture.isEligible()) {
            return;
        }
        AgentChoice choice = capture.choice();
        if (choice == null) {
            return;
        }
        RunEvent event = timeline.choice(sessionId, seq, choice);
        if (event != null) {
            written.add(event);
        }
    }


    /**
     * 计划执行模式的一轮：规划 →（可选）停住等批准 → 串行执行 → 汇总终答。
     *
     * <p>「停住等批准」只把计划落库并把上下文留在 {@link PlanPendingRegistry}，本轮以
     * assistantSuppressed 结束：不写空助手事件、不推进记忆窗口。用户点批准后由
     * {@link #approvePlan(Long)} 续跑，点取消则落一条 REJECTED 计划事件。
     *
     * @param agent     已启用的 PLAN_AND_EXECUTE Agent
     * @param content   用户原文
     * @param modelUser 已注入记忆的用户侧文本（规划与执行复用，避免摘要被推进两次）
     * @param session   当前会话
     * @param written   本轮已写事件，计划类事件追加进去
     * @param seq       下一条事件序号
     * @param progress  增量出口，可空
     * @return 终答；待批准时为空正文且 assistantSuppressed=true
     */
    private TrialTurn runPlan(AgentDef agent, String content, String modelUser, RunSession session,
                              List<RunEvent> written, int seq, TrialProgress progress) {
        // 计划类增量既要出站也要落库：用旁路把 plan / step 按顺序收集，整轮结束统一写入
        PlanProgressRecorder recorder = new PlanProgressRecorder(progress);
        PlanTrialExecutor.PlannedTurn planned;
        try {
            planned = planRunner.plan(agent, content, modelUser, session.getUserId(), session.getId(), recorder);
        } catch (RuntimeException ex) {
            // 规划失败也要把已产生的帧写掉，否则时间线上看不到「为什么没有计划」
            log.warn("计划阶段失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
        seq = appendNotices(session.getId(), seq, planned.plan().getNotices(), written);

        PendingPlan pending = new PendingPlan(planned.planId(), session.getId(), agent.getId(), session.getUserId(),
                content, modelUser, planned.steps(), LocalDateTime.now());
        if (planned.state() == PlanState.PENDING) {
            // 只登记不执行：把复用所需的上下文留给批准接口，本轮到此为止
            pendingPlans.put(pending);
            TrialTurn waiting = new TrialTurn();
            waiting.setAssistantSuppressed(true);
            return waiting;
        }
        return executePendingPlan(agent, pending, session, written, seq, recorder);
    }

    /**
     * 按已确定的计划串行执行并汇总。自动执行与「用户批准」两条路径共用这里。
     *
     * <p>失败时也要把已产生的计划/步骤帧写掉：时间线必须能看出「计划执行到第几步失败」，
     * 只留一条 error 会让用户无从判断进度。
     *
     * @param agent      Agent 定义
     * @param pending    待执行计划（含复用所需上下文）
     * @param session    当前会话
     * @param written    本轮已写事件
     * @param seq        下一条事件序号
     * @param recorder   计划类增量旁路；批准路径传入新实例
     * @return 终答
     */
    private TrialTurn executePendingPlan(AgentDef agent, PendingPlan pending, RunSession session,
                                          List<RunEvent> written, int seq, PlanProgressRecorder recorder) {
        PlanTrialExecutor.ExecutedTurn executed;
        try {
            executed = planRunner.execute(agent, pending, recorder);
        } catch (RuntimeException ex) {
            // 失败也要落计划/步骤帧：时间线要能看出「执行到第几步、失败在哪」
            log.warn("计划执行阶段失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
            throw ex;
        }
        seq = timeline.appendPlanFrames(session.getId(), seq, recorder.drain(), written);
        // 计划模式的思考/工具已由 recorder 按发生顺序逐条登记并随帧落库，
        // 这里不能再走 appendTraces：那是整轮合并后的快照，再写一遍会重复，
        // 且会把所有步骤的推理堆到全部步骤之后，破坏「第 N 步 → 该步思考」的顺序。
        written.add(timeline.assistant(session.getId(), seq, executed.turn()));
        memory.afterAssistant(session, executed.turn().getAssistantText());
        return executed.turn();
    }


    private MessageResp succeed(RunSession session, TrialTurn turn, List<RunEvent> written, String userText) {
        return succeed(session, turn, written, userText, true);
    }

    /**
     * @param advance false 表示同一轮的续跑（批准计划），不重复累加轮次
     */
    private MessageResp succeed(RunSession session, TrialTurn turn, List<RunEvent> written, String userText,
                                boolean advance) {
        // 库里的会话继续 RUNNING。响应 status 只表示这一轮成功，供气泡使用
        recordTurn(session, userText, "", advance);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(session.getId()),
                "试跑会话 " + session.getId() + " 本轮完成");
        // 首轮问答都已落库，交给后台按问答内容起个短标题；成功与否都不影响本轮响应。
        // 禁止在这里用首问截断先写 session_title：一旦有值，异步生成会因「已有标题」永久跳过。
        maybeGenerateTitle(session, userText, turn);
        // 待批准：本轮没有助手正文，预览给一句可读说明，不把空串写进左栏。
        // 可点选项的机器块不能进左栏预览：列表里出现一段 ```agenthub-choice 只会让人以为是乱码
        String preview = turn.isAssistantSuppressed()
                ? TrialTexts.preview("已生成计划，等待批准后执行")
                : TrialTexts.preview(WorkbenchChoiceJson.strip(turn.getAssistantText()));
        MessageResp resp = response(session, preview, written);
        resp.setStatus(RunStatus.SUCCESS.name());
        resp.setPlanPending(turn.isAssistantSuppressed());
        return resp;
    }

    /**
     * 首轮结束后触发异步标题生成（LLM 摘要 ≤10 字写入 session_title）。
     *
     * <p>为什么只在 message_count == 1 时触发：标题概括的是会话主题，首轮就定了；
     * 后续轮次再调模型纯属浪费 token。批准计划那条路径的 advance=false、轮次仍是 1，
     * 因此首轮「计划待批准 → 批准执行完」也能补上标题。
     *
     * <p>为什么这里不判标题是否已存在：判定要在异步线程读最新库值才准，
     * 由 {@link SessionTitleGeneratorAppService} 自己做；这里只做「是否首轮」这一层最省事的过滤。
     *
     * <p>工作台虚拟根会话 {@code agentId} 常为空：优先把本轮粘性专家传给生成器解析 SUMMARY 槽；
     * 仍为空时生成器走平台 SUMMARY / CHAT 降级，不得因此整段跳过。
     *
     * @param session  本轮会话（轮次已累加）
     * @param userText 用户这一轮原文
     * @param turn     本轮结果；助手正文为空（待批准）时不触发
     */
    private void maybeGenerateTitle(RunSession session, String userText, TrialTurn turn) {
        if (turn == null || turn.isAssistantSuppressed()) {
            return;
        }
        String answer = turn.getAssistantText();
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (safeCount(session) != 1) {
            return;
        }
        // 绑根会话用 agentId；工作台虚拟根用本轮粘性专家；都空则交给生成器走平台槽
        Long modelAgentId = session.getAgentId() != null ? session.getAgentId() : session.getStickyAgentId();
        try {
            titleGenerator.asyncGenerateTitle(session.getId(), modelAgentId, userText, answer);
        } catch (RuntimeException ex) {
            // 线程池拒绝等提交期异常：标题可以没有，本轮响应必须正常返回
            log.warn("提交会话标题生成任务失败 sessionId={} type={} cause={}",
                    session.getId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
        }
    }

    private MessageResp fail(RunSession session, List<RunEvent> written, String reason, String userText) {
        return fail(session, written, reason, userText, true);
    }

    /**
     * @param advance false 表示同一轮的续跑（批准计划），不重复累加轮次
     */
    private MessageResp fail(RunSession session, List<RunEvent> written, String reason, String userText,
                             boolean advance) {
        int seq = events.nextSeq(session.getId());
        written.add(writer.append(session.getId(), seq, EventType.ERROR, reason, null, null));
        String summary = TrialTexts.summary(reason);
        recordTurn(session, userText, summary, advance);
        operLogWriter.write("runtime", "run", "agent_run_session", String.valueOf(session.getId()),
                "试跑会话 " + session.getId() + " 本轮失败");
        MessageResp resp = response(session, "", written);
        resp.setStatus(RunStatus.FAILED.name());
        resp.setErrorSummary(summary);
        return resp;
    }

    /**
     * 推进轮次和预览，清掉或写上本轮错误。不把会话收成终态。
     *
     * @param advance true 表示这是新的一轮（用户刚发问）；批准/取消同一轮时传 false，
     *                否则 user 事件那一轮已被计过一次，这里会把轮次算两遍
     */
    /**
     * 发送前的取消门禁。已经写完的会话即使库里是 CANCELLED 也放行，不回写这一行。
     *
     * @param session 当前会话
     * @throws BizException 用户取消，或系统打断了未写完的一轮
     */
    private void assertSendable(RunSession session) {
        boolean open = false;
        if (session != null && session.getId() != null
                && RunStatus.CANCELLED.name().equals(session.getStatus())
                && !SessionCancelFace.userCancelled(session)) {
            // 用户取消不必再读事件；其余取消行要看最后一轮写没写完
            open = TurnStillOpen.of(events.listClosureMarks(List.of(session.getId())));
        }
        TrialSendGate.assertSendable(session, open);
    }

    private void recordTurn(RunSession session, String userText, String errorSummary, boolean advance) {
        TrialTurnOutcome.keepOpen(session);
        if (advance) {
            session.setMessageCount(safeCount(session) + 1);
        }
        ensureTitle(session, userText);
        session.setErrorSummary(errorSummary);
        sessions.updateOutcome(session, operatorContext.requireUserId());
    }

    private MessageResp response(RunSession session, String preview, List<RunEvent> written) {
        MessageResp resp = new MessageResp();
        resp.setSessionId(session.getId());
        resp.setStatus(session.getStatus());
        resp.setAssistantPreview(preview);
        resp.setErrorSummary(session.getErrorSummary());
        resp.setEvents(written.stream().map(TrialSessionQueryService::toEvent).toList());
        return resp;
    }

    /**
     * 只在还没有预览时用用户原文生成 last_msg_preview。
     *
     * <p>严禁在这里写 session_title：首轮开始时系统只落一条副标题预览，
     * 真正的主标题由 {@link SessionTitleGeneratorAppService} 在本轮结束后异步生成，
     * 或由用户手动改名产生。若这里顺手写了标题，异步任务会因为「已有值」而永远跳过。
     *
     * @param session  会话
     * @param userText 用户原文
     */
    private void ensureTitle(RunSession session, String userText) {
        if (session.getLastMsgPreview() == null || session.getLastMsgPreview().isBlank()) {
            session.setLastMsgPreview(TrialTexts.preview(userText));
        }
    }

    private int safeCount(RunSession session) {
        return session.getMessageCount() == null ? 0 : session.getMessageCount();
    }

    /**
     * 幂等指纹用的正文。没有文件时原样返回，旧请求的指纹不变。
     * 有文件时按长度前缀拼上文件名和 data URI，换文件不会命中上一次的重放。
     *
     * @param content 用户正文，可空
     * @param files   非图片文件，可空
     * @return 交给 {@link TrialMessageIdempotency} 的正文
     */
    private static String fingerprintContent(String content, List<UserMessageFiles.NamedPayload> files) {
        if (files == null || files.isEmpty()) {
            return content;
        }
        StringBuilder text = new StringBuilder(content == null ? "" : content);
        text.append('\u0000').append(files.size());
        for (UserMessageFiles.NamedPayload file : files) {
            String name = file == null || file.name() == null ? "" : file.name();
            String data = file == null || file.dataUrl() == null ? "" : file.dataUrl();
            text.append('\u0000').append(name.length()).append(':').append(name);
            text.append('\u0000').append(data.length()).append(':').append(data);
        }
        return text.toString();
    }

    /**
     * 一次断点续作的全部依据。所属域：runtime 的 application（仅本类内部使用）。
     *
     * <p>为什么把三样东西绑在一起：用户点了选项这件事，只有在「上一轮问了什么、
     * 上一轮答了什么」的上下文里才是一次回答。缺了前两样，专家收到的就只是一句孤立的
     * 选项文案，只能回一句「已完成作答」或把问题再问一遍。
     *
     * @param question 上一轮的用户问句；历史数据缺失时为空串（不会为 null）
     * @param answer   上一轮的助手终答（旧机器块已摘掉）；保证非空
     * @param option   用户选中的选项，label 以服务端登记为准
     */
    private record ChoiceAnchor(String question, String answer, AgentChoice.Option option) {
    }
}
