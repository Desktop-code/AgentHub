package com.agenthub.runtime.api;

import com.agenthub.runtime.api.dto.MessageResp;
import com.agenthub.runtime.api.sse.ServletSseFrameSink;
import com.agenthub.runtime.api.sse.SseBatchOptions;
import com.agenthub.runtime.api.sse.UnifiedSseWriter;
import com.agenthub.runtime.application.TrialStreamEnd;
import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.ChildPlanPendingView;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.PreprocessView;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.GeneratedFileMarker;
import com.agenthub.runtime.domain.service.PreprocessJson;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.domain.service.WorkbenchChoiceJson;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把试跑增量写成 SSE。所属域：runtime。
 * 在当前请求线程里边跑边刷出，避免异步线程丢掉登录态。出站前脱敏。
 * 结束帧（done）的兜底统一交给 {@link UnifiedSseWriter}：本类只负责把「业务上已经结束」
 * 这件事通知过去，其余四条异常路径（截断 / 超时 / 上游错误 / 客户端取消）由写入器用 CAS 收口。
 *
 * <p>正文合批在写入器里做（见 {@code UnifiedSseWriter#messageDelta}）：
 * 本类仍是「一个增量一次回调」，合批只改变出站帧数，不改事件名、字段与顺序。
 */
@Slf4j
public final class TrialChunkWriter implements TrialProgress, TrialStreamEnd {

    private final UnifiedSseWriter writer;

    /**
     * 已下发过的参与者身份：id → 身份。
     *
     * <p>为什么要记：并行 / 嵌套时增量交错，「归属哪一位」只连续标记一次就够了；
     * 若每个增量都重发完整身份，帧量会随 token 数膨胀。用并发 Map 是因为多个子线程
     * 会同时推事件，而身份只在「第一次出现」时需要写。
     */
    private final Map<String, ParticipantView> participants = new ConcurrentHashMap<>();

    /**
     * 最近一次下发的归属 id。
     *
     * <p>由 {@link #emitAttribution(String)} 在同步块里读写：只有切换参与者时才发一帧，
     * 同一参与者的连续思考 / 工具不再产生额外的身份帧。
     */
    private String lastParticipantId = "";

    /**
     * @param response 已设为 text/event-stream 的响应
     * @param json     把 data 收成一行，避免换行截断 SSE
     */
    public TrialChunkWriter(HttpServletResponse response, ObjectMapper json) {
        this(response, json, SseBatchOptions.defaults());
    }

    /**
     * @param response 已设为 text/event-stream 的响应
     * @param json     把 data 收成一行，避免换行截断 SSE
     * @param batch    正文增量合批参数（时间窗 + 单帧字节上限）
     */
    public TrialChunkWriter(HttpServletResponse response, ObjectMapper json, SseBatchOptions batch) {
        this.writer = new UnifiedSseWriter(new ServletSseFrameSink(response, true), json, batch);
    }

    /**
     * 绑定会话主键，写进 done 载荷供前端刷新历史。
     *
     * @param sessionId 会话主键
     */
    public void bindSession(Long sessionId) {
        writer.bindSession(sessionId);
    }

    /**
     * 是否已发出结束帧。供调用方在收尾时判断是否还需要兜底。
     *
     * @return true 表示 done 已写
     */
    public boolean isDone() {
        return writer.isDone();
    }

    /**
     * 客户端断开或本轮被中止时的收尾。这是出站层独有的兜底点：
     * 上游 Adapter 取消后已无接收方，只有这里还持有 HTTP 连接，能真实尝试补一帧 done。
     *
     * @return true 表示结束帧确实写出
     */
    public boolean cancel() {
        return writer.finishCancelled();
    }

    /**
     * 收尾兜底：若还没发过结束帧就补一帧。Controller 在 finally 里无条件调用。
     * 原因取 STOP 而不是 ERROR：走到这里通常意味着业务正常跑完但没显式收尾，
     * 真正的失败路径（{@link #fail}）早已写入 ERROR 并占位，不会落到这里。
     *
     * @return true 表示本次补出了结束帧
     */
    public boolean ensureFinished() {
        return writer.finish(UnifiedFinishReason.STOP, null, null);
    }

    /** {@inheritDoc} */
    @Override
    public void phase(String phase) {
        String text = phase == null ? "" : phase;
        if (text.isEmpty()) {
            return;
        }
        // phase 在定稿 §4.1 没有统一类型，保留旧事件名直接下发，不编造 unified 语义
        writer.emitPhase(text);
    }

    /** {@inheritDoc} */
    @Override
    public void route(String summary) {
        String text = TrialTexts.redact(summary);
        if (!text.isEmpty()) {
            writer.emitRoute(text);
        }
    }

    /**
     * 参与者真正开始：立刻下发一条完整身份帧，前端据此先把块画出来。
     *
     * <p>载荷带 {@code begin=true}：与「只是切换归属」的轻量帧区分开，前端只在 begin 时新建块。
     * 同一 id 重复开始（并行子偶发）只发一次，避免出现两块同名参与者。
     *
     * <p>重复 id 只告警、不补帧、不改 id 规则：这里的 id 由 {@code ChildRunner.participantIdOf}
     * 按「深度 + 父方案键 + 叶子键」拼出，若同一轮里真的撞了车，第二张卡就再也收不到 begin，
     * 页面只能等收尾回放补——现象是「子专家后面才一起出现」。留一条 WARN 用来确认是否撞车，
     * 撞车本身要靠 id 规则单独收口，不在出站层偷偷补一张卡。
     */
    @Override
    public void beginParticipant(ParticipantView participant) {
        if (participant == null || participant.id().isBlank()) {
            return;
        }
        ParticipantView previous = participants.putIfAbsent(participant.id(), participant);
        // 已经登记过：说明这一步早已开始过，不重复发 begin 帧；同名不同人则必须留痕
        if (previous != null) {
            log.warn("参与者 begin 撞车，已忽略本次身份帧 id={} 本次={} 已登记={} kind={} depth={} routeKey={}",
                    participant.id(), participant.name(), previous.name(), participant.kind(),
                    participant.depth(), participant.routeKey());
            return;
        }
        emitParticipant(participant, true);
    }

    /** {@inheritDoc} */
    @Override
    public void attributeParticipant(String participantId) {
        emitAttribution(participantId == null ? "" : participantId.trim());
    }

    /**
     * 参与者终态：沿用同一个 participant 事件，带上 {@code state}（必要时带 {@code note}）。
     *
     * <p>为什么与 begin 共用事件名：身份是同一个契约对象，前端解析器不必新增事件分支；
     * {@code begin=false} 明确表示「不新建块」，只收口已有块。没有 begin 登记过的 id 直接忽略——
     * 没有块可收，发了也没有接收方。
     *
     * @param participant 参与者身份
     * @param state       done / failed / aborted
     * @param note        终态说明；可空
     */
    @Override
    public void endParticipant(ParticipantView participant, String state, String note) {
        if (participant == null || participant.id() == null || participant.id().isBlank()) {
            return;
        }
        String code = state == null ? "" : state.trim();
        if (code.isEmpty()) {
            return;
        }
        // 用 begin 时登记的那份身份：名称以「块上已经显示的那位」为准，避免改名后收错块
        ParticipantView known = participants.get(participant.id());
        if (known == null) {
            return;
        }
        // 终态与 begin 同一事件名，同样交对象：先收成字符串再交出站会再编码一次
        Map<String, Object> payload = ParticipantJson.frame(known, code, TrialTexts.redact(note));
        if (!payload.isEmpty()) {
            writer.emitLegacy("participant", payload);
        }
    }

    /**
     * 切换「后续增量归属哪一位」。只在与上一条不同时下发，避免每帧都发身份。
     *
     * <p>不在 {@link #participants} 里的 id 直接忽略：身份帧必须先于内容帧，
     * 没有 begin 的归属标记无法建立块，发了也没有意义。
     *
     * @param participantId 参与者标识
     */
    private void emitAttribution(String participantId) {
        if (participantId.isEmpty()) {
            return;
        }
        ParticipantView known = participants.get(participantId);
        if (known == null) {
            return;
        }
        synchronized (this) {
            if (participantId.equals(lastParticipantId)) {
                return;
            }
            lastParticipantId = participantId;
        }
        emitParticipant(known, false);
    }

    /**
     * 写一行 participant 帧。
     *
     * <p>载荷是对象，与 {@link #workflow} 同一口径。写入器只序列化这一次，
     * 前端一次解析就得到 begin / kind / depth。先收成 JSON 字符串再交出站，
     * 会再包一层引号，解析结果是字符串，建卡分支直接丢掉。
     *
     * @param participant 身份
     * @param begin       true＝该步开始（前端新建块）；false＝仅切换归属
     */
    private void emitParticipant(ParticipantView participant, boolean begin) {
        Map<String, Object> payload = ParticipantJson.frame(participant, begin);
        if (payload.isEmpty()) {
            return;
        }
        writer.emitLegacy("participant", payload);
    }

    /** {@inheritDoc} */
    @Override
    public void reasoning(String delta) {
        String text = TrialTexts.redact(delta);
        if (!text.isEmpty()) {
            writer.thinkingDelta(text);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void toolStart(String name) {
        String tool = name == null ? "" : name.trim();
        if (tool.isEmpty()) {
            return;
        }
        /*
         * 只下发工具名：前端据此先把「正在调用 xxx…」画成蓝色胶囊，
         * 随后到达的 tool 帧按名字把同一条补成成功 / 失败。
         * data 用纯文本（不是对象）——这条事件只有名字，没有其它字段。
         */
        writer.emitLegacy("tool_start", TrialTexts.redact(tool));
    }

    /** {@inheritDoc} */
    @Override
    public void tool(String name, String arguments, String result, boolean success) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String line = TrialTexts.toolDisplay(name, arguments);
        payload.put("line", success ? line : line + " · 失败");
        // 兜底清洗：正常路径下工具层已剔除标记，但 MCP 等外部实现不受我们控制。
        // 这里再扫一遍，保证用户在聊天框里永远看不到 [FILE_GENERATED] 这串机械字符。
        payload.put("result", TrialTexts.redact(GeneratedFileMarker.strip(result)));
        payload.put("success", success);
        // 工具名单独给一份：前端要把这条结果与前面那条「正在调用」胶囊配对
        if (name != null && !name.isBlank()) {
            payload.put("name", name.trim());
        }
        // 工具执行结果没有 unified 等价事件（定稿里 tool_call.completed 是「模型要求调用」），
        // 故只走旧事件名，避免语义混淆
        writer.emitLegacy("tool", payload);
    }

    /** {@inheritDoc} */
    @Override
    public void assistant(String delta) {
        String text = TrialTexts.redact(delta);
        if (!text.isEmpty()) {
            writer.messageDelta(text);
        }
    }

    /**
     * 图片文字提取结果。走独立事件名 vision，载荷是整段文本。
     * 与 tool 的区别：它没有调用行与结果的拆分，前端按「素材块」渲染即可。
     */
    @Override
    public void vision(String text) {
        String body = TrialTexts.redact(text);
        if (!body.isEmpty()) {
            writer.emitLegacy("vision", body);
        }
    }

    /**
     * 入口预处理步骤。走独立事件名 preprocess，载荷是对象（与落库 PREPROCESS 正文同形）。
     * 进行中 / 完成 / 失败都原位更新同一块，不要先收成字符串再出站。
     */
    @Override
    public void preprocess(PreprocessView view) {
        Map<String, Object> payload = PreprocessJson.frame(view);
        if (!payload.isEmpty()) {
            writer.emitLegacy("preprocess", payload);
        }
    }

    /**
     * 计划生成或状态变化。走独立事件名 plan，载荷是结构化 JSON（planId + state + steps），
     * 不塞进 assistant 正文，前端才能按步骤列表渲染而不是读一段纯文本。
     */
    @Override
    public void plan(PlanView plan) {
        if (plan == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.planId());
        payload.put("state", plan.state().name());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", step.seq());
            one.put("title", TrialTexts.redact(step.title()));
            one.put("detail", TrialTexts.redact(step.detail()));
            steps.add(one);
        }
        payload.put("steps", steps);
        payload.put("summary", TrialTexts.redact(plan.summary()));
        writer.emitLegacy("plan", payload);
    }

    /**
     * 编排根下的子计划待批准。走独立事件名 {@code ma_child_plan_pending}：
     * 根侧批准按钮必须按 route_key 定位具体子，不能复用单 Agent 的 plan 卡。
     */
    @Override
    public void childPlanPending(ChildPlanPendingView pending) {
        if (pending == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeKey", pending.routeKey());
        payload.put("childAgentId", pending.childAgentId());
        payload.put("childAgentName", TrialTexts.redact(pending.childAgentName()));
        payload.put("planId", pending.planId());
        payload.put("state", pending.state());
        payload.put("runState", pending.runState());
        payload.put("summary", TrialTexts.redact(pending.summary()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PlanStep step : pending.steps()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("seq", step.seq());
            one.put("title", TrialTexts.redact(step.title()));
            one.put("detail", TrialTexts.redact(step.detail()));
            steps.add(one);
        }
        payload.put("steps", steps);
        writer.emitLegacy("ma_child_plan_pending", payload);
    }

    /**
     * 计划单步进度。载荷与 STEP 事件正文同一套字段（seq / title / state / text）。
     */
    @Override
    public void step(StepView step) {
        if (step == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", step.seq());
        payload.put("title", TrialTexts.redact(step.title()));
        payload.put("state", step.state().name());
        payload.put("text", TrialTexts.redact(step.text()));
        payload.put("line", TrialTexts.redact(step.displayLine()));
        writer.emitLegacy("step", payload);
    }

    /**
     * 文件生成。走独立事件名 {@code file_generated}，载荷含前端直接可用的下载地址。
     *
     * <p>为什么下载地址在后端拼：fileName 可能含中文与子目录，编码规则必须与下载接口
     * 一一对应；散到前端拼迟早出现「卡片能显示、点了 404」。
     *
     * @param file 产物名与体积
     */
    @Override
    public void file(GeneratedFile file) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileName", file.fileName());
        payload.put("size", file.size());
        payload.put("downloadUrl", downloadUrl(file.fileName()));
        /*
         * 身份随文件一起出站：产物由谁写出就挂谁的卡，作者只取文件自带的那一份。
         * 缺作者时这一帧不带 id——前端会把产物留作无主，等真实作者卡出现再挂，
         * 绝不按「最后一位参与者」（常常是工作流 / 汇总结论）猜宿主。
         */
        ParticipantView owner = ownerOf(file);
        if (owner != null && !owner.id().isBlank()) {
            payload.put("participantId", owner.id());
            if (!owner.name().isBlank()) {
                payload.put("participantName", owner.name());
            }
        }
        writer.emitLegacy("file_generated", payload);
    }

    /**
     * 产物作者：**只认产生处**写进文件的那一份身份。
     *
     * <p>为什么不再退回「当前归属」：{@code lastParticipantId} 是整个 writer 共用的一格，
     * 由最后一个推归属帧的参与者改写。并行子与汇总结论交错时，它常常已经不是真正写出
     * 这个文件的人（汇总结论阶段只推「方案结论：X」，根本不切归属），于是没有作者的文件
     * 会被署成「最后一位参与者」——工作流卡就是这样被动接下了别人的文件卡，
     * 页面表现为「文字 → 文件 → 又变回文字」。
     *
     * <p>缺作者时宁可不带身份：前端会把这份产物留作无主，等真实作者卡出现再挂，
     * 不再往工作流卡兜底。
     *
     * @param file 产物
     * @return 作者身份；文件没带作者时 null（历史单专家轮次 / 工具标记产生的文件）
     */
    private ParticipantView ownerOf(GeneratedFile file) {
        if (file == null || file.participantId() == null || file.participantId().isBlank()) {
            return null;
        }
        return new ParticipantView(file.participantId(), file.participantName(),
                ParticipantView.KIND_AGENT, null, 0);
    }

    /**
     * 拼下载地址。
     *
     * <p>会话主键取当前 SSE 已绑定的值：整轮开始时 Controller 就 {@code bindSession} 过，
     * 因此在工具回调里必定可用。文件名按 UTF-8 百分号编码放进查询串，
     * 前端无需再处理中文与 {@code #}、{@code &} 之类的字符。
     *
     * @param fileName 相对工作区根的文件名
     * @return 形如 {@code /api/runtime/workspace/download?sessionId=12&fileName=report.md}
     */
    private String downloadUrl(String fileName) {
        Long id = writer.boundSession();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        return "/api/runtime/workspace/download?sessionId=" + (id == null ? "" : id) + "&fileName=" + encoded;
    }

    /**
     * 本轮需要用户拍板的结构化选项。走独立事件名 {@code choice}，载荷与落库 CHOICE 事件正文同形
     * （question + options[{id,label}]）。前端只按它渲染 chips，正文里不出现裸 JSON。
     */
    @Override
    public void choice(AgentChoice choice) {
        String payload = WorkbenchChoiceJson.write(choice);
        if (!payload.isEmpty()) {
            writer.emitLegacy("choice", payload);
        }
    }

    /**
     * 工作流执行卡片：走独立事件名 {@code workflow}，载荷是整份视图（对象形态）。
     *
     * <p>为什么不用字符串载荷：出站层对字符串会再 JSON 编码一次，前端拿到的就是一段
     * 需要二次解析的字符串；这里直接给 Map，帧就是前端可以直接读的对象。
     */
    @Override
    public void workflow(WorkflowRunView view) {
        Map<String, Object> payload = WorkflowRunJson.payload(view);
        if (payload.isEmpty()) {
            return;
        }
        writer.emitLegacy("workflow", payload);
    }

    /** {@inheritDoc} */
    @Override
    public void done(MessageResp resp) {
        writer.bindSession(resp == null ? null : resp.getSessionId());
        // 业务已给出结论：按 resp 的状态判定结束原因，而不是无条件当成功
        boolean failed = resp != null && "FAILED".equalsIgnoreCase(resp.getStatus());
        UnifiedFinishReason reason = failed ? UnifiedFinishReason.ERROR : UnifiedFinishReason.STOP;
        String summary = resp == null ? null : resp.getErrorSummary();
        // 停在待批准不是异常结束：额外带一个标记，前端据此渲染批准/取消按钮而不是「失败」
        writer.finish(reason, null, summary, resp != null && resp.isPlanPending());
    }

    /** {@inheritDoc} */
    @Override
    public void fail(String message) {
        writer.finishError(message == null || message.isBlank() ? "试跑失败" : message);
    }
}
