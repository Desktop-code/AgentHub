package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.obs.domain.ObsSpanStack;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 给一轮模型调用记账。所属域：runtime 的 infra。
 * 一次 {@link #stream} 记一条流水，并在有 trace 时记一条 LLM 步骤。
 * 入参只写本轮增量；出参过滤 {@code __fragment__}；attrs 可带人话 brief。
 * 记账和埋点失败只打类型，不中断试跑；obs 不写金额。
 */
public final class MeteringModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(MeteringModel.class);

    private final Model delegate;
    private final BillingMeter meter;
    private final MeterScope scope;
    /** 打开模型时从当前线程取走。流回调换线程后不再读 ThreadLocal */
    private final ObsTraceScope trace;
    /** 创建时捕获的子链 stack；并行换线程后仍读同一 parent */
    private final ObsSpanStack stack;

    /**
     * @param delegate 真实模型客户端
     * @param meter    流水写入
     * @param scope    本轮的 Agent、模型和会话
     */
    public MeteringModel(Model delegate, BillingMeter meter, MeterScope scope) {
        this.delegate = delegate;
        this.meter = meter;
        this.scope = scope;
        this.trace = ObsTraceHolder.current();
        this.stack = this.trace == null ? null : this.trace.stack();
    }

    /**
     * 订阅结束时记一条。失败且没有 usage 时 token 为 0，并带失败说明。
     *
     * @param messages 本轮消息
     * @param tools    工具 schema，可空
     * @param options  生成参数，可空
     * @return 原样转发的响应流
     */
    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        AtomicInteger prompt = new AtomicInteger(-1);
        AtomicInteger completion = new AtomicInteger(-1);
        AtomicReference<String> failure = new AtomicReference<>();
        // 失败原异常单独留一份：remark 已被脱敏成短句，写 attrs.cause 与打全栈都要原始 Throwable
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<StringBuilder> outputBuf = new AtomicReference<>(new StringBuilder());
        AtomicReference<Map<String, String>> toolCalls = new AtomicReference<>(LlmSpanTexts.newToolCallMap());
        AtomicBoolean recorded = new AtomicBoolean(false);
        String inputText = LlmSpanTexts.buildInput(messages);
        // 用户侧体量只有在这里才看得到真实正文（窗口摘要 + 近轮 + 本轮原文），装配层拿不到。
        // 每轮重复 meter 是幂等的：算的是「本轮送进模型的用户文本」，不是累计量。
        meterUserSide(messages);
        Long spanId = openLlm();
        BigDecimal temperature = options == null || options.getTemperature() == null
                ? null : BigDecimal.valueOf(options.getTemperature());
        Integer maxTokens = options == null ? null
                : (options.getMaxTokens() != null ? options.getMaxTokens() : options.getMaxCompletionTokens());
        try {
            return delegate.stream(messages, tools, options)
                    .doOnNext(response -> {
                        capture(response, prompt, completion);
                        if (response != null) {
                            LlmSpanTexts.captureOutput(response.getContent(), outputBuf.get(), toolCalls.get());
                        }
                        if (response != null && response.getFinishReason() != null && !response.getFinishReason().isBlank()) {
                            finishReason.set(response.getFinishReason());
                        }
                    })
                    .doOnError(cause -> {
                        failure.set(MeterRemarks.from(cause));
                        error.set(cause);
                        // 模型出站失败是本轮最关键的信号：必须带 Throwable，否则控制台只剩一句脱敏文案
                        log.warn("模型出站失败 model={} agentId={} type={} cause={}",
                                delegate.getModelName(), scope.agentId(),
                                cause.getClass().getSimpleName(), CauseDigest.of(cause), cause);
                    })
                    .doFinally(signal -> finish(signal, prompt, completion, failure, error, recorded, spanId,
                            inputText, outputBuf.get(), toolCalls.get(), finishReason.get(), temperature, maxTokens));
        } catch (RuntimeException ex) {
            // 同步抛出的构造期异常（映射失败、参数非法等）：同样要留栈
            log.warn("模型出站失败（订阅前） model={} agentId={} type={} cause={}",
                    delegate.getModelName(), scope.agentId(), ex.getClass().getSimpleName(), CauseDigest.of(ex), ex);
            closeLlm(spanId, ObsStatus.ERROR, inputText, null, MeterRemarks.from(ex),
                    0, 0, null, temperature, maxTokens, ex);
            throw ex;
        }
    }

    /** {@inheritDoc} */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    /** {@inheritDoc} */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    /** {@inheritDoc} */
    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }

    private void capture(ChatResponse response, AtomicInteger prompt, AtomicInteger completion) {
        if (response == null || response.getUsage() == null) {
            return;
        }
        prompt.set(response.getUsage().getInputTokens());
        completion.set(response.getUsage().getOutputTokens());
    }

    /**
     * 记 user 侧体量到本轮注入统计。
     * <p>
     * 只取<b>最后一条</b> USER 消息：那才是本轮拼好的「摘要 + 近轮 + 短期 + 当前用户」。
     * 把历史 USER 消息也累加进来会让「本轮用户侧有多长」失真，还会把分段占比算错
     * （历史轮没有【会话摘要】标记，全被算成「本轮原文」）。
     * <p>
     * 为什么忽略异常：这是旁路计量，统计不到不该影响模型调用本身。
     *
     * @param messages 本轮消息，可空
     */
    private void meterUserSide(List<Msg> messages) {
        PromptInjectionStats injection = PromptInjectionHolder.get();
        if (injection == null || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            String latest = null;
            for (Msg message : messages) {
                if (message == null || message.getRole() != MsgRole.USER) {
                    continue;
                }
                String body = message.getTextContent();
                if (body != null && !body.isEmpty()) {
                    latest = body;
                }
            }
            if (latest != null) {
                injection.meterUser(latest);
            }
        } catch (RuntimeException ex) {
            log.debug("user 侧体量统计失败 type={}", ex.getClass().getSimpleName());
        }
    }

    private void finish(SignalType signal, AtomicInteger prompt, AtomicInteger completion,
                        AtomicReference<String> failure, AtomicReference<Throwable> error, AtomicBoolean recorded,
                        Long spanId, String inputText, StringBuilder outputBuf, Map<String, String> toolCalls,
                        String finishReason, BigDecimal temperature, Integer maxTokens) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        int promptTokens = prompt.get() < 0 ? 0 : Math.max(prompt.get(), 0);
        int completionTokens = completion.get() < 0 ? 0 : Math.max(completion.get(), 0);
        String remark = failure.get();
        ObsStatus status = ObsStatus.OK;
        if (signal == SignalType.CANCEL) {
            status = ObsStatus.CANCELLED;
            if (remark == null) {
                remark = "模型调用已取消";
            }
        } else if (remark != null) {
            status = ObsStatus.ERROR;
        }
        safeRecord(promptTokens, completionTokens, remark);
        String output = LlmSpanTexts.mergeOutput(outputBuf, toolCalls);
        closeLlm(spanId, status, inputText, output, remark, promptTokens, completionTokens,
                finishReason, temperature, maxTokens, error.get());
    }

    /**
     * 父步骤在打开模型之前已经 bind。读捕获的 stack，不读换线程后的 ThreadLocal。
     * 没有 trace 时不记步骤，计费照旧。
     */
    private Long openLlm() {
        if (trace == null) {
            return null;
        }
        Long parentId = stack == null ? trace.implicitParent() : stack.parentId();
        return trace.open(SpanType.LLM, delegate.getModelName(), scope.agentId(), parentId, null);
    }

    private void closeLlm(Long spanId, ObsStatus status, String inputText, String outputText, String errorSummary,
                          int promptTokens, int completionTokens, String finishReason,
                          BigDecimal temperature, Integer maxTokens, Throwable error) {
        if (trace == null || spanId == null) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(status);
        close.setInputText(inputText);
        close.setOutputText(outputText);
        if (status != ObsStatus.OK) {
            close.setErrorSummary(errorSummary);
        }
        close.setModelInstanceId(scope.modelInstanceId());
        close.setModelName(delegate.getModelName());
        close.setTokenPrompt(promptTokens);
        close.setTokenCompletion(completionTokens);
        close.setTokenTotal(cap(promptTokens, completionTokens));
        close.setFinishReason(finishReason != null ? finishReason : statusFinish(status));
        close.setTemperature(temperature);
        close.setMaxTokens(maxTokens);
        // 有把握才写人话摘要，不强行编造
        String attrs = status == ObsStatus.OK ? ObsAttrs.withBrief(null, LlmSpanTexts.briefOf(outputText)) : null;
        // 失败时补一份脱敏 cause：LLM 行往往是唯一能看到上游真实异常的地方
        attrs = ObsAttrs.withCause(attrs, status == ObsStatus.OK ? null : CauseDigest.of(error));
        PromptInjectionStats injection = PromptInjectionHolder.get();
        if (injection != null) {
            // 分段统计（字符 + 估算 token + 人话占比）一并写入：面板要回答「钱花在哪一段」
            attrs = PromptInjectionAttrs.merge(attrs, injection);
        }
        close.setAttrsJson(attrs);
        trace.close(spanId, close);
    }

    private String statusFinish(ObsStatus status) {
        if (status == ObsStatus.CANCELLED) {
            return "cancelled";
        }
        if (status == ObsStatus.ERROR) {
            return "error";
        }
        return "stop";
    }

    private int cap(int prompt, int completion) {
        long sum = (long) prompt + completion;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private void safeRecord(int prompt, int completion, String remark) {
        try {
            meter.record(scope.command(prompt, completion, remark));
        } catch (RuntimeException ex) {
            log.warn("计量写入失败 type={}", ex.getClass().getSimpleName());
        }
    }
}
