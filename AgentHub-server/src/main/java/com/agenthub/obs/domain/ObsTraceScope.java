package com.agenthub.obs.domain;

import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.StepNoAllocator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次提问的埋点范围。所属域：obs。
 * seq 用原子自增，避免模型回调和工具回调抢号。
 * 每线程独立 {@link ObsSpanStack}：Router 多子并行互不抢父；LLM/TOOL 捕获 stack 引用后换线程仍可读。
 * 串行 LLM/TOOL/RETRIEVE：open 成功后推进当前叶，形成 AGENT→LLM→TOOL→LLM 链；并行扇出不推进。
 * step_no 由本 trace 内的 {@link StepNoAllocator} 发号并落库。
 */
public final class ObsTraceScope {

    private final String traceId;
    private final TraceRecorder recorder;
    private final AtomicInteger seq;
    private final StepNoAllocator stepNos;
    /** 已落库步骤 id → step_no，供 bindParent 写入 stack */
    private final ConcurrentHashMap<Long, String> stepNoById = new ConcurrentHashMap<>();
    private final ThreadLocal<ObsSpanStack> stacks = ThreadLocal.withInitial(ObsSpanStack::new);
    /**
     * 本 trace 最近一次成功打开的 LLM 行 id。
     * 并行外壳会互相覆盖它，扇出挂点不要读这个，改读 {@link #llmUnder(Long)}。
     */
    private final AtomicReference<Long> lastLlmSpanId = new AtomicReference<>();
    /** 与 {@link #lastLlmSpanId} 成对：那条 LLM 落库时的父 id，供按层保存 / 写回 */
    private final AtomicReference<Long> lastLlmParentId = new AtomicReference<>();
    /**
     * 父步骤 id → 挂在它下面的最近一条 LLM。
     * 为什么按父而不是按 trace：多问并行时工作流线程会改写全局最近 LLM，
     * 内层专家必须仍能找到「父是本层 ROUTE」的那条，而不是入口判路由那条。
     */
    private final ConcurrentHashMap<Long, Long> llmByParent = new ConcurrentHashMap<>();
    /**
     * 工具调用 id → TOOL 行 id。
     * <p>为什么需要：知识库检索等工具执行在 {@code boundedElastic} 线程，拿到本次 TOOL 行 id
     * 只能靠引擎给的 toolCallId 反查；有了它，检索子步才能稳定挂到「本次工具调用」下。
     */
    private final ConcurrentHashMap<String, Long> toolSpanByCallId = new ConcurrentHashMap<>();
    /** 最近一次成功打开的 TOOL 行 id；callId 缺失或事件尚未落库时的兜底挂点 */
    private final AtomicReference<Long> lastToolSpanId = new AtomicReference<>();
    /**
     * 只活在这一轮里的旁路数据（例如同轮知识库检索复用）。
     * 下一轮是新的 scope，{@link #clearTurnSlots()} 再兜住「同一个对象被拿去复用」。
     */
    private final ConcurrentHashMap<String, Object> turnSlots = new ConcurrentHashMap<>();

    /**
     * 全新 trace：seq 与 step_no 都从 1 起号。
     *
     * @param traceId  业务 traceId
     * @param recorder 实际落库。失败由实现吞掉
     */
    public ObsTraceScope(String traceId, TraceRecorder recorder) {
        this(traceId, recorder, 0, 0);
    }

    /**
     * 续跑已有 trace：seq / step_no 从库里已用过的最大号接着发。
     * <p>为什么需要：工作流 run 可能在 HUMAN 节点暂停，恢复调度时是同一条 trace，
     * 若不接号会出现两个 step「1」，面板排序会乱。
     *
     * @param traceId       业务 traceId
     * @param recorder      实际落库。失败由实现吞掉
     * @param initialSeq    已用过的最大 seq；小于 0 按 0 处理
     * @param initialSerial 已用过的最大串行 step 号；小于 0 按 0 处理
     */
    public ObsTraceScope(String traceId, TraceRecorder recorder, int initialSeq, int initialSerial) {
        this.traceId = traceId;
        this.recorder = recorder;
        this.seq = new AtomicInteger(Math.max(initialSeq, 0));
        this.stepNos = new StepNoAllocator(initialSerial);
    }

    /**
     * @return 业务 traceId
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 同一条 trace 内从 1 开始的下一个序号。
     *
     * @return 新序号
     */
    public int allocateSeq() {
        return seq.incrementAndGet();
    }

    /**
     * 当前线程子链的 stack；并行子应在开跑时拿到并交给 MeteringModel / TrialSpanSink。
     *
     * @return 非空 stack
     */
    public ObsSpanStack stack() {
        return stacks.get();
    }

    /**
     * 为本步分配 step_no（写入前调用）。
     *
     * @param parentId       父行 id
     * @param parentStepNo   父步展示号
     * @param parallelFanout 是否并行扇出
     * @return 非空展示号
     */
    public String allocateStepNo(Long parentId, String parentStepNo, boolean parallelFanout) {
        return stepNos.next(parentId, parentStepNo, parallelFanout);
    }

