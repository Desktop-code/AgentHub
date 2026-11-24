package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.PreprocessView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.PreprocessJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进行中一轮的内存快照。所属域：runtime。
 *
 * 为什么需要它：引擎的思考、工具、正文增量只通过 SSE 出站下发，不写入 agent_run_event；
 * 事件要等整轮跑完才由 {@link TrialRunAppService} 批量落库。因此刷新页面后前端失去了 SSE，
 * 再拉 events 只能看到整轮结果，表现为「转圈卡住、结束才一次性出现」。
 * 这里把同一批增量在**内存**里镜像一份，供只读接口轮询，从而在不落库、
 * 不改 SSE 出站、不改 Adapter 的前提下恢复「边跑边看」。
 *
 * 边界：
 * - 只在当前进程有效，多实例部署时只能读到本实例在跑的那一轮；读不到就退回 events 轮询。
 * - 整轮结束后立刻清空，权威数据始终是 agent_run_event。
 * - 正文按 {@link TrialTexts#content} 脱敏并截断，与落库口径一致，不额外暴露密钥。
 */
@Component
public class TrialLiveBuffer {

    /** 单轮内存占用上限。超出后不再追加，避免长回答把堆撑爆。 */
    private static final int LIVE_TEXT_LIMIT = 32 * 1024;

    /** 会话主键 → 当前这一轮的快照。整轮结束即移除。 */
    private final Map<Long, LiveTurn> turns = new ConcurrentHashMap<>();

    /**
     * 开始一轮：登记会话并写入用户事件。
     *
     * @param sessionId 会话主键
     * @param seq       本轮用户事件的序号，与落库口径一致，便于前端按 seq 排序
     * @param userText  用户输入
     */
    public void begin(Long sessionId, int seq, String userText) {
        begin(sessionId, seq, userText, null);
    }

    /**
     * 开始一轮：登记会话、写入用户事件，并可选追加一条图片文字提取结果。
     *
     * <p>为什么识别结果要进快照：SSE 已在整轮开始前就把它推出去了，
     * 若快照里没有这一条，刷新页面后同一轮会「少一段」——用户看到主模型答了些什么，
     * 却看不到它依据的图片文字。
     *
     * @param sessionId 会话主键
     * @param seq       本轮用户事件的序号
     * @param userText  用户输入
     * @param visionNote 图片文字提取结果，可空
     */
    public void begin(Long sessionId, int seq, String userText, String visionNote) {
        begin(sessionId, seq, userText, null, visionNote);
    }

    /**
     * 开始一轮，并带上用户附图清单。
     *
     * <p>刷新时内存快照会按序号盖住已落库事件。用户事件的摘要若只有正文，
     * 这一拍会把附图清单冲掉，所以快照必须带同一份清单。
     *
     * @param sessionId    会话主键
     * @param seq          本轮用户事件的序号
     * @param userText     用户原文，只发图时为空串
     * @param imageSummary 附图清单，无图时 null
     * @param visionNote   图片文字提取结果，可空
     */
    public void begin(Long sessionId, int seq, String userText, String imageSummary, String visionNote) {
        LiveTurn turn = new LiveTurn(sessionId);
        turn.nextSeq = seq;
        String body = TrialTexts.content(userText, LIVE_TEXT_LIMIT);
        if (imageSummary != null && !imageSummary.isBlank()) {
            // 只发图时正文为空：清单在摘要里，回放靠它把图找回来
            turn.append(EventType.USER, imageSummary, body, seq);
        } else if (!body.isBlank()) {
            turn.append(EventType.USER, body, seq);
        }
        if (visionNote != null && !visionNote.isBlank()) {
            turn.append(EventType.VISION, TrialTexts.content(visionNote, LIVE_TEXT_LIMIT), null);
        }
        turns.put(sessionId, turn);
    }

    /**
     * 开始一轮，但不写用户事件。
     *
     * <p>用于「批准待执行计划」：批准不是新的用户输入，库里那条 USER 事件早已存在，
     * 再写一次会让前端时间线多出一个重复气泡。这里只把序号游标对齐到该轮的下一条，
     * 后续 plan / step / reasoning / tool / assistant 仍按真实序号递增，与落库无缝衔接。
     *
     * @param sessionId 会话主键
     * @param nextSeq   本轮下一条事件的序号（即 {@code events.nextSeq(sessionId)} 的结果）
     */
    public void beginAt(Long sessionId, int nextSeq) {
        LiveTurn turn = new LiveTurn(sessionId);
        turn.nextSeq = nextSeq;
        turns.put(sessionId, turn);
    }

    /**
     * 取该会话的写入器。须先 {@link #begin} 登记。
     *
     * @param sessionId 会话主键
     * @return 写入器；未登记时返回 null（调用方应跳过镜像）
     */
    public Recorder recorder(Long sessionId) {
        LiveTurn turn = turns.get(sessionId);
        return turn == null ? null : new Recorder(turn);
    }

    /**
     * 取当前快照。没有在跑的一轮时返回空列表。
     *
     * @param sessionId 会话主键
     * @return seq 升序的事件副本，形状与落库事件一致，前端可直接复用同一套回放
     */
    public List<EventResp> snapshot(Long sessionId) {
        LiveTurn turn = turns.get(sessionId);
        return turn == null ? List.of() : turn.copy();
    }

    /**
     * 该会话此刻是否确有一轮在跑。
     *
     * @param sessionId 会话主键
     * @return true 表示内存里还有本轮快照
     */
    public boolean running(Long sessionId) {
        return turns.containsKey(sessionId);
    }

    /**
     * 用户已停止本轮：把内存快照封成中断终态，供刷新后的只读轮询读到「已停」而不是「还在跑」。
     *
     * <p>为什么在快照上做：执行线程不会立刻结束（工作流节点、其它并行子还在收尾），
     * 只把库里的 run 写成 CANCELLED 的话，刷新时页面仍会把最后一张 RUNNING 快照画成运行中。
     * 这里先让快照与取消落库对齐，整轮真正结束时再由 {@code TrialRunAppService} 落成事件。
     *
     * <p>没有在跑的一轮时无操作；重复停止只封一次，不重复补闭环说明。
     *
     * @param sessionId 会话主键
     */
    public void sealCancelled(Long sessionId) {
        LiveTurn turn = turns.get(sessionId);
        if (turn != null) {
            turn.sealCancelled();
        }
    }

    /**
     * 这一轮的快照是否已被用户停止封口。
     *
     * <p>调用方（{@code TrialRunAppService}）据此决定：整轮结束时除了普通事件，
     * 还要不要把快照里已出现的过程与工作流卡片按中断终态补落库。
     *
     * @param sessionId 会话主键
     * @return true 表示本轮已被用户停止
     */
    public boolean cancelled(Long sessionId) {
        LiveTurn turn = turns.get(sessionId);
        return turn != null && turn.cancelled;
    }

    /**
     * 结束本轮，丢掉快照。落库事件接管后由调用方在 finally 里调用。
     *
     * @param sessionId 会话主键
     */
    public void clear(Long sessionId) {
        turns.remove(sessionId);
    }

    /**
     * 把增量同时写给真实出站口与内存快照。
     * 出站失败不影响记录，记录失败也不影响出站：两边互不阻断。
     *
     * @param delegate 真实出站（SSE），可空
     * @param recorder 内存写入器，可空
     * @return 合流后的写入器；两者都空时返回 null
     */
    public static TrialProgress tee(TrialProgress delegate, Recorder recorder) {
        if (delegate == null) {
            return recorder;
        }
        if (recorder == null) {
            return delegate;
        }
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                recorder.phase(phase);
                delegate.phase(phase);
            }

            @Override
            public void route(String summary) {
                recorder.route(summary);
                delegate.route(summary);
            }

            /**
             * 参与者开始：两边都要发。快照记一条身份事件（刷新后仍能按身份还原块），
             * 出站帧让前端立刻出块。
             */
            @Override
            public void beginParticipant(ParticipantView participant) {
                recorder.beginParticipant(participant);
                delegate.beginParticipant(participant);
            }

            /**
             * 归属切换只走出站：快照里每条增量都已按顺序记录，回放时按 begin 事件归属，
             * 不必为每次切换再写一条（否则快照会被翻转的标记撑大）。
             */
            @Override
            public void attributeParticipant(String participantId) {
                delegate.attributeParticipant(participantId);
            }

            /**
             * 参与者整段结论：路由行 + 正文仍要写出站；快照侧由随后的 assistant 记录承接。
             */
            @Override
            public void deliverParticipantConclusion(String routeLine, String text) {
                delegate.deliverParticipantConclusion(routeLine, text);
            }

            /**
             * 参与者终态：出站与内存快照两边都要。
             *
             * <p>为什么快照也必须记：刷新后页面读的是这份镜像。只有 begin 没有终态时，
             * 投影只能拿「整轮是否还在流」当进行态——已经跑完的成员会和正在跑的那位一起转圈，
             * 多成员方案里就是「方案内全员同时转」。整轮结束时落库事件才接管，刷新那一拍覆盖不了。
             * 终态帧沿用出站同一形状（同 id、begin=false、state、note），前端一套解析即可。
             */
            @Override
            public void endParticipant(ParticipantView participant, String state, String note) {
                recorder.endParticipant(participant, state, note);
                delegate.endParticipant(participant, state, note);
            }

            /** 内层方案的收口通道要保持镜像，不能在这里把内存快照丢掉。 */
            @Override
            public TrialProgress conclusionChannel() {
                return tee(delegate.conclusionChannel(), recorder);
            }

            @Override
            public void reasoning(String delta) {
                recorder.reasoning(delta);
                delegate.reasoning(delta);
            }

            @Override
            public void toolStart(String name) {
                // 快照不记「正在调用」：刷新后那条胶囊没有对应的结果可配对，重放时不该出现
                delegate.toolStart(name);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                recorder.tool(name, arguments, result, success);
                delegate.tool(name, arguments, result, success);
            }

            @Override
            public void assistant(String delta) {
                recorder.assistant(delta);
                delegate.assistant(delta);
            }

            @Override
            public void vision(String text) {
                recorder.vision(text);
                delegate.vision(text);
            }

            @Override
            public void preprocess(PreprocessView view) {
                recorder.preprocess(view);
                delegate.preprocess(view);
            }

            @Override
            public void plan(PlanView plan) {
                recorder.plan(plan);
                delegate.plan(plan);
            }

            @Override
            public void step(StepView step) {
                recorder.step(step);
                delegate.step(step);
            }

            @Override
            public void file(GeneratedFile file) {
                recorder.file(file);
                delegate.file(file);
            }

            @Override
            public void childPlanPending(ChildPlanPendingView pending) {
                recorder.childPlanPending(pending);
                delegate.childPlanPending(pending);
            }

            /**
             * 结构化选项：快照与出站都要。刷新时若整轮还没落库，快照是 chips 的唯一来源。
             */
            @Override
            public void choice(AgentChoice choice) {
                recorder.choice(choice);
                delegate.choice(choice);
            }

            /**
             * 工作流卡片同样两边都要：服务端快照记整份视图，出站帧让页面立刻更新节点状态。
             * 漏掉这个覆写会让「刷新页面后卡片消失」，而实时那一轮看起来一切正常。
             */
            @Override
            public void workflow(WorkflowRunView view) {
                recorder.workflow(view);
                delegate.workflow(view);
            }
        };
    }

    /**
     * 单轮的可变快照。写入来自引擎线程（Router 多子并发），故全程加锁。
     */
    private static final class LiveTurn {

        private final Long sessionId;
        private final List<EventResp> events = new ArrayList<>();
        /** 下一条事件用的序号。起始值由用户事件的真实 seq 推进 */
        private int nextSeq;
        /** 用户是否已点停止：封口后快照按中断终态出站，不再表示「还在跑」 */
        private boolean cancelled;

        private LiveTurn(Long sessionId) {
            this.sessionId = sessionId;
        }

        /** 追加一条完整事件。 */
        private synchronized void append(EventType type, String text, Integer fixedSeq) {
            if (text == null || text.isBlank()) {
                return;
            }
            append(type, TrialTexts.summary(text), text, fixedSeq);
        }

        /**
         * 覆盖工作流卡片的快照：同一轮只保留最新一份。
         *
         * <p>为什么不像正文那样续写：卡片是**状态快照**，不是增量。追加会让刷新时
         * 看到一串互相矛盾的旧状态（先是「运行中」再是「已完成」），看起来像卡了两遍。
         *
         * @param summary 人话摘要
         * @param content 视图 JSON
         */
        private synchronized void replaceWorkflow(String summary, String content) {
            // 停止后快照已封口：迟到的 RUNNING / 完成帧不能把「已取消」卡片覆盖掉
            if (cancelled) {
                return;
            }
            for (int i = events.size() - 1; i >= 0; i -= 1) {
                if (EventType.WORKFLOW.code().equals(events.get(i).getEventType())) {
                    events.remove(i);
                    break;
                }
            }
            append(EventType.WORKFLOW, summary, content, null);
        }

        /**
         * 入口预处理只留最新一份：进行中 → 完成 / 失败是同一块快照，追加会让刷新看到两段互相矛盾的状态。
         *
         * @param summary 人话摘要
         * @param content 视图 JSON
         */
        private synchronized void replacePreprocess(String summary, String content) {
            // 同上：停止后不再让迟到的预处理完成帧改写快照
            if (cancelled) {
                return;
            }
            for (int i = events.size() - 1; i >= 0; i -= 1) {
                if (EventType.PREPROCESS.code().equals(events.get(i).getEventType())) {
                    events.remove(i);
                    break;
                }
            }
            append(EventType.PREPROCESS, summary, content, null);
        }

        /**
         * 把快照封成「用户已停止」的终态。
         *
         * <p>做两件事，缺一都会让刷新后的页面继续把这一轮画成运行中：
         * <ol>
         *   <li>工作流卡片改画 CANCELLED，在途节点与库里 {@code cancelOpen} 同口径落成
         *       CANCELLED（未开始的落成 SKIPPED），工具胶囊不再按 RUNNING 转圈；</li>
         *   <li>末尾补一条「已中断」闭环事件：前端 {@code hasOpenTurn} 看到它就不再续订，
         *       直播投影也走现有「已中断」收口，不另写刷新特例。</li>
         * </ol>
         *
         * <p>只改内存快照，不落库；整轮真正结束时由 {@code TrialRunAppService} 统一补写。
         */
        private synchronized void sealCancelled() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (EventResp item : events) {
                if (!EventType.WORKFLOW.code().equals(item.getEventType())) {
                    continue;
                }
                String sealed = WorkflowRunJson.cancelled(item.getContentText());
                if (sealed != null && !sealed.isBlank()) {
                    item.setContentText(sealed);
                }
            }
            // 末尾已经是同一条中断说明时不重复补：重复点击停止只封一次
            EventResp last = events.isEmpty() ? null : events.get(events.size() - 1);
            String lastText = last == null ? "" : trim(last.getContentText());
            if (lastText.isEmpty() && last != null) {
                lastText = trim(last.getSummary());
            }
            boolean closed = last != null
                    && EventType.ERROR.code().equals(last.getEventType())
                    && "已中断".equals(lastText);
            if (!closed) {
                append(EventType.ERROR, "已中断", null);
            }
        }

        /** 去空白；快照里正文与摘要都可能为空，判断闭环说明时两列都看。 */
        private static String trim(String text) {
            return text == null ? "" : text.trim();
        }

        /**
         * 摘要与正文不同的完整事件（计划 / 步骤：摘要是人话，正文是 JSON）。
         */
        private synchronized void append(EventType type, String summary, String content, Integer fixedSeq) {
            /*
             * 停止后快照只允许再写「已中断」闭环：执行线程可能还在收尾，迟到的正文 / 工具 / 工作流帧
             * 若继续追加，会把末尾的闭环说明顶掉，刷新时 hasOpenTurn 又把这一轮当成还在跑。
             * 用户已经点了停止，停止之后才产生的过程不再补进回放。
             */
            if (cancelled && !(type == EventType.ERROR
                    && ((content != null && content.contains("已中断"))
                    || (summary != null && summary.contains("已中断"))))) {
                return;
            }
            int seq = fixedSeq != null ? fixedSeq : nextSeq;
            nextSeq = seq + 1;
            EventResp resp = new EventResp();
            resp.setSessionId(sessionId);
            resp.setSeq(seq);
            resp.setEventType(type.code());
            resp.setSummary(summary);
            resp.setContentText(content);
            resp.setCreateTime(LocalDateTime.now());
            events.add(resp);
        }

        /**
         * 追加增量：末尾同类型事件就续写，避免一个 token 一条。
         * 续写会重新脱敏与截断，保证与落库内容同一口径。
         */
        private synchronized void appendDelta(EventType type, String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            // 停止后冻结快照：流式正文的尾巴不能把闭环说明顶下去
            if (cancelled) {
                return;
            }
            EventResp last = events.isEmpty() ? null : events.get(events.size() - 1);
            if (last != null && type.code().equals(last.getEventType())) {
                String merged = (last.getContentText() == null ? "" : last.getContentText()) + delta;
                String safe = TrialTexts.content(merged, LIVE_TEXT_LIMIT);
                last.setContentText(safe);
                last.setSummary(TrialTexts.summary(safe));
                return;
            }
            append(type, TrialTexts.content(delta, LIVE_TEXT_LIMIT), null);
        }

        /** 拷贝一份返回，避免调用方在序列化时与写入线程抢同一批对象。 */
        private synchronized List<EventResp> copy() {
            List<EventResp> out = new ArrayList<>(events.size());
            for (EventResp item : events) {
                EventResp one = new EventResp();
                one.setId(item.getId());
                one.setSessionId(item.getSessionId());
                one.setSeq(item.getSeq());
                one.setEventType(item.getEventType());
                one.setSummary(item.getSummary());
                one.setContentText(item.getContentText());
                one.setCreateTime(item.getCreateTime());
                out.add(one);
            }
            return out;
        }
    }

    /**
     * 内存写入器。实现 {@link TrialProgress}，被 {@link #tee} 包在真实出站之外。
     */
    public static final class Recorder implements TrialProgress {

        private final LiveTurn turn;

        private Recorder(LiveTurn turn) {
            this.turn = turn;
        }

        @Override
        public void phase(String phase) {
            // 阶段只影响前端状态条，事件时间线里不留痕，避免多出一条无可展示内容的记录
        }

        @Override
        public void route(String summary) {
            turn.append(EventType.ROUTE, TrialTexts.content(summary, LIVE_TEXT_LIMIT), null);
        }

        /**
         * 参与者开始：快照记一条身份事件，刷新后前端仍能按 id 建块。
         *
         * <p>只记 begin：归属切换不写快照（见 {@link #attributeParticipant(String)}）。
         */
        @Override
        public void beginParticipant(ParticipantView participant) {
            String payload = ParticipantJson.payload(participant, true);
            if (payload == null) {
                return;
            }
            turn.append(EventType.PARTICIPANT, TrialTexts.summary(participant.name()), payload, null);
        }

        /**
         * 参与者终态：快照记一条 begin=false + state 的身份事件。
         *
         * <p>为什么必须补：快照是刷新后唯一的「边跑边看」来源。缺这一帧时，回放出来的
         * route 块没有 {@code closed}，整轮又仍被标成 streaming，于是所有成员一起转圈；
         * 直播侧有 SSE 终态所以看起来正常，差别只在这一份镜像。
         *
         * <p>状态/说明与出站帧同源（{@link ParticipantJson#payload}），不另造字段；
         * 老前端不认识 state 时按普通归属帧忽略，行为不变。
         */
        @Override
        public void endParticipant(ParticipantView participant, String state, String note) {
            String payload = ParticipantJson.payload(participant, state, note);
            if (payload == null) {
                return;
            }
            turn.append(EventType.PARTICIPANT, TrialTexts.summary(participant.name()), payload, null);
        }

        /**
         * 归属切换不落快照：身份事件已按 begin 顺序记录，逐次切换只会把快照撑大，
         * 而快照只用于「刷新时仍在进行中的一轮」，完整归属以落库时间线为准。
         */
        @Override
        public void attributeParticipant(String participantId) {
            // 快照不记归属切换
        }

        @Override
        public void reasoning(String delta) {
            turn.appendDelta(EventType.REASONING, delta);
        }

        @Override
        public void tool(String name, String arguments, String result, boolean success) {
            // 与落库口径一致：首行是调用行，空行后是结果；前端按「\n\n」拆分
            String line = TrialTexts.toolDisplay(name, arguments);
            if (!success) {
                line = line + " · 失败";
            }
            String body = result == null || result.isBlank() ? line : line + "\n\n" + result;
            turn.append(EventType.TOOL, TrialTexts.content(body, LIVE_TEXT_LIMIT), null);
        }

        @Override
        public void assistant(String delta) {
            turn.appendDelta(EventType.ASSISTANT, delta);
        }

        @Override
        public void vision(String text) {
            // 识别结果是整段素材而不是增量：直接追加一条完整事件，避免与相邻的 reasoning 续写混在一起
            turn.append(EventType.VISION, TrialTexts.content(text, LIVE_TEXT_LIMIT), null);
        }

        /**
         * 入口预处理：同一轮只保留最新快照，刷新后与直播终态一致。
         */
        @Override
        public void preprocess(PreprocessView view) {
            String payload = PreprocessJson.payload(view);
            if (payload.isEmpty()) {
                return;
            }
            turn.replacePreprocess(PreprocessJson.summaryOf(view),
                    TrialTexts.content(payload, LIVE_TEXT_LIMIT));
        }

        /**
         * 计划与步骤的正文是 JSON、摘要是人话，故走「摘要与正文分开」的那条追加路径，
         * 否则 JSON 会被当成摘要截成 500 字。
         */
        @Override
        public void plan(PlanView plan) {
            if (plan == null) {
                return;
            }
            String summary = plan.steps().isEmpty() ? "计划" + plan.stateText() : plan.summary();
            turn.append(EventType.PLAN, TrialTexts.summary(summary),
                    TrialTexts.content(PlanJson.planPayload(plan), LIVE_TEXT_LIMIT), null);
        }

        @Override
        public void step(StepView step) {
            if (step == null) {
                return;
            }
            turn.append(EventType.STEP, TrialTexts.summary(step.displayLine()),
                    TrialTexts.content(PlanJson.stepPayload(step), LIVE_TEXT_LIMIT), null);
        }

        /**
         * 文件产物。正文与 SSE 帧、落库事件共用 {@link GeneratedFileMarker#payload} 的字段形状，
         * 前端一套解析即可覆盖「实时推送 / 内存快照 / 落库回放」三条来源。
         */
        @Override
        public void file(GeneratedFile file) {
            if (file == null || file.fileName() == null || file.fileName().isBlank()) {
                return;
            }
            // 摘要用「文件名 + 可读体积」：左栏/时间线折叠时也能看出生成了哪个文件；
            // 正文带参与者身份，断线重连走内存快照时文件仍挂回写出它的那一位
            String summary = file.fileName() + "（" + file.humanSize() + "）";
            turn.append(EventType.FILE, TrialTexts.summary(summary),
                    GeneratedFileMarker.payload(file.fileName(), file.size(),
                            file.participantId(), file.participantName()), null);
        }

        /**
         * 结构化选项：正文是 JSON、摘要是问题句，故走「摘要与正文分开」的追加路径。
         * 字段与 SSE {@code choice} 帧、落库 CHOICE 事件完全一致，前端一套解析即可。
         */
        @Override
        public void choice(AgentChoice choice) {
            String payload = WorkbenchChoiceJson.write(choice);
            if (payload.isEmpty()) {
                return;
            }
            String question = choice.question() == null ? "" : choice.question().trim();
            String summary = question.isEmpty() ? "需要你选择" : question;
            turn.append(EventType.CHOICE, TrialTexts.summary(summary),
                    TrialTexts.content(payload, LIVE_TEXT_LIMIT), null);
        }

        /**
         * 工作流执行卡片：快照里保留**最新一份**即可。
         *
         * <p>为什么要覆盖而不是追加：这是同一张卡片的快照（运行中 → 已完成），
         * 追加会让刷新时看到一串互相矛盾的旧状态；覆盖后视觉与实时完全一致。
         *
         * <p>为什么不能再按字符截断：快照会被刷新页面直接读走并 JSON 解析，
         * 半截字符串会让整张卡消失。装得下就存整份；装不下退成紧凑骨架（仍是合法 JSON），
         * 读侧按 {@code runId} 从库里补回节点产出。
         */
        @Override
        public void workflow(WorkflowRunView view) {
            String payload = WorkflowRunJson.write(view);
            if (payload.isEmpty()) {
                return;
            }
            String stored = payload.length() <= LIVE_TEXT_LIMIT
                    ? payload : WorkflowRunJson.compact(view);
            if (stored.isEmpty()) {
                return;
            }
            // 同一张卡片只留最新快照：先摘掉上一份，再按当前顺序追加
            turn.replaceWorkflow(TrialTexts.summary(WorkflowRunJson.summary(view)), stored);
        }
    }
}
