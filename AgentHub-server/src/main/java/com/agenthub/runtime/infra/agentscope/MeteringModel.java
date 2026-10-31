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
import com.agenthub.runtime.domain.service.PromptInjectionHolder;
import io.agentscope.core.message.Msg;
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
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<StringBuilder> outputBuf = new AtomicReference<>(new StringBuilder());
        AtomicReference<Map<String, String>> toolCalls = new AtomicReference<>(LlmSpanTexts.newToolCallMap());
        AtomicBoolean recorded = new AtomicBoolean(false);
        String inputText = LlmSpanTexts.buildInput(messages);
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
                    .doOnError(error -> failure.set(MeterRemarks.from(error)))
                    .doFinally(signal -> finish(signal, prompt, completion, failure, recorded, spanId,
                            inputText, outputBuf.get(), toolCalls.get(), finishReason.get(), temperature, maxTokens));
        } catch (RuntimeException ex) {
            closeLlm(spanId, ObsStatus.ERROR, inputText, null, MeterRemarks.from(ex),
                    0, 0, null, temperature, maxTokens);
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

    private void finish(SignalType signal, AtomicInteger prompt, AtomicInteger completion,
                        AtomicReference<String> failure, AtomicBoolean recorded, Long spanId,
                        String inputText, StringBuilder outputBuf, Map<String, String> toolCalls,
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
                finishReason, temperature, maxTokens);
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
                          BigDecimal temperature, Integer maxTokens) {
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
        PromptInjectionStats injection = PromptInjectionHolder.get();
        if (injection != null) {
            attrs = ObsAttrs.withInjection(attrs, injection.getToolsInjected(), injection.getSkillsInjected(),
                    injection.totalChars(), injection.isTruncated());
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
