package com.agenthub.runtime.application;

import com.agenthub.agent.application.ReceptionSchemeResolver;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.MaDef;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.common.error.BizException;
import com.agenthub.runtime.application.router.ChildOutcome;
import com.agenthub.runtime.application.router.ChildRunner;
import com.agenthub.runtime.application.router.MaNestedContext;
import com.agenthub.runtime.application.router.MaPauseSignal;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.repository.MaRunWaitRepository;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.ChoiceCaptureHolder;
import com.agenthub.runtime.domain.service.LeafChoiceHolder;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 方案（MA）串行运行「停在子专家询问处」的落库、续跑与停止接线。所属域：runtime 的 application。
 *
 * <p>与工作流停等的 {@link WorkflowReplyResumer} 同构，但状态不是工作流节点，而是
 * 「一条串行方案跑到哪一位成员、问了什么、剩余谁没跑」。两侧共用同一套时间线投影：
 * 停等时把已完成成员、发问成员与 CHOICE 落成标准事件，续跑时只追加新成员，
 * 不新开运行、不再走智能网关、也不额外落一条 USER 事件。
 *
 * <p>硬边界：
 * <ul>
 *   <li><b>幂等</b>：续跑前先把等待行收成 DONE，同一句重复投递第二次查不到等待行，
 *       自然回落到普通一轮，不会把回复交给同一位专家两次；</li>
 *   <li><b>不新建运行</b>：先把回复写进发问成员的卡（CHOICE.reply），再由
 *       {@link RouterTrialExecutor#resumeSerial} 从该成员继续；</li>
 *   <li><b>不阻塞主路径</b>：找不到等待行 / 续跑时运行已被收口时返回空，
 *       调用方按现网普通一轮继续；</li>
 *   <li><b>可恢复</b>：等待快照落库（含问句、选项、已完成成员与已写键），
 *       刷新 / 重启后仍能停在同一成员；</li>
 *   <li><b>不打密钥</b>：只处理用户正文与问题选项，日志不带模型 Key。</li>
 * </ul>
 *
 * <p>线程：在请求线程内同步调用（与 {@link WorkflowReplyResumer} 一致），不另起线程池。
 */
@Component
public class MaReplyResumer {

    private static final Logger log = LoggerFactory.getLogger(MaReplyResumer.class);

    private final MaRunWaitRepository waits;
    private final TrialEventWriter writer;
    private final RunEventRepository events;
    private final TrialTimelineWriter timeline;
    private final RouterTrialExecutor router;
    private final MaTrialSpecLoader maSpecs;
    private final AgentDefRepository agents;
    private final ReceptionSchemeResolver reception;

    /**
     * @param waits     等待态仓储（一条会话最多一条 WAITING）
     * @param writer    事件落库入口（脱敏 / 截断口径与现网一致）
     * @param events    取会话内下一个序号
     * @param router    串行续跑执行器
     * @param maSpecs   方案执行来源装载（续跑时重新读候选）
     * @param agents    Agent 仓储（旧 Agent 固定串行续跑用）
     * @param reception 默认接待方案解析：嵌套停等要补写「智能网关」入口卡，入口 id 只能由它反查
     */
    public MaReplyResumer(MaRunWaitRepository waits, TrialEventWriter writer, RunEventRepository events,
                          RouterTrialExecutor router, MaTrialSpecLoader maSpecs, AgentDefRepository agents,
                          ReceptionSchemeResolver reception) {
        this.waits = waits;
        this.writer = writer;
        this.events = events;
        this.timeline = new TrialTimelineWriter(writer);
        this.router = router;
        this.maSpecs = maSpecs;
        this.agents = agents;
        this.reception = reception;
    }

    /**
     * 一次续跑的结果。
     *
     * @param turn   本轮正文（等待说明或续跑终答，供响应预览）
     * @param events 已落库事件（调用方并入本轮响应）
     */
    public record Resumed(TrialTurn turn, List<RunEvent> events) {
    }

    /**
     * 本会话是否有停在「子专家询问」处的方案运行。
     *
     * @param sessionId 工作台会话 id
     * @return 等待快照；没有时为空
     */
    public Optional<MaPauseSnapshot> waitingRun(long sessionId) {
        return waits.findWaiting(sessionId);
    }

    /**
     * 停等落库 + 把已完成成员与询问卡补写成时间线事件。
     *
     * <p>顺序与普通轮完全一致：入口身份 → 判定推理 / 决策块 → 每位成员的身份 / 路由 / 正文
     * → 询问选项 → 收尾助手事件。最后一行为空正文，只用于让前端 / 回放确认「这一轮已收口」，
     * 不产生第二条可见气泡。
     *
     * <p>重复停等（续跑后又有人问）时，已写过的成员只追加它这次新产生的正文，
     * 不会再写一遍身份 / 路由，卡片不会分裂成两张。
     *
     * @param sessionId 会话
     * @param state     本次停等快照
     * @param seq       当前下一条事件序号
     * @param written   本轮已写事件收集器
     * @return 本轮等待态正文（供响应预览；不落库）
     */
    public TrialTurn pauseRun(long sessionId, MaPauseSnapshot state, int seq, List<RunEvent> written) {
        state.setSessionId(sessionId);
        // 先落库：写事件失败时，宁可这一轮报错，也不要「页面在等、库里没有可续状态」
        waits.save(state);
        Set<String> alreadyWritten = new LinkedHashSet<>(state.getPreWrittenKeys());
        boolean firstWrite = alreadyWritten.isEmpty();
        List<MaPauseMember> turnOnly = new ArrayList<>();
        List<RouterTrialExecutor.RouteSegment> segments = new ArrayList<>();
        /*
         * done 的前 seedDoneSize 位是上一次停等已经完整写过的人：只留给续跑摘要，不再补事件。
         * 否则再停等时会把他们的正文追加到当前活跃卡片上（回放会挂错人、同卡出现两遍）。
         */
        List<MaPauseMember> done = state.getDone();
        int freshFrom = Math.max(0, Math.min(state.getSeedDoneSize(), done.size()));
        for (int i = freshFrom; i < done.size(); i++) {
            MaPauseMember member = done.get(i);
            if (alreadyWritten.contains(keyOf(member))) {
                turnOnly.add(member);
            } else {
                RouterTrialExecutor.RouteSegment segment = segmentOf(member);
                if (segment != null) {
                    segments.add(segment);
                }
            }
        }
        MaPauseMember paused = state.getPausedMember();
        if (paused != null) {
            if (alreadyWritten.contains(keyOf(paused))) {
                turnOnly.add(paused);
            } else {
                RouterTrialExecutor.RouteSegment segment = segmentOf(paused);
                if (segment != null) {
                    segments.add(segment);
                }
            }
        }
        // 先补已写成员的续跑正文，再写新成员：时间线顺序与真实执行顺序一致
        for (MaPauseMember member : turnOnly) {
            seq = timeline.appendParticipantTurn(sessionId, seq, member.getTurn(), written);
        }
        if (!segments.isEmpty() || firstWrite) {
            /*
             * 嵌套方案：入口卡与方案卡在正常路径里由外层包装器推，停等时整轮还没落库，
             * 这里必须在成员段之前补两行，否则刷新 / 回放里成员卡找不到父，全部摊平到根上。
             *
             * <p>只在首轮补（重复停等已写过）；补写位置在成员段之前，是为了让后面那条
             * 「正在等待」的 CHOICE 仍归属最后一条成员路由行，而不是被入口 / 方案行抢走。
             */
            if (firstWrite && state.getDepth() > 0) {
                seq = timeline.appendParticipantRow(sessionId, seq, entryGateway(), written);
                seq = timeline.appendParticipantRow(sessionId, seq, schemeCardOf(state), written);
            }
            // 首次停等补入口身份 / 判定推理 / 决策块；重复停等只补新成员段
            // 嵌套时入口已按上面两行补齐，这里不再重复传 gateway
            RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                    "", firstWrite ? state.getDecisionBlock() : null, segments, List.of(),
                    null, null, firstWrite ? state.getJudgeReasoning() : null, null, null,
                    firstWrite && state.getDepth() == 0 ? state.getGateway() : null);
            seq = timeline.appendRouterSegments(sessionId, seq, routed, written);
        }
        // 询问选项：标 waiting，前端把下一句当回复而不是新问题
        if (state.getChoice() != null) {
            RunEvent choiceEvent = timeline.choice(sessionId, seq, state.getChoice().awaiting());
            if (choiceEvent != null) {
                written.add(choiceEvent);
                seq++;
            }
        }
        // 空正文助手事件只用于收口这一轮（前端 hasOpenTurn 据此停止续订），不会多出可见气泡
        written.add(writer.append(sessionId, seq, EventType.ASSISTANT, "", null, null));
        String name = paused == null || paused.getParticipantName() == null
                ? state.getSchemeName() : paused.getParticipantName();
        TrialTurn turn = new TrialTurn();
        turn.setAssistantText("「" + (name == null ? "专家" : name) + "」正在等待你的回复。");
        return turn;
    }

    /**
     * 入口卡身份（「智能网关」）：嵌套停等补写用。
     *
     * <p>为什么必须反查而不是从快照里取：停等快照的 {@code maDefId} 是<b>被停住的方案</b>，
     * 入口 id 不在其中；入口块在直播里由根层包装器推过，停等落库时只能按默认接待方案补回来。
     *
     * @return 入口参与者；没有默认接待方案时为空（此时只丢入口块，不影响成员归属）
     */
    private ParticipantView entryGateway() {
        MaDef entry = reception.snapshot().def();
        if (entry == null || entry.getId() == null) {
            return null;
        }
        String name = entry.getName() == null || entry.getName().isBlank() ? "智能网关" : entry.getName().trim();
        return ParticipantView.gateway("gw-ma-" + entry.getId(), name);
    }

    /**
     * 方案卡身份：与直播里外层包装器推的那张用<b>同一个</b> id 算法。
     *
     * <p>方案卡自己的层级是 {@code depth - 1}（它由上一层派发），父键取当前层方案键；
     * 一层嵌套时正好落在根上（{@code p-0-root-<方案键>}）。
     *
     * @param state 停等快照
     * @return 方案参与者身份
     */
    private static ParticipantView schemeCardOf(MaPauseSnapshot state) {
        int cardDepth = Math.max(0, state.getDepth() - 1);
        String key = state.getParentKey() == null ? "" : state.getParentKey().trim();
        String name = state.getSchemeName() == null || state.getSchemeName().isBlank()
                ? "方案" : state.getSchemeName().trim();
        return ParticipantView.scheme(ChildRunner.participantIdOf(cardDepth, "", key), name, key, cardDepth);
    }

    /**
     * 用输入框原文或选项文案续跑等待中的方案运行。
     *
     * @param session  当前会话
     * @param state    已确认等待中的快照
     * @param reply    用户回复（选项的服务端权威文案或输入原文）
     * @param progress 本轮增量出口；可空
     * @return 续跑结果；没有可续状态时为空
     */
    public Optional<Resumed> resumeWaiting(RunSession session, MaPauseSnapshot state, String reply,
                                           TrialProgress progress) {
        if (session == null || state == null || reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        long sessionId = session.getId();
        long userId = session.getUserId();
        // 先占住等待行：并发重复投递只有第一次能拿到可续状态
        if (!waits.markDone(sessionId)) {
            return Optional.empty();
        }
        AgentDef agent = session.getAgentId() == null ? null
                : agents.findById(session.getAgentId()).orElse(null);
        MaRunSpec spec;
        try {
            /*
             * 嵌套停等：会话根挂的是入口方案，`resolve(session, agent)` 只会把入口方案装回来，
             * 续跑就变成「在入口方案的候选池里找被停住的那位成员」——必然找不到。
             * 此时按快照里的 maDefId 直接装载被停住的方案，恢复坐标才对得上。
             */
            spec = state.getDepth() > 0
                    ? maSpecs.requireForWorkbench(state.getMaDefId())
                    : maSpecs.resolve(session, agent);
        } catch (BizException ex) {
            // 方案被停用 / 删除：等不到续跑了，按业务失败交给调用方收口
            throw ex;
        }
        // 嵌套续跑要在同一条嵌套坐标里跑：成员 id / 方案键与停等那一次一致，回放才能挂回原卡
        boolean nested = state.getDepth() > 0;
        List<RunEvent> written = new ArrayList<>();
        int seq = events.nextSeq(sessionId);
        /*
         * 先把「已答复」写进时间线再跑续作：等待期间刷新 / 断线时，卡片立刻从
         * 「等你回复」变成「您的选择：…」，不会因为续作还没跑完而继续停在等待态。
         * 选项在这一步清空；续作正文随后追加到同一位专家卡上。
         */
        if (state.getChoice() != null) {
            RunEvent replied = timeline.choice(sessionId, seq, state.getChoice().replied(reply.trim()));
            if (replied != null) {
                written.add(replied);
                seq++;
            }
        }
        // 续跑轮同样允许这位专家再次询问：capture 与选项开关都要开
        ChoiceCaptureHolder.begin();
        LeafChoiceHolder.set(true);
        try {
            List<ChildOutcome> outcomes;
            if (nested) {
                // 只在这一段进入嵌套坐标：退出后写事件不依赖它（身份已在 outcome 里算好）
                MaNestedContext.enter(state.getParentKey());
                try {
                    outcomes = router.resumeSerial(agent, spec, state, reply.trim(), userId, sessionId, progress);
                } finally {
                    MaNestedContext.exit();
                }
            } else {
                outcomes = router.resumeSerial(agent, spec, state, reply.trim(), userId, sessionId, progress);
            }
            if (outcomes.isEmpty()) {
                return Optional.empty();
            }
            ChildOutcome first = outcomes.get(0);
            /*
             * 被回复的那位身份 / 路由已经写过：只追加它这次续跑新产生的思考与正文，
             * 卡片仍是同一张；回复行已在上方落库，这里不再重复写。
             */
            seq = timeline.appendParticipantTurn(sessionId, seq, first.turn(), written);
            List<RouterTrialExecutor.RouteSegment> rest = new ArrayList<>();
            for (int i = 1; i < outcomes.size(); i++) {
                RouterTrialExecutor.RouteSegment segment = segmentOf(outcomes.get(i));
                if (segment != null) {
                    rest.add(segment);
                }
            }
            if (!rest.isEmpty()) {
                RouterTrialExecutor.RoutedTurn routed = new RouterTrialExecutor.RoutedTurn(
                        "", null, rest, List.of(), null, null, null, null, null, null);
                seq = timeline.appendRouterSegments(sessionId, seq, routed, written);
            }
            TrialTurn turn = new TrialTurn();
            turn.setAssistantText(answerOf(outcomes, reply));
            // 最后一条不是助手正文时补一句收口：否则刷新后这一轮仍会被判成没写完
            if (!endsWithAssistant(written)) {
                written.add(writer.append(sessionId, seq, EventType.ASSISTANT,
                        turn.getAssistantText(), null, null));
            }
            return Optional.of(new Resumed(turn, written));
        } catch (MaPauseSignal pause) {
            // 续跑中又有人问：把新一轮停等按同一套事件补落库，再把等待说明当本轮结果
            TrialTurn waiting = pauseRun(sessionId, pause.state(), seq, written);
            return Optional.of(new Resumed(waiting, written));
        } finally {
            LeafChoiceHolder.clear();
            ChoiceCaptureHolder.clear();
        }
    }

    /**
     * 停止等待中的方案运行：按现网中断口径收口，会话本身仍可继续。
     *
     * <p>等待期间没有请求线程在跑；不显式收口的话，刷新后卡内仍停在「等待你的回复」。
     * 这里把等待行迁成 CANCELLED，并补一条回复态 CHOICE（清掉 chips）+「已中断」闭环。
     *
     * @param sessionId 会话
     * @param userId    会话归属人（仅日志用，不落正文）
     * @return true 表示确实停掉了一条等待中的运行
     */
    public boolean stopWaiting(long sessionId, long userId) {
        Optional<MaPauseSnapshot> found = waits.findWaiting(sessionId);
        if (found.isEmpty()) {
            return false;
        }
        if (!waits.markCancelled(sessionId)) {
            return false;
        }
        MaPauseSnapshot state = found.get();
        int seq = events.nextSeq(sessionId);
        if (state.getChoice() != null) {
            timeline.choice(sessionId, seq++, state.getChoice().replied("已中断"));
        }
        writer.append(sessionId, seq, EventType.ERROR, "已中断", null, null);
        log.info("停止等待回复的方案运行 sessionId={} userId={} scheme={}",
                sessionId, userId, state.getSchemeName());
        return true;
    }

    /**
     * 把一位快照成员还原成时间线分段。
     *
     * @param member 成员
     * @return 分段；没有身份时 null（跳过行不建卡）
     */
    private static RouterTrialExecutor.RouteSegment segmentOf(MaPauseMember member) {
        if (member == null) {
            return null;
        }
        ParticipantView participant = member.participant();
        if (participant == null) {
            return null;
        }
        return new RouterTrialExecutor.RouteSegment(member.getRouteLine(), member.getTurn(), member.getError(),
                member.getChildren(), member.getReasoning(), member.getWorkflow(),
                member.getConclusionName(), member.getConclusionReasoning(), participant);
    }

    /**
     * 成员结果 → 时间线分段。
     *
     * <p>身份只取 {@link ChildOutcome#participant()}（子执行时在嵌套上下文内算好的那一份）。
     * 不能在这里调 {@link ChildRunner#viewOf} 重算：续跑时 {@link MaNestedContext} 已退出，
     * 深度 / 父键都丢了，剩余成员会被落成 {@code p-0-root-*}，回放时跑出方案内框。
     *
     * @param outcome 子结果
     * @return 分段；身份不可识别时 null
     */
    private static RouterTrialExecutor.RouteSegment segmentOf(ChildOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        ParticipantView participant = outcome.participant();
        if (participant == null) {
            return null;
        }
        String line = outcome.intent().routeLine();
        return new RouterTrialExecutor.RouteSegment(line, outcome.turn(), outcome.error(), outcome.children(),
                outcome.reasoning(), outcome.workflow(), outcome.conclusionName(),
                outcome.conclusionReasoning(), participant);
    }

    /**
     * 本轮响应正文：优先最后一位有正文的成员，其次被回复成员，最后用一句明确的说明。
     *
     * @param outcomes 续跑结果
     * @param reply    用户回复
     * @return 非空正文
     */
    private static String answerOf(List<ChildOutcome> outcomes, String reply) {
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            ChildOutcome outcome = outcomes.get(i);
            if (outcome != null && outcome.turn() != null && outcome.turn().getAssistantText() != null
                    && !outcome.turn().getAssistantText().isBlank()) {
                return outcome.turn().getAssistantText();
            }
        }
        return "已根据你的回复继续完成：" + reply;
    }

    /**
     * 已写事件末尾是否已经有一条助手正文（回放据此收口）。
     *
     * @param written 已写事件
     * @return true 表示已收口
     */
    private static boolean endsWithAssistant(List<RunEvent> written) {
        for (int i = written.size() - 1; i >= 0; i--) {
            RunEvent event = written.get(i);
            if (event == null || event.getEventType() == null) {
                continue;
            }
            return EventType.ASSISTANT.code().equalsIgnoreCase(event.getEventType());
        }
        return false;
    }

    /**
     * 成员去重键：与 ChildRunner 的 route_key 口径一致，空键退回参与者 id。
     *
     * @param member 成员
     * @return 非空键
     */
    private static String keyOf(MaPauseMember member) {
        if (member == null) {
            return "";
        }
        String key = member.getRouteKey();
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        return member.getParticipantId() == null ? "" : member.getParticipantId().trim();
    }
}
