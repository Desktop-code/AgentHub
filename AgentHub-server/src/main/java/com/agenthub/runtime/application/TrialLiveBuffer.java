package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.PlanJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
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
        LiveTurn turn = new LiveTurn(sessionId);
        turn.nextSeq = seq;
        turn.append(EventType.USER, TrialTexts.content(userText, LIVE_TEXT_LIMIT), seq);
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

            @Override
            public void reasoning(String delta) {
                recorder.reasoning(delta);
                delegate.reasoning(delta);
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
         * 摘要与正文不同的完整事件（计划 / 步骤：摘要是人话，正文是 JSON）。
         */
        private synchronized void append(EventType type, String summary, String content, Integer fixedSeq) {
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
            // 摘要用「文件名 + 可读体积」：左栏/时间线折叠时也能看出生成了哪个文件
            String summary = file.fileName() + "（" + file.humanSize() + "）";
            turn.append(EventType.FILE, TrialTexts.summary(summary),
                    GeneratedFileMarker.payload(file.fileName(), file.size()), null);
        }
    }
}
