package com.agenthub.obs.application;

import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsChannel;
import com.agenthub.obs.domain.model.ObsSpan;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.ObsTrace;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.model.TraceFinishSummary;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.obs.domain.repository.ObsSpanRepository;
import com.agenthub.obs.domain.repository.ObsTraceRepository;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.obs.domain.service.ObsTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把试跑步骤写入 obs_trace / obs_span。所属域：obs。
 * 任何失败只记异常类型，不抛回试跑，也不写计费。
 * 对话场景写 CONSOLE，工作流场景写 WORKFLOW（根 trace 带 workflow_run_id，节点步为 TASK）。
 * 树键为自增 id + parent_id；step_no 写入时发号；seq 由 ObsTraceScope 串行分配。
 * 开/关步用墙钟毫秒算 latency，避免 datetime 秒精度把父子/先后步挤成同一秒。
 */
@Service
public class TraceRecorderAppService implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorderAppService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObsTraceRepository traces;
    private final ObsSpanRepository spans;
    /** 行主键 → 真实开工 epoch ms；close 时算 latency，不与父步共用起点 */
    private final ConcurrentHashMap<Long, Long> spanOpenedTimeMs = new ConcurrentHashMap<>();

    /**
     * @param traces obs_trace
     * @param spans  obs_span
     */
    public TraceRecorderAppService(ObsTraceRepository traces, ObsSpanRepository spans) {
        this.traces = traces;
        this.spans = spans;
    }

    /**
     * {@inheritDoc}
     * 对话场景没有 sessionId 或 userId 时不建 trace。channel 固定为控制台。
     */
    @Override
    public Optional<ObsTraceScope> beginConsole(TraceStart start) {
        try {
            if (start == null || start.sessionId() == null || start.userId() == null) {
                return Optional.empty();
            }
            ObsTrace trace = newTrace(start);
            trace.setChannel(ObsChannel.CONSOLE);
            traces.insert(trace, start.userId());
            return Optional.of(new ObsTraceScope(trace.getTraceId(), this));
        } catch (RuntimeException ex) {
            log.warn("trace 创建失败 type={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     * <p>工作流 run 没有对话会话：sessionId 由调用方传 {@code ObsPlaceholders.SESSION_NONE}。
     * channel 固定 WORKFLOW，编排扩展信息先打码再落库。
     */
    @Override
    public Optional<ObsTraceScope> beginWorkflow(TraceStart start, String workflowRunId, String attrsJson) {
        try {
            if (start == null || start.sessionId() == null || start.userId() == null) {
                return Optional.empty();
            }
            ObsTrace trace = newTrace(start);
            trace.setChannel(ObsChannel.WORKFLOW);
            trace.setWorkflowRunId(blankToNull(workflowRunId));
            // 编排信息（工作流名 / 版本 / runId）只作展示与排障，同样禁止明文 Key 入库
            trace.setAttrsJson(ObsTexts.mask(attrsJson));
            traces.insert(trace, start.userId());
            return Optional.of(new ObsTraceScope(trace.getTraceId(), this));
        } catch (RuntimeException ex) {
            log.warn("工作流 trace 创建失败 type={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     * <p>续号口径：seq 取库里最大 seq，step_no 取最大的纯整数串行号；
     * 并行点号支（3.1）不复用作基号，避免恢复后串行步与历史分支重号。
     */
    @Override
    public Optional<ObsTraceScope> resumeWorkflow(String traceId) {
        try {
            if (traceId == null || traceId.isBlank()) {
                return Optional.empty();
            }
            ObsTrace trace = traces.findByTraceId(traceId).orElse(null);
            // 已终态的 trace 不再续写：收尾后的行不能被重新当作「运行中」
            if (trace == null || trace.getStatus() != ObsStatus.RUNNING) {
                return Optional.empty();
            }
            int maxSeq = 0;
            int maxSerial = 0;
            for (ObsSpan span : spans.listByTraceId(traceId)) {
                if (span.getSeq() != null && span.getSeq() > maxSeq) {
                    maxSeq = span.getSeq();
                }
                maxSerial = Math.max(maxSerial, serialOf(span.getStepNo()));
            }
            return Optional.of(new ObsTraceScope(traceId, this, maxSeq, maxSerial));
        } catch (RuntimeException ex) {
            log.warn("工作流 trace 续号失败 type={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 新建 trace 的公共字段：身份、标题打码、RUNNING、开始时间。
     *
     * @param start 会话 / 用户 / 标题
     * @return 未落库的 trace（channel 与编排列由调用方补）
     */
    private ObsTrace newTrace(TraceStart start) {
        ObsTrace trace = new ObsTrace();
        trace.setTraceId(newId());
        trace.setSessionId(start.sessionId());
        trace.setAgentId(start.agentId());
        trace.setUserId(start.userId());
        trace.setTitle(ObsTexts.mask(start.title()));
        trace.setStatus(ObsStatus.RUNNING);
        trace.setStartTime(LocalDateTime.now());
        return trace;
    }

    /**
     * 只认纯整数 step_no；点号支与非法值都返回 0。
     *
     * @param stepNo 展示号
     * @return 串行基号
     */
    private int serialOf(String stepNo) {
        if (stepNo == null || stepNo.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(stepNo.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** {@inheritDoc} */
    @Override
    public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
        try {
            if (scope == null || draft == null || draft.type() == null) {
                return null;
            }
            Long parentId = draft.parentId();
            // 除根外 AGENT/LLM/TOOL 禁止 parent 空：无显式父时回落到当前 stack
            if (parentId == null && needsParent(draft.type())) {
                parentId = scope.implicitParent();
            }
            if (parentId == null && needsParent(draft.type())) {
                log.warn("span parent 缺失 type={} name={}", draft.type(), draft.name());
            }
            String parentStepNo = parentId == null ? null : scope.stepNoOf(parentId);
            if (parentStepNo == null && parentId != null) {
                parentStepNo = scope.stack().parentStepNo();
            }
            String stepNo = scope.allocateStepNo(parentId, parentStepNo, draft.parallelFanout());
            // 每步独立取当前墙钟，禁止复用父 ROUTE/LLM 的开工时刻
            long openedTime = System.currentTimeMillis();
            ObsSpan span = new ObsSpan();
            span.setTraceId(scope.traceId());
            span.setParentId(parentId);
            span.setStepNo(stepNo);
            span.setSeq(scope.allocateSeq());
            span.setSpanType(draft.type());
            span.setName(ObsTexts.fitName(draft.name(), "step"));
            span.setAgentId(draft.agentId());
            span.setStatus(ObsStatus.RUNNING);
            span.setStartTime(toLocal(openedTime));
            span.setInputText(ObsTexts.mask(draft.inputText()));
            spans.insert(span);
            // MP 回填 id 后登记 step_no，供子步 bind / 发号
            scope.registerStepNo(span.getId(), stepNo);
            spanOpenedTimeMs.put(span.getId(), openedTime);
            // 手验父链：一条即可，禁刷屏（debug）
            log.debug("span parent={} id={} type={} stepNo={}", parentId, span.getId(), draft.type(), stepNo);
            return span.getId();
        } catch (RuntimeException ex) {
            log.warn("span 写入失败 type={}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
        try {
            if (scope == null || spanId == null || close == null || !terminal(close.getStatus())) {
                return;
            }
            SpanClose masked = maskClose(close);
            long endTime = System.currentTimeMillis();
            Long openedTime = spanOpenedTimeMs.remove(spanId);
            Integer latencyMs = null;
            if (openedTime != null) {
                long delta = endTime - openedTime;
                latencyMs = delta < 0 ? 0 : (delta > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) delta);
            }
            // end_time 尽量带上毫秒；库列若无小数秒会被截断，latency_ms 仍保留墙钟
            spans.closeIfRunning(scope.traceId(), spanId, masked, toLocal(endTime), latencyMs);
        } catch (RuntimeException ex) {
            log.warn("span 结束失败 type={}", ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void amendAttrs(ObsTraceScope scope, Long spanId, java.util.Map<String, Object> fields) {
        try {
            if (scope == null || spanId == null || fields == null || fields.isEmpty()) {
                return;
            }
            // 先读回既有 attrs 再合并：补写只加字段，不能把 brief / cause / 模板等已有信息冲掉
            Optional<ObsSpan> existing = spans.find(scope.traceId(), spanId);
            if (existing.isEmpty()) {
                return;
            }
            String merged = ObsAttrs.withFields(existing.get().getAttrsJson(), fields);
            if (merged == null || merged.isBlank()) {
                return;
            }
            spans.updateAttrs(scope.traceId(), spanId, ObsTexts.mask(merged));
        } catch (RuntimeException ex) {
            // 埋点永远不能让主流程失败
            log.warn("span attrs 补写失败 type={}", ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
        finish(scope, status, errorSummary, null);
    }

    /** {@inheritDoc} */
    @Override
    public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary, String finishReason) {
        finish(scope, status, errorSummary, finishReason, null);
    }

    /** {@inheritDoc} */
    @Override
    public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary, String finishReason,
                       String attrsJson) {
        try {
            if (scope == null || !terminal(status)) {
                return;
            }
            LocalDateTime ended = LocalDateTime.now();
            spans.closeRunning(scope.traceId(), status, ended);
            // 只摘掉本 trace 仍挂着的开工时钟；禁止 clear 全局 map（多 trace 并发）
            for (ObsSpan row : spans.listByTraceId(scope.traceId())) {
                if (row.getId() != null) {
                    spanOpenedTimeMs.remove(row.getId());
                }
            }
            TraceFinishSummary summary = summarize(scope.traceId(), status, finishReason);
            // trace 级 cause 与 span 级同口径：先打码再写，绝不把 Key / base64 带进库
            String maskedAttrs = ObsTexts.mask(attrsJson);
            if (maskedAttrs != null) {
                summary.setAttrsJson(maskedAttrs);
            }
            traces.finishIfRunning(scope.traceId(), status, ObsTexts.mask(errorSummary), ended, null, summary);
        } catch (RuntimeException ex) {
            log.warn("trace 结束失败 type={}", ex.getClass().getSimpleName());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void cancelSession(long sessionId, String reason) {
        try {
            LocalDateTime ended = LocalDateTime.now();
            String summaryText = ObsTexts.mask(reason);
            for (String traceId : traces.listRunningIds(sessionId)) {
                spans.closeRunning(traceId, ObsStatus.CANCELLED, ended);
                for (ObsSpan row : spans.listByTraceId(traceId)) {
                    if (row.getId() != null) {
                        spanOpenedTimeMs.remove(row.getId());
                    }
                }
                TraceFinishSummary summary = summarize(traceId, ObsStatus.CANCELLED, null);
                traces.finishIfRunning(traceId, ObsStatus.CANCELLED, summaryText, ended, null, summary);
            }
        } catch (RuntimeException ex) {
            log.warn("trace 取消失败 type={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * ROUTE 可为根；AGENT 在纯 ReAct 时也可为根；其余类型必须有父。
     */
    /**
     * ROUTE / AGENT / VISION 可为根；其余类型必须有父。
     * VISION（图片文字提取）发生在任何 Agent 步骤之前，天然挂在 trace 根下，
     * 因此不强制父级，否则每轮带图试跑都会刷一条「span parent 缺失」告警。
     */
    private boolean needsParent(SpanType type) {
        return type == SpanType.LLM || type == SpanType.TOOL || type == SpanType.RETRIEVE;
    }

    /**
     * 按已落库 span 汇总次数与 token；主模型取第一条带模型信息的 LLM。
     * finishReason 显式传入时优先（撞限写 timeout / max_rounds 等）。
     */
    private TraceFinishSummary summarize(String traceId, ObsStatus status, String finishReason) {
        List<ObsSpan> rows = spans.listByTraceId(traceId);
        int llm = 0;
        int tool = 0;
        int retrieve = 0;
        long prompt = 0;
        long completion = 0;
        Long primaryInstance = null;
        String primaryName = null;
        for (ObsSpan span : rows) {
            if (span.getSpanType() == SpanType.LLM) {
                llm++;
                prompt += nonNeg(span.getTokenPrompt());
                completion += nonNeg(span.getTokenCompletion());
                if (primaryInstance == null && span.getModelInstanceId() != null) {
                    primaryInstance = span.getModelInstanceId();
                }
                if (primaryName == null && span.getModelName() != null && !span.getModelName().isBlank()) {
                    primaryName = span.getModelName();
                }
            } else if (span.getSpanType() == SpanType.VISION) {
                // 视觉预处理也是真实出站：token 计入本提问合计，但不进 llm 步数，
                // 否则「轮数」会把开场那次 OCR 也算成一轮模型对话
                prompt += nonNeg(span.getTokenPrompt());
                completion += nonNeg(span.getTokenCompletion());
            } else if (span.getSpanType() == SpanType.TOOL) {
                tool++;
            } else if (span.getSpanType() == SpanType.RETRIEVE) {
                retrieve++;
            }
        }
        TraceFinishSummary summary = new TraceFinishSummary();
        summary.setLlmCallCount(llm);
        summary.setToolCallCount(tool);
        summary.setRetrieveCallCount(retrieve);
        summary.setRoundCount(llm > 0 ? llm : null);
        summary.setTokenPrompt(cap(prompt));
        summary.setTokenCompletion(cap(completion));
        summary.setTokenTotal(cap(prompt + completion));
        summary.setPrimaryModelInstanceId(primaryInstance);
        summary.setPrimaryModelName(primaryName);
        summary.setFinishReason(finishReason != null && !finishReason.isBlank()
                ? finishReason.trim() : finishReasonOf(status));
        return summary;
    }

    private SpanClose maskClose(SpanClose source) {
        SpanClose close = new SpanClose();
        close.setStatus(source.getStatus());
        close.setInputText(ObsTexts.mask(source.getInputText()));
        close.setOutputText(ObsTexts.mask(source.getOutputText()));
        close.setErrorSummary(ObsTexts.mask(source.getErrorSummary()));
        close.setModelInstanceId(source.getModelInstanceId());
        if (source.getModelName() != null && !source.getModelName().isBlank()) {
            close.setModelName(ObsTexts.fitName(source.getModelName(), "model"));
        }
        close.setProvider(blankToNull(source.getProvider()));
        close.setTokenPrompt(source.getTokenPrompt());
        close.setTokenCompletion(source.getTokenCompletion());
        close.setTokenTotal(source.getTokenTotal());
        close.setFinishReason(blankToNull(source.getFinishReason()));
        close.setBillingLedgerId(source.getBillingLedgerId());
        close.setTemperature(source.getTemperature());
        close.setMaxTokens(source.getMaxTokens());
        close.setToolId(source.getToolId());
        if (source.getToolName() != null && !source.getToolName().isBlank()) {
            close.setToolName(ObsTexts.fitName(source.getToolName(), "tool"));
        }
        close.setToolType(blankToNull(source.getToolType()));
        close.setToolCallId(blankToNull(source.getToolCallId()));
        close.setRetryCount(source.getRetryCount());
        close.setToolSuccess(source.getToolSuccess());
        close.setRouteDecision(ObsTexts.mask(source.getRouteDecision()));
        close.setChildAgentId(source.getChildAgentId());
        close.setRetrieveQuery(ObsTexts.mask(source.getRetrieveQuery()));
        close.setRetrieveHitCount(source.getRetrieveHitCount());
        close.setRetrieveTopK(source.getRetrieveTopK());
        close.setRetrieveKbId(source.getRetrieveKbId());
        // brief 单独打码后再写回，避免整段 JSON 被正则误伤；
        // cause 同理单独取再合并，否则「有 brief 时整段重建」会把排障用的 cause 一起丢掉
        String brief = ObsAttrs.briefOf(source.getAttrsJson());
        String cause = ObsAttrs.causeOf(source.getAttrsJson());
        if (brief != null || cause != null) {
            String attrs = ObsAttrs.withBrief(null, brief == null ? null : ObsTexts.mask(brief));
            close.setAttrsJson(ObsAttrs.withCause(attrs, cause == null ? null : ObsTexts.mask(cause)));
        } else {
            close.setAttrsJson(ObsTexts.mask(source.getAttrsJson()));
        }
        return close;
    }

    private String finishReasonOf(ObsStatus status) {
        if (status == ObsStatus.OK) {
            return "completed";
        }
        if (status == ObsStatus.CANCELLED) {
            return "cancelled";
        }
        if (status == ObsStatus.ERROR) {
            return "error";
        }
        return null;
    }

    private int nonNeg(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private int cap(long value) {
        if (value < 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private boolean terminal(ObsStatus status) {
        return status == ObsStatus.OK || status == ObsStatus.ERROR || status == ObsStatus.CANCELLED;
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 墙钟毫秒 → 上海时区 LocalDateTime（可含 nanos，供 JSON 带毫秒）。 */
    private LocalDateTime toLocal(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }
}
