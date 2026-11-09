package com.agenthub.runtime.api.sse;

import com.agenthub.runtime.domain.service.TrialTexts;
import com.agenthub.runtime.model.acl.UnifiedFinishReason;
import com.agenthub.runtime.model.acl.UnifiedMessage;
import com.agenthub.runtime.model.acl.UnifiedStreamEvent;
import com.agenthub.runtime.model.acl.UnifiedStreamEventType;
import com.agenthub.runtime.model.acl.UnifiedToolCall;
import com.agenthub.runtime.model.acl.UnifiedUsage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * SSE 出站统一事件写入器。所属域：runtime（出站层）。
 * 核心职责：把 {@link UnifiedStreamEvent} 编成 SSE 帧，并保证 <b>一次流有且仅有一帧结束</b>。
 * done 兜底覆盖定稿 §4.2 的五条路径，全部收敛到 {@link #doneSent} 的 CAS 上：
 * <ol>
 *   <li>上游已映射为 done：{@link #emit} 收到 DONE 类型时占位；</li>
 *   <li>上游流结束但缺结束标识：{@link #finish} 由调用方在流结束后无条件调用；</li>
 *   <li>读超时 / 墙钟超时：{@link #finishTimeout}；</li>
 *   <li>上游错误且无后续 chunk：{@link #finishError}，先 error 再 done；</li>
 *   <li>客户端取消 / 断开：{@link #finishCancelled}，会真实尝试写一帧 done 再完成 emitter —
 *       这是 Adapter 层做不到的部分，Adapter 取消后已无接收方，只有出站层才知道连接是否还活着。</li>
 * </ol>
 * 并发：Router 多子 Agent 并行时多线程会同时推增量，故所有写出与状态判定都在本对象的锁内完成。
 *
 * <p>正文合批（性能刀6）：assistant / reasoning 这类纯文本增量先进 {@link SseTextBatcher}，
 * 按「时间窗 + 单帧字节上限」合并成一帧再写，降低帧频率但<b>不改事件名、字段与文本顺序</b>。
 * 控制帧（phase / route / tool / plan / step / file / vision / done / error）写出前一律先 flush，
 * 收尾与取消路径也强制 flush，因此尾字不会丢、卡片不会插进半句话。
 */
public final class UnifiedSseWriter {

    /** 旧前端 done 载荷里表示成功的 status 值，与 MessageResp.status 口径一致 */
    private static final String STATUS_SUCCESS = "SUCCESS";
    /** 旧前端 done 载荷里表示失败的 status 值 */
    private static final String STATUS_FAILED = "FAILED";

    private final SseFrameSink sink;
    private final ObjectMapper json;
    /**
     * done 是否已发。用 CAS 而不是普通 boolean：五条路径可能在不同线程上竞争
     * （上游完成线程、超时调度线程、容器断连回调），必须只允许一条路径写出结束帧。
     */
    private final AtomicBoolean doneSent = new AtomicBoolean(false);

    /** 会话主键，写进旧前端 done 载荷，前端据此决定是否刷新历史 */
    private Long sessionId;
    /** 已见到的用量，done 载荷兜底时带上 */
    private UnifiedUsage usage;
    /** 本轮是否已向旧前端发过 error，避免 done 兜底时重复报同一个错 */
    private boolean errorEmitted;

    /**
     * 正文增量合批器。只收 assistant / reasoning 这类纯文本增量；
     * 所有控制帧在写出前都会先 flush 它，因此帧的相对顺序与改造前一致。
     */
    private final SseTextBatcher textBatcher;

    /**
     * @param sink 帧写出目标
     * @param json 序列化器，复用容器配置
     */
    public UnifiedSseWriter(SseFrameSink sink, ObjectMapper json) {
        this(sink, json, SseBatchOptions.defaults());
    }

    /**
     * @param sink  帧写出目标
     * @param json  序列化器，复用容器配置
     * @param batch 正文合批参数（时间窗 + 单帧字节上限）
     */
    public UnifiedSseWriter(SseFrameSink sink, ObjectMapper json, SseBatchOptions batch) {
        this(sink, json, batch, System::nanoTime);
    }

    /**
     * 全参构造，供单测注入可控时钟。生产一律走上面两个构造。
     *
     * @param sink      帧写出目标
     * @param json      序列化器
     * @param batch     合批参数
     * @param nanoClock 纳秒时钟
     */
    UnifiedSseWriter(SseFrameSink sink, ObjectMapper json, SseBatchOptions batch, LongSupplier nanoClock) {
        this.sink = sink;
        this.json = json;
        // 合批写出走本对象的 write，队列里的文本先拼成一帧；两个帧（新名 + 旧名）在此一次性发出
        this.textBatcher = new SseTextBatcher(batch, nanoClock, this::writeBatchedText);
    }

    /**
     * 记录会话主键，供结束帧的旧字段使用。
     *
     * @param sessionId 会话主键，可空
     */
    public synchronized void bindSession(Long sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 当前已绑定的会话主键。工具回调里拼下载地址要用它——
     * 那时代码已经离开 Controller 的栈帧，拿不到原始请求了。
     *
     * @return 会话主键；尚未绑定时为 null
     */
    public synchronized Long boundSession() {
        return sessionId;
    }

    /**
     * 是否已发出结束帧。调用方可用它在收尾时提前判断，避免无谓的兜底尝试。
     *
     * @return true 表示结束帧已写出
     */
    public boolean isDone() {
        return doneSent.get();
    }

    /**
     * 输出一条统一事件。旧前端可读的事件会额外发一条旧名帧，保证兼容期内前端行为不变。
     * 收到 DONE 时按「路径 1」处理：占位结束帧并发出，后续所有兜底都会被 CAS 挡掉。
     *
     * <p>非正文事件写出前一定先 flush 合批器：控制帧不允许越过还没发出去的正文，
     * 否则前端会把工具卡/计划卡插到半句话中间。
     *
     * <p>正文合批只作用于 {@link #messageDelta} / {@link #thinkingDelta}（现网试跑的真实热路径）；
     * 经本方法直接传进来的正文事件仍立即写出，不额外缓冲，语义与改造前一致。
     *
     * @param event 统一事件
     * @return true 表示至少有一条帧写出成功；false 表示连接已不可用或无需写出
     */
    public synchronized boolean emit(UnifiedStreamEvent event) {
        if (event == null || event.getType() == null) {
            return false;
        }
        // 结束帧走专用路径，保证 CAS 与旧载荷形状统一
        if (event.getType() == UnifiedStreamEventType.DONE) {
            return emitDone(event.getFinishReason(), event.getUsage(), null);
        }
        // 结束帧之后不再写任何业务帧：前端已收尾，继续写会污染下一轮或造成重复渲染
        if (doneSent.get()) {
            return false;
        }
        // 先把攒着的正文落地，再写这一帧（正文 / 控制帧的相对顺序与逐 chunk 时一致）
        textBatcher.flush();
        if (event.getUsage() != null) {
            usage = event.getUsage();
        }
        if (event.getType() == UnifiedStreamEventType.ERROR) {
            errorEmitted = true;
            // error 的旧新事件名相同（都是 error），而旧前端要求 data 是字符串。
            // 因此只发一帧字符串形态：结构化的 finishReason / message 由随后的 done 承载，
            // 这样旧前端不退化，新前端也能从 done 拿到完整语义。
            return writeLegacyError(event.getContent());
        }
        return writeEventFrames(event);
    }

    /**
     * 写一条业务事件对应的新旧两帧。
     *
     * @param event 统一事件（非 ERROR / 非 DONE）
     * @return 是否至少有一帧写出成功
     */
    private boolean writeEventFrames(UnifiedStreamEvent event) {
        boolean ok = writeUnified(event);
        // 旧名兼容帧：只对旧前端认识的类型补发；新旧同名时上面已合并，不能再写第二遍
        UnifiedSseMapping.Mapping mapping = UnifiedSseMapping.of(event.getType());
        if (mapping != null && mapping.legacyEvent() != null
                && !mapping.legacyEvent().equals(mapping.unifiedEvent())) {
            ok |= writeLegacy(mapping, event);
        }
        return ok;
    }

    /**
     * 合批器回调：把攒好的一段正文写成「新名 + 旧名」两帧。
     *
     * <p>由 {@link SseTextBatcher#flush()} 在写入器的锁内调用，因此不会被控制帧穿插。
     * 合并只发生在帧的粒度上，文本内容与顺序与逐 chunk 写出完全一致。
     *
     * @param kind 正文类型（助手终答 / 思考）
     * @param text 本帧合并后的完整文本
     */
    private void writeBatchedText(SseTextBatcher.Kind kind, String text) {
        if (text == null || text.isEmpty() || doneSent.get()) {
            return;
        }
        UnifiedStreamEvent event = kind == SseTextBatcher.Kind.REASONING
                ? UnifiedStreamEvent.thinkingDelta(text)
                : UnifiedStreamEvent.messageDelta(text);
        writeEventFrames(event);
    }

    /**
     * 写旧前端可读的 error 帧。data 是 JSON 字符串（序列化后带引号），
     * 旧解析器 JSON.parse 后得到 string，与现网行为完全一致。
     *
     * @param text 错误摘要
     * @return 是否写出成功
     */
    private boolean writeLegacyError(String text) {
        String safe = TrialTexts.redact(text == null || text.isBlank() ? "试跑失败" : text);
        return write("error", safe);
    }

    /**
     * 路径 2：上游流正常结束（含缺结束标识的截断）。
     * 调用方应在流结束后无条件调用一次；若上游已发过 done，本方法不产生任何帧。
     *
     * @param reason   结束原因；上游未给出时传 null，将按 ERROR 兜底而不是伪装成成功
     * @param usage    本轮用量，可空
     * @param summary  附加说明（如截断提示），可空；非空时写进 done 载荷
     * @return true 表示本次调用真的写出了结束帧
     */
    public synchronized boolean finish(UnifiedFinishReason reason, UnifiedUsage usage, String summary) {
        return emitDone(reason, usage, summary, false);
    }

    /**
     * 同 {@link #finish}，可附带「本轮停在待批准计划」标记。
     * 标记只写进 done 载荷，不改变结束原因：它既不是失败也不是取消，前端据此显示人工介入按钮。
     *
     * @param reason      结束原因
     * @param usage       本轮用量，可空
     * @param summary     附加说明，可空
     * @param planPending 是否停在待批准计划
     * @return true 表示本次调用真的写出了结束帧
     */
    public synchronized boolean finish(UnifiedFinishReason reason, UnifiedUsage usage, String summary,
                                       boolean planPending) {
        return emitDone(reason, usage, summary, planPending);
    }

    /**
     * 路径 3：读超时 / 墙钟超时。
     *
     * @param summary 超时说明，会脱敏后再下发
     * @return true 表示本次写出了结束帧
     */
    public synchronized boolean finishTimeout(String summary) {
        return emitDone(UnifiedFinishReason.TIMEOUT, null,
                summary == null || summary.isBlank() ? "模型响应超时" : summary);
    }

    /**
     * 路径 4：上游错误且不会再有后续 chunk。先补 error（若尚未发过），再发 done。
     *
     * @param summary 已脱敏的错误摘要
     * @return true 表示本次写出了结束帧
     */
    public synchronized boolean finishError(String summary) {
        String text = summary == null || summary.isBlank() ? "试跑失败" : summary;
        // 错误提示前先把攒着的正文发出去：否则 error 会插在半句话前面，用户以为回答被截断
        textBatcher.flush();
        // 错误提示只发一次：避免 error 事件与兜底路径各发一遍，前端弹两次
        if (!errorEmitted && !doneSent.get()) {
            emitErrorFrame(text);
        }
        return emitDone(UnifiedFinishReason.ERROR, null, text);
    }

    /**
     * 路径 5：客户端取消 / 断开。
     * 这是本层独有的能力：Adapter 取消后其下游已无接收方，无法补帧；
     * 出站层仍持有 HTTP 连接引用，可以真实尝试写一帧 done 再完成 emitter。
     * 注意：若客户端已彻底断开，写出会失败——此时仍返回 false，但 CAS 已置位，不会重复尝试。
     *
     * @return true 表示结束帧确实写进了连接；false 表示连接已断或此前已发过
     */
    public synchronized boolean finishCancelled() {
        return emitDone(UnifiedFinishReason.CANCELLED, null, "客户端已取消");
    }

    /**
     * 统一结束帧出口。CAS 保证只发一次。
     *
     * @param reason  结束原因，为 null 时按 ERROR 处理（不伪装成功）
     * @param usage   用量，可空；为空时用此前记录的值
     * @param summary 附加说明，可空
     * @return true 表示本次写出了结束帧
     */
    private boolean emitDone(UnifiedFinishReason reason, UnifiedUsage usageOverride, String summary) {
        return emitDone(reason, usageOverride, summary, false);
    }

    /**
     * 统一结束帧出口（可带待批准标记）。CAS 保证只发一次。
     *
     * @param reason      结束原因，为 null 时按 ERROR 处理（不伪装成功）
     * @param usage       用量，可空；为空时用此前记录的值
     * @param summary     附加说明，可空
     * @param planPending 本轮是否停在待批准计划
     * @return true 表示本次写出了结束帧
     */
    private boolean emitDone(UnifiedFinishReason reason, UnifiedUsage usageOverride, String summary,
                             boolean planPending) {
        // 已收尾：不再 flush、不再写帧（尾字在第一次收尾时已经落地过）
        if (doneSent.get()) {
            return false;
        }
        // 收尾前必须把缓冲里的正文全部落地，否则尾字会随连接一起丢。
        // 注意顺序：必须在 CAS 之前——CAS 置位后 doneSent=true，合批写出会把自己否决掉。
        textBatcher.flush();
        // CAS 是唯一闸门：五条路径无论谁先到，只有第一个能继续写帧
        if (!doneSent.compareAndSet(false, true)) {
            return false;
        }
        UnifiedFinishReason settled = reason == null ? UnifiedFinishReason.ERROR : reason;
        UnifiedUsage effectiveUsage = usageOverride != null ? usageOverride : usage;
        // done 的新旧事件名都是 done：只能写一帧，否则前端收到两次结束会重复收尾。
        // 载荷取并集：status/sessionId/errorSummary 满足旧前端，finishReason/usage 满足新契约。
        boolean ok = writeLegacyDone(settled, effectiveUsage, summary, planPending);
        // 无论写成功与否都收口：继续持有连接只会让客户端多等一个超时
        sink.complete();
        return ok;
    }

    /**
     * 写旧前端 done 帧：保持 status / sessionId / errorSummary 三个原有字段，
     * 并额外附带 finishReason 供新前端读取（旧前端忽略未知字段，不会受影响）。
     * 这就是 done 的唯一一帧，新旧契约靠同一帧的字段并集共存。
     *
     * @return 是否写出成功
     */
    private boolean writeLegacyDone(UnifiedFinishReason reason, UnifiedUsage usageValue, String summary,
                                    boolean planPending) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // 旧前端只区分 FAILED 与非 FAILED；降级结束必须映射成 FAILED，否则会被当成正常完成
        payload.put("status", "SUCCESS".equals(UnifiedSseMapping.legacyStatus(reason))
                ? STATUS_SUCCESS : STATUS_FAILED);
        payload.put("sessionId", sessionId);
        payload.put("errorSummary", summary == null ? "" : TrialTexts.redact(summary));
        // 新字段：旧解析器忽略，新前端可据此区分截断/取消/超时
        payload.put("finishReason", reason.name());
        if (usageValue != null) {
            payload.put("usage", usagePayload(usageValue));
        }
        // 待批准计划：仅计划执行模式会出现，旧前端忽略该字段不受影响
        if (planPending) {
            payload.put("planPending", true);
        }
        return write("done", payload);
    }

    /**
     * 写统一格式的业务帧。
     *
     * @return 是否写出成功
     */
    private boolean writeUnified(UnifiedStreamEvent event) {
        String eventName = event.getType().code();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", eventName);
        if (event.getContent() != null) {
            payload.put("content", TrialTexts.redact(event.getContent()));
        }
        if (event.getToolCallDelta() != null) {
            payload.put("toolCall", toolCallPayload(event.getToolCallDelta()));
        }
        if (event.getUsage() != null) {
            payload.put("usage", usagePayload(event.getUsage()));
        }
        if (event.getExtensions() != null && !event.getExtensions().isEmpty()) {
            payload.put("extensions", event.getExtensions());
        }
        return write(eventName, payload);
    }

    /**
     * 写旧前端的兼容帧。载荷形状严格按现网前端解析器的要求：
     * assistant / reasoning / error 三类是「JSON 字符串」，前端按 string 处理。
     * 注意：tool_call.* 刻意没有旧等价物——旧 tool 事件语义是「工具已执行完 + 结果」，
     * 与 tool_call.completed「模型要求调用某工具 + 参数」完全不同，强行映射会让界面显示错误内容。
     *
     * @return 是否写出成功
     */
    private boolean writeLegacy(UnifiedSseMapping.Mapping mapping, UnifiedStreamEvent event) {
        // 旧解析器对这些事件要求 data 为字符串：序列化成 JSON 字符串字面量后由前端 JSON.parse 得到 string
        String text = TrialTexts.redact(event.getContent() == null ? "" : event.getContent());
        return write(mapping.legacyEvent(), text);
    }

    /**
     * 补发一条 error 帧，用于兜底前告知前端失败原因。
     * error 的新旧事件名相同，故只发一帧字符串形态（同 {@link #writeLegacyError}）。
     */
    private void emitErrorFrame(String text) {
        errorEmitted = true;
        writeLegacyError(text);
    }

    /**
     * 实际写出：序列化失败或连接不可用都不抛异常。
     * 出站层绝不因为写帧失败而中断业务线程，业务侧还有落库要完成。
     *
     * @return 是否写出成功
     */
    private boolean write(String event, Object data) {
        try {
            return sink.write(event, json.writeValueAsString(data));
        } catch (JsonProcessingException ex) {
            // 序列化失败说明载荷含无法表达的对象，标记连接不可用避免继续写半截
            return false;
        }
    }

    /**
     * @return 工具调用的出站载荷，参数保持原始字符串，不在出站层解析
     */
    private Map<String, Object> toolCallPayload(UnifiedToolCall call) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", call.getId());
        payload.put("name", call.getName());
        if (call.getArguments() != null) {
            payload.put("arguments", TrialTexts.redactArgs(call.getArguments()));
        }
        return payload;
    }

    /**
     * @return 用量载荷；缺失字段不补 0
     */
    private Map<String, Object> usagePayload(UnifiedUsage usageValue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("prompt", usageValue.getPrompt());
        payload.put("completion", usageValue.getCompletion());
        payload.put("total", usageValue.getTotal());
        return payload;
    }

    /**
     * 便捷构造：把一条助手正文增量交给合批器。
     * 供仍按旧回调工作的调用方（TrialProgress 实现）直接复用，避免各处重复 builder。
     *
     * <p>不再「一个 chunk 一帧」：同一类型连续增量会在时间窗 / 字节上限内合成一帧，
     * 控制帧与收尾路径都会先 flush，因此调用方不需要关心合批细节。
     *
     * @param text 正文片段；空串直接忽略
     * @return true 表示已收下（可能仍在缓冲里）；false 表示本轮已收尾，帧会被丢弃
     */
    public synchronized boolean messageDelta(String text) {
        return queueText(SseTextBatcher.Kind.ASSISTANT, text);
    }

    /**
     * 便捷构造：思考增量。语义同 {@link #messageDelta}，只是类型为 reasoning。
     *
     * @param text 思考片段；空串直接忽略
     * @return true 表示已收下（可能仍在缓冲里）
     */
    public synchronized boolean thinkingDelta(String text) {
        return queueText(SseTextBatcher.Kind.REASONING, text);
    }

    /**
     * 把一段正文交进合批缓冲。
     *
     * @param kind 正文类型
     * @param text 片段文本，空串忽略
     * @return true 表示已收下；false 表示已收尾或片段为空
     */
    private boolean queueText(SseTextBatcher.Kind kind, String text) {
        if (text == null || text.isEmpty() || doneSent.get()) {
            return false;
        }
        textBatcher.accept(kind, text);
        return true;
    }

    /**
     * 下发当前阶段。定稿 §4.1 未定义 phase 的统一类型，故本方法只发旧事件名，
     * 不臆造 unified 语义；待前端迁移到 unified 后由该期 REQ 决定是否新增类型。
     *
     * @param phase planning / reasoning / tool / answering 等
     * @return true 表示写出成功
     */
    public synchronized boolean emitPhase(String phase) {
        if (doneSent.get()) {
            return false;
        }
        // 阶段切换是控制帧：先让正文落地，状态条与正文的先后关系不会错
        textBatcher.flush();
        return write("phase", phase);
    }

    /**
     * 下发 Router 判定摘要。同 {@link #emitPhase}：暂只走旧事件名。
     *
     * @param summary 已脱敏的路由摘要
     * @return true 表示写出成功
     */
    public synchronized boolean emitRoute(String summary) {
        if (doneSent.get()) {
            return false;
        }
        // 路由摘要同样是控制帧：先 flush 正文，时间线里「正文 → 路由」的顺序不乱
        textBatcher.flush();
        return write("route", summary);
    }

    /**
     * 下发一条纯旧格式事件（没有 unified 等价物时使用，例如工具执行结果）。
     * 结束帧之后不再下发，避免污染已收尾的流。
     *
     * @param event 旧事件名
     * @param data  已构造好的载荷对象
     * @return true 表示写出成功
     */
    public synchronized boolean emitLegacy(String event, Object data) {
        if (doneSent.get()) {
            return false;
        }
        // 工具卡 / 计划卡 / 步骤 / 产物 / 图片文字都在这里出站：先 flush 正文，卡片不会插进半句话
        textBatcher.flush();
        return write(event, data);
    }
}
