package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.VisionOcrTexts;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 图片文字提取（OCR）执行器。所属域：runtime 的 infra。
 *
 * <p>为什么要有这一层：对话主模型（CHAT）多数不支持图片输入，
 * 于是把「看图」这件事收口成一次独立的视觉模型调用——先提文字，再把文字当成普通用户提问交给主模型。
 * 这样主链路（ReAct / Router / 计划执行）完全不需要知道图片的存在。
 *
 * <p>边界与约束：
 * <ol>
 *   <li>只做「图 → 文字」，不产出对话正文、不参与工具调用；</li>
 *   <li>每张图一次独立调用而不是塞进一条多图消息：单张失败只污染那一张，其余仍可用，
 *       而且各视觉模型对「多图 + 中文指令」的稳定性差异很大；</li>
 *   <li>无 trace 时照常跑，只是不写 VISION 步骤；记账与埋点失败都不影响本轮；</li>
 *   <li>所有出站都是阻塞调用，必须切到弹性调度器，禁止占用请求线程的事件循环。</li>
 * </ol>
 *
 * 线程：无状态可复用；每次调用自建请求对象。
 */
@Component
public class VisionOcrExecutor {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrExecutor.class);

    /** 单张图识别的整体超时。视觉模型首字延迟高，但也不该拖满整轮墙钟 */
    private static final Duration OCR_TIMEOUT = Duration.ofSeconds(60);

    /** 识别结果的最大输出 token。OCR 只需要文字，不需要长篇推理 */
    private static final int OCR_MAX_TOKENS = 2048;

    private final AgentScopeModelFactory models;
    private final BillingMeter meter;

    /**
     * @param models 按实例 id 构造模型客户端（内部负责解密凭证）
     * @param meter  视觉调用也要记流水，否则 OCR 的 token 会凭空消失
     */
    public VisionOcrExecutor(AgentScopeModelFactory models, BillingMeter meter) {
        this.models = models;
        this.meter = meter;
    }

    /**
     * 对一批图片逐张做文字提取。
     *
     * <p>失败策略：单张失败不抛出，返回空串占位；全部失败才抛 {@link TrialFailure}，
     * 因为「一张都没提出来」时继续让主模型盲答只会给出更离谱的答案。
     *
     * @param images           图片 URL 或 data URI，调用方已做数量与体积校验
     * @param visionInstanceId 已解析出的 VISION 槽实例 id
     * @param scope            本轮计量范围（Agent / 会话 / 用户）
     * @return 汇总文本，格式 {@code [图1内容]: …\n[图2内容]: …}；无图时为空串
     * @throws TrialFailure 模型不可用、全部图片识别失败或整体超时
     */
    public String extract(List<String> images, Long visionInstanceId, MeterScope scope) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        Model model = models.open(visionInstanceId);
        List<String> perImage = new ArrayList<>(images.size());
        int failed = 0;
        for (String image : images) {
            String text = recognizeOne(model, image, visionInstanceId, scope);
            if (text == null || text.isBlank()) {
                failed++;
                perImage.add("");
            } else {
                perImage.add(text);
            }
        }
        if (failed == images.size()) {
            throw new TrialFailure("图片文字识别失败，请确认图片清晰后重试");
        }
        return VisionOcrTexts.aggregate(perImage);
    }

    /**
     * 识别单张图。带一个 VISION 步骤：面板上能看到 OCR 的耗时与 token。
     *
     * @param model      已打开的视觉模型
     * @param image      图片 URL 或 data URI
     * @param instanceId 实例 id，写进 span 便于区分用的是哪个视觉模型
     * @param scope      计量范围
     * @return 识别出的文字；失败时返回 null（调用方按失败占位处理）
     */
    private String recognizeOne(Model model, String image, Long instanceId, MeterScope scope) {
        ObsTraceScope trace = ObsTraceHolder.current();
        // 入参只写「第几张图 + 图片类型」，绝不写 base64 原文：一段 base64 就能撑爆 span 正文
        String inputSummary = "[VISION]\n任务：" + VisionOcrTexts.OCR_SYSTEM_PROMPT + "\n图片：" + describeImage(image);
        Long spanId = trace == null ? null
                : trace.open(SpanType.VISION, "图片文字识别", scope.agentId(), trace.implicitParent(), inputSummary);
        long startedAt = System.currentTimeMillis();
        try {
            ChatResponse response = callModel(model, image);
            String text = textOf(response);
            ChatUsage usage = response == null ? null : response.getUsage();
            int prompt = usage == null ? 0 : Math.max(0, usage.getInputTokens());
            int completion = usage == null ? 0 : Math.max(0, usage.getOutputTokens());
            safeRecord(scope, prompt, completion, null);
            closeSpan(trace, spanId, ObsStatus.OK, inputSummary, text, null, instanceId,
                    model.getModelName(), prompt, completion, System.currentTimeMillis() - startedAt);
            return text;
        } catch (RuntimeException ex) {
            String remark = MeterRemarks.from(ex);
            log.warn("图片文字识别失败 instanceId={} type={}", instanceId, ex.getClass().getSimpleName());
            safeRecord(scope, 0, 0, remark);
            closeSpan(trace, spanId, ObsStatus.ERROR, inputSummary, null, remark, instanceId,
                    model.getModelName(), 0, 0, System.currentTimeMillis() - startedAt);
            return null;
        }
    }

    /**
     * 真正发起一次视觉调用。用流式接口再合并：多数视觉模型不支持非流式大图请求，
     * 而流式对超时的表现也更可控（有帧就说明还活着）。
     *
     * @param model 视觉模型
     * @param image 图片
     * @return 合并后的响应（正文取累计文本，usage 取最后一帧给出的值）
     * @throws TrialFailure 超时或上游报错
     */
    private ChatResponse callModel(Model model, String image) {
        List<Msg> messages = List.of(visionMessage(image));
        GenerateOptions options = GenerateOptions.builder()
                .maxTokens(OCR_MAX_TOKENS)
                // 提取文字不需要发散，温度锁 0 保证同一张图结果稳定、可复现
                .temperature(0D)
                .build();
        // 视觉请求是阻塞的（blockLast）：必须切到弹性调度器，否则会占住调用线程的事件循环
        return Mono.fromCallable(() -> {
            AtomicReference<StringBuilder> text = new AtomicReference<>(new StringBuilder());
            AtomicReference<ChatUsage> usage = new AtomicReference<>();
            AtomicReference<String> finish = new AtomicReference<>();
            model.stream(messages, List.of(), options)
                    .doOnNext(frame -> collect(frame, text.get(), usage, finish))
                    .blockLast(OCR_TIMEOUT);
            return ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(text.get().toString()).build()))
                    .usage(usage.get())
                    .finishReason(finish.get())
                    .build();
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> error instanceof TrialFailure ? error : new TrialFailure(MeterRemarks.from(error)))
                .block(OCR_TIMEOUT.plusSeconds(5));
    }

    /**
     * 累积一帧：正文追加，usage 与 finish_reason 取最后一次非空值。
     *
     * @param frame  引擎响应帧，可空
     * @param text   文本累积
     * @param usage  用量
     * @param finish 结束原因
     */
    private void collect(ChatResponse frame, StringBuilder text, AtomicReference<ChatUsage> usage,
                         AtomicReference<String> finish) {
        if (frame == null) {
            return;
        }
        if (frame.getContent() != null) {
            for (ContentBlock block : frame.getContent()) {
                if (block instanceof TextBlock piece && piece.getText() != null) {
                    text.append(piece.getText());
                }
            }
        }
        if (frame.getUsage() != null) {
            usage.set(frame.getUsage());
        }
        if (frame.getFinishReason() != null && !frame.getFinishReason().isBlank()) {
            finish.set(frame.getFinishReason());
        }
    }

    /**
     * 构造视觉请求消息：文字提示在前，图片块在后。
     * 图片按「data:…;base64,…」与「http(s)」分流到 Base64Source / URLSource，
     * 因为部分厂商只认其中一种形态。
     *
     * @param image 图片字符串
     * @return 引擎用户消息（图文混排）
     */
    private Msg visionMessage(String image) {
        List<ContentBlock> blocks = new ArrayList<>(2);
        blocks.add(TextBlock.builder().text(VisionOcrTexts.OCR_USER_PROMPT).build());
        blocks.add(imageBlock(image));
        return new UserMessage(blocks);
    }

    /**
     * @param image 图片字符串（data URI 或 http(s) 地址）
     * @return 引擎图片块
     * @throws TrialFailure 形态无法识别
     */
    private ImageBlock imageBlock(String image) {
        String lower = image.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("data:")) {
            int marker = lower.indexOf(";base64,");
            if (marker <= "data:".length()) {
                throw new TrialFailure("图片数据格式不正确");
            }
            String mime = image.substring("data:".length(), marker);
            String data = image.substring(marker + ";base64,".length());
            return ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mime).data(data).build())
                    .build();
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return ImageBlock.builder().source(new URLSource(image)).build();
        }
        throw new TrialFailure("图片地址格式不正确，仅支持上传的图片或 http(s) 地址");
    }

    /**
     * 图片的可读描述，用于 span 入参与日志：绝不输出 base64 原文。
     *
     * @param image 图片字符串
     * @return 如「内联图片 image/png，约 512KB」或地址摘要
     */
    private String describeImage(String image) {
        String lower = image.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("data:")) {
            int marker = lower.indexOf(";base64,");
            String mime = marker > "data:".length() ? image.substring("data:".length(), marker) : "image/*";
            long bytes = Math.max(0, (long) (image.length() - marker) * 3 / 4);
            return "内联图片 " + mime + "，约 " + (bytes / 1024) + "KB";
        }
        // URL 里可能带签名参数，只保留主机与路径首段
        int queryAt = image.indexOf('?');
        return "图片地址 " + (queryAt > 0 ? image.substring(0, queryAt) : image);
    }

    /**
     * @param response 合并后的响应
     * @return 纯文本正文；没有正文时返回空串
     */
    private String textOf(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock piece && piece.getText() != null) {
                text.append(piece.getText());
            }
        }
        return text.toString().trim();
    }

    /**
     * 写 VISION 步骤。失败只打日志，不影响本轮结果。
     */
    private void closeSpan(ObsTraceScope trace, Long spanId, ObsStatus status, String inputText, String outputText,
                           String errorSummary, Long instanceId, String modelName,
                           int prompt, int completion, long elapsedMs) {
        if (trace == null || spanId == null) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(status);
        close.setInputText(inputText);
        close.setOutputText(outputText);
        if (status != ObsStatus.OK) {
            close.setErrorSummary(errorSummary == null ? "图片文字识别失败" : errorSummary);
        }
        close.setModelInstanceId(instanceId);
        close.setModelName(modelName);
        close.setTokenPrompt(prompt);
        close.setTokenCompletion(completion);
        close.setTokenTotal(prompt + completion);
        close.setFinishReason(status == ObsStatus.OK ? "stop" : "error");
        close.setAttrsJson(ObsAttrs.withBrief(null,
                status == ObsStatus.OK ? "提取图片文字 · " + elapsedMs + "ms" : "图片文字识别失败"));
        trace.close(spanId, close);
    }

    /**
     * 记一条流水。失败只打日志：计费不该反噬试跑。
     */
    private void safeRecord(MeterScope scope, int prompt, int completion, String remark) {
        try {
            meter.record(scope.command(prompt, completion, remark));
        } catch (RuntimeException ex) {
            log.warn("计量写入失败 type={}", ex.getClass().getSimpleName());
        }
    }
}