    /**
     * insert 回填 id 后登记 step_no，供后续 bind。
     *
     * @param id     行主键
     * @param stepNo 展示号
     */
    public void registerStepNo(Long id, String stepNo) {
        if (id != null && stepNo != null && !stepNo.isBlank()) {
            stepNoById.put(id, stepNo);
        }
    }

    /**
     * @param id 步骤主键
     * @return 已登记的 step_no；未知为空
     */
    public String stepNoOf(Long id) {
        return id == null ? null : stepNoById.get(id);
    }

    /**
     * @return 当前线程 stack 上的父行 id；还没有时为 null
     */
    public Long implicitParent() {
        return stacks.get().parentId();
    }

    /**
     * 本 trace 最近一次成功打开的 LLM 行 id。
     * 扇出挂点请用 {@link #llmUnder(Long)}：并行时后开的 LLM 会覆盖本值。
     *
     * @return LLM 行主键；尚未开过 LLM 时为 null
     */
    public Long lastLlmSpanId() {
        return lastLlmSpanId.get();
    }

    /**
     * 取挂在指定父步骤下的最近一条 LLM。
     * 内层子专家只认「父是本层 ROUTE」的模型步；别的层后写入的 LLM 不会顶掉这一条。
     *
     * @param parentSpanId 本层 ROUTE（或其它容器）的 span id
     * @return 该父下最近的 LLM id；没有或父为空时为 null
     */
    public Long llmUnder(Long parentSpanId) {
        if (parentSpanId == null) {
            return null;
        }
        return llmByParent.get(parentSpanId);
    }

    /**
     * 记下当前全局 LLM 指针，供进入目标方案前保存、出来后写回。
     * 不碰 {@link #llmByParent}：按父索引在嵌套期间仍要能查到外层判路由 LLM。
     *
     * @return 当时的 LLM id 与其父 id；都还没有时两个字段均为 null
     */
    public LlmCursor captureLlmCursor() {
        return new LlmCursor(lastLlmSpanId.get(), lastLlmParentId.get());
    }

    /**
     * 写回先前记下的全局 LLM 指针。传入 null 表示清掉，让内层在开出自己的模型步之前看不到外层。
     *
     * @param cursor {@link #captureLlmCursor()} 的结果；可空
     */
    public void restoreLlmCursor(LlmCursor cursor) {
        if (cursor == null) {
            lastLlmSpanId.set(null);
            lastLlmParentId.set(null);
            return;
        }
        lastLlmSpanId.set(cursor.spanId());
        lastLlmParentId.set(cursor.parentId());
    }

    /**
     * 一次全局 LLM 指针的快照。所属域：obs。
     *
     * @param spanId   LLM 行 id，可空
     * @param parentId 该 LLM 落库时的父 id，可空
     */
    public record LlmCursor(Long spanId, Long parentId) {
    }

    /**
     * 登记「本次工具调用 id → TOOL 行 id」。
     * <p>调用方：TrialSpanSink.onStart（同一工具调用的入参事件）；执行线程可据此反查父 span。
     *
     * @param toolCallId 引擎侧工具调用 id；空则忽略
     * @param spanId     本次 TOOL 行 id；空则忽略
     */
    public void registerToolSpan(String toolCallId, Long spanId) {
        if (toolCallId == null || toolCallId.isBlank() || spanId == null) {
            return;
        }
        toolSpanByCallId.put(toolCallId, spanId);
    }

