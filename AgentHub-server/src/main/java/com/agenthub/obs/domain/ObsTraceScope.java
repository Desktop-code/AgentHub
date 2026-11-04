package com.agenthub.obs.domain;

import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.StepNoAllocator;

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
    /** 本 trace 最近一次成功打开的 LLM 行 id；Router 扇出前可读作判路由 LLM */
    private final AtomicReference<Long> lastLlmSpanId = new AtomicReference<>();

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
     * Router 在判路由结束后、子 AGENT 扇出前读取，作为子挂点；无判路由 LLM（关键字短路等）为空。
     *
     * @return LLM 行主键；尚未开过 LLM 时为 null
     */
    public Long lastLlmSpanId() {
        return lastLlmSpanId.get();
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
        Long id = recorder.openSpan(this, new SpanDraft(type, name, owner, parentId, inputText, parallelFanout));
        // 仅成功落库的 LLM 计入，供 Router「先链后扇」挂子 AGENT
        if (id != null && type == SpanType.LLM) {
            lastLlmSpanId.set(id);
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