    /**
     * 按工具调用 id 取 TOOL 行 id。
     * <p>有 callId 时只认精确登记：登记缺失说明本次 TOOL 行没落成，
     * 这时绝不能回退到「上一条 TOOL」，否则会把检索子步挂到别的工具下。
     *
     * @param toolCallId 引擎侧工具调用 id；可空
     * @return 对应行 id；callId 为空时回退最近一次 TOOL 行，仍无则 null
     */
    public Long toolSpanOf(String toolCallId) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            return toolSpanByCallId.get(toolCallId);
        }
        return lastToolSpanId.get();
    }

    /**
     * 工具调用收尾时清理登记，避免长会话 map 无限增长。
     *
     * @param toolCallId 引擎侧工具调用 id；空则忽略
     */
    public void forgetToolSpan(String toolCallId) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            toolSpanByCallId.remove(toolCallId);
        }
    }

    /**
     * 读取本轮槽。没有则 null。所属域：obs。
     * <p>
     * 槽只挂在当前这条 trace 上，不进全局表。调用方用固定名字存放自己的本轮数据。
     *
     * @param name 槽名；空则 null
     * @return 槽里的值；没有则为 null
     */
    public Object turnSlot(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return turnSlots.get(name);
    }

    /**
     * 槽还没有值时放入。并发下保留先写入的那份。
     *
     * @param name  槽名；空则忽略
     * @param value 要放入的值；空则忽略
     * @return 槽里最终的值；名字或值为空时为 null
     */
    public Object putTurnSlotIfAbsent(String name, Object value) {
        if (name == null || name.isBlank() || value == null) {
            return null;
        }
        Object raced = turnSlots.putIfAbsent(name, value);
        return raced == null ? value : raced;
    }

    /**
     * 本轮结束，丢掉只应活在这一轮里的数据。
     * <p>
     * 下一条用户消息会新开 scope，本来就看不到这里的内容。
     * 这里再清一次，是为了轮结束当下就失效，避免同一个 scope 被继续拿去查。
     */
    public void clearTurnSlots() {
        turnSlots.clear();
    }

    /**
     * 接下来的 LLM / TOOL 挂到这个步骤下面（仅当前线程的 stack）。
     *
     * @param spanId 父行自增 id，可空表示回到根
     */
    public void bindParent(Long spanId) {
        ObsSpanStack stack = stacks.get();
        if (spanId == null) {
            stack.bind(null, null);
        } else {
            stack.bind(spanId, stepNoById.get(spanId));
        }
    }

    /**
     * 打开一个 RUNNING 步骤。agentId 为空时沿用当前 stack 的 Agent。
     *
     * @param type     步骤类型
     * @param name     展示名
     * @param agentId  本步 Agent，可空
     * @param parentId 父行 id，可空
     * @param inputText 已有输入，可空
     * @return 行主键 id；写入失败时为 null
     */
    public Long open(SpanType type, String name, Long agentId, Long parentId, String inputText) {
        return open(type, name, agentId, parentId, inputText, false);
    }

    /**
     * 同 {@link #open(SpanType, String, Long, Long, String)}，可标记并行扇出以发 N.1 / N.2。
     *
     * @param parallelFanout 同父并行扇出
     * @return 行主键 id；写入失败时为 null
     */
    public Long open(SpanType type, String name, Long agentId, Long parentId, String inputText,
                     boolean parallelFanout) {
        ObsSpanStack stack = stacks.get();
        if (agentId != null) {
            stack.rememberAgent(agentId);
        }
        Long owner = agentId != null ? agentId : stack.stepAgentId();
        // 显式父优先；没传时与落库口径一致，用当前栈父（LLM/TOOL/RETRIEVE 会回落到它）
        Long effectiveParent = parentId != null ? parentId : stack.parentId();
        Long id = recorder.openSpan(this, new SpanDraft(type, name, owner, parentId, inputText, parallelFanout));
        // 仅成功落库的 LLM 计入：全局指针给「按层保存」，按父索引给子专家挂点
        if (id != null && type == SpanType.LLM) {
            lastLlmSpanId.set(id);
            lastLlmParentId.set(effectiveParent);
            if (effectiveParent != null) {
                llmByParent.put(effectiveParent, id);
            }
        }
        // TOOL 行也留一个「最近一次」兜底：个别引擎事件不带 callId 时，工具内子步仍能挂上
        if (id != null && type == SpanType.TOOL) {
            lastToolSpanId.set(id);
        }
        // 串行业务步：开成功后把当前叶推进到本步，下一步挂本步 id，禁止钉死容器 AGENT
        // 真并行扇出（parallelFanout）不推进，兄弟仍同挂分叉点
        if (id != null && !parallelFanout && advancesSerialLeaf(type)) {
            stack.bind(id, stepNoById.get(id));
        }
        return id;
    }

    /**
     * LLM / TOOL / RETRIEVE 参与 ReAct 串行链；容器步（AGENT/ROUTE/PLAN）仍靠显式 bindParent。
     *
     * @param type 步骤类型
     * @return 是否在 open 后推进当前叶
     */
    private static boolean advancesSerialLeaf(SpanType type) {
        return type == SpanType.LLM || type == SpanType.TOOL || type == SpanType.RETRIEVE;
    }

    /**
     * 工作线程结束时摘掉本线程 stack 引用，避免池化线程串味。
     * 不清空已被 MeteringModel 捕获的 stack 对象内容，以免迟到回调误伤。
     */
    public void clearThreadLocals() {
        stacks.remove();
    }

    /**
     * 结束一个步骤。id 为空或已经不是 RUNNING 时什么都不做。
     *
     * @param spanId 行主键
     * @param close  终态与分列正文
     */
    public void close(Long spanId, SpanClose close) {
        recorder.closeSpan(this, spanId, close);
    }

    /**
     * 给已结束的步骤补写 attrs 字段（如投票 winner / 降级标记）。
     *
     * <p>只加不删；spanId 为空或 trace 未启用时静默跳过，埋点失败不影响主流程。
     *
     * @param spanId 行主键
     * @param fields 追加字段；空则忽略
     */
    public void amendAttrs(Long spanId, Map<String, Object> fields) {
        if (spanId == null || fields == null || fields.isEmpty()) {
            return;
        }
        recorder.amendAttrs(this, spanId, fields);
    }

    /**
     * 简短收尾：只写 output（失败时同时作 errorSummary）。
     *
     * @param spanId 行主键
     * @param status 终态
     * @param output 输出摘要，可空
     */
    public void close(Long spanId, ObsStatus status, String output) {
        close(spanId, SpanClose.ofOutput(status, output));
    }
}
