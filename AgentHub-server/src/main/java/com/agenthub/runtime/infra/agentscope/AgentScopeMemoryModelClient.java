package com.agenthub.runtime.infra.agentscope;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.runtime.domain.model.MeterScope;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用已配置的模型实例做摘要和偏好抽取。所属域：runtime 的 infra。
 * 每次调用都套 BillingMeter 和 LLM span，渠道仍是 CONSOLE。
 *
 * <p>空回判定（排障重点）：只认 {@link TextBlock} 的正文。推理模型的思考是
 * {@link ThinkingBlock}，<b>不能</b>当答案用（思考里出现 store 也不代表用户要求记住）；
 * 若一帧正文都没有而思考有内容，说明输出上限被思考吃满，属于「调用了但拿不到正文」。
 *
 * <p>推理兜底（重要）：L1 复用用户已绑的 MEMORY，不新增模型槽。当调用方给了明确的输出上限
 * （目前只有 L1 分类会传）而结果只拿到思考时，本类<b>自动重试一次</b>：把上限放大到能容纳
 * 「思考 + 一个词」，并顺带关闭 think（DashScope 原生链路认 {@code enable_thinking}，
 * 其它链路会忽略该键，不会因此报错）。重试仍无正文才抛业务异常，由 L1 按 normal 处理 ——
 * 绝不因为兜底失败就把用户的话误写成长期记忆。
 *
 * <p>为什么只对「调用方给了上限」的调用重试：会话结束批抽、滚动摘要、会话标题的既有行为
 * 必须保持不变（一次出站、不额外计费），本类不替它们做二次调用。
 */
@Component
public class AgentScopeMemoryModelClient implements MemoryModelClient {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeMemoryModelClient.class);

    /** 单次出站最长等待；超时按调用失败处理，调用方自行降级 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    /** 推理兜底重试的放大倍数：思考长度通常远超「一个词」，直接给足余量 */
    private static final int ESCALATE_FACTOR = 8;

    /** 兜底重试的保底额度：即使调用方给得很小，也要能容下一段思考 */
    private static final int ESCALATE_FLOOR = 2048;

    /**
     * 兜底重试的上限。取 4096 而不是更大：多数厂商的输出上限（含思考）在 4k～8k，
     * 请求超过模型上限会被直接 400，反而连兜底都失败。
     */
    private static final int ESCALATE_CAP = 4096;

    private final MeteredModelOpener models;
    private final ModelCallDescriber describer;

    /**
     * @param models     打开实例并记账
     * @param describer  出站目标描述（只供日志，已脱敏）
     */
    public AgentScopeMemoryModelClient(MeteredModelOpener models, ModelCallDescriber describer) {
        this.models = models;
        this.describer = describer;
    }

    /**
     * {@inheritDoc}
     * 失败抛业务异常，调用方不得改用主聊天模型顶上。
     */
    @Override
    public String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                           String instruction, String source) {
        // 会话结束 / 摘要批抽与标题生成的既有行为保持不变：任务说明与正文合一，不额外改 sampling
        Model model = models.open(MeterScope.console(agentId, modelInstanceId,
                sessionId == null ? 0L : sessionId, userId));
        String prompt = instruction + "\n\n" + (source == null ? "" : source);
        return collect(model, modelInstanceId, List.of(new UserMessage(prompt)), null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 系统段始终放在最前面；MEMORY L3 场景说明只追加在系统段之后，不能替换格式。
     * 显式通道传 null 采样参数时走模型实例默认值；L1 分类传 temperature=0 + 输出上限，
     * 从而获得本类的推理兜底重试。
     */
    @Override
    public String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                           String systemInstruction, String scenarioExtra, String source,
                           Double temperature, Integer maxTokens) {
        Model model = models.open(MeterScope.console(agentId, modelInstanceId,
                sessionId == null ? 0L : sessionId, userId));
        List<Msg> messages = new ArrayList<>(2);
        // 格式红线：代码常量永远在最前；L3 追加区只能跟在它后面，用户不能整段替换
        String system = joinSystem(systemInstruction, scenarioExtra);
        if (!system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        messages.add(new UserMessage(source == null ? "" : source));
        GenerateOptions options = null;
        if (temperature != null || maxTokens != null) {
            GenerateOptions.Builder builder = GenerateOptions.builder();
            if (temperature != null) {
                builder.temperature(temperature);
            }
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            options = builder.build();
        }
        return collect(model, modelInstanceId, messages, options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Outbound describe(Long modelInstanceId) {
        return describer.describe(modelInstanceId);
    }

    /**
     * 订阅模型、收集正文；只有推理内容时按需兜底重试一次。
     *
     * @param model           已套计量的模型
     * @param modelInstanceId 实例 id，仅用于日志/报错定位
     * @param messages        系统段 + 用户正文
     * @param options         采样参数，可空
     * @return 模型正文
     * @throws BizException 出站失败或无可用正文
     */
    private String collect(Model model, long modelInstanceId, List<Msg> messages, GenerateOptions options) {
        Attempt first = attempt(model, modelInstanceId, messages, options);
        if (first.hasText()) {
            return first.text();
        }
        // 仅当调用方显式给了输出上限（L1 分类）才做推理兜底：
        // 批抽 / 摘要 / 标题保持「一次出站」，不因本类新增二次调用
        if (options != null && options.getMaxTokens() != null && first.thinkingChars() > 0) {
            String recovered = retryWithoutThinking(model, modelInstanceId, messages, options, first);
            if (recovered != null) {
                return recovered;
            }
        }
        throw failure(modelInstanceId, first);
    }

    /**
     * 推理兜底重试：放大输出上限并关闭 think。
     *
     * <p>为什么同时做两件事：只放大上限，长思考仍可能吃满；只关 think，部分厂商对该开关不生效。
     * 两者叠加后，只要链路支持其中任一项就能吐出正文。
     *
     * @param model           已套计量的模型
     * @param modelInstanceId 实例 id，日志用
     * @param messages        原样消息（不改提示词，避免影响判定口径）
     * @param options         首次采样参数（含 temperature 与原始上限）
     * @param first           首次结果，用于日志对账
     * @return 重试得到的正文；仍为空时返回 null
     */
    private String retryWithoutThinking(Model model, long modelInstanceId, List<Msg> messages,
                                        GenerateOptions options, Attempt first) {
        int escalated = escalate(options.getMaxTokens());
        GenerateOptions.Builder builder = GenerateOptions.builder().maxTokens(escalated);
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        // DashScope 原生链路会把该键并进 parameters；OpenAI 兼容 / Anthropic 链路忽略未知键，
        // 因此这里不做协议判断也能安全出站
        builder.additionalBodyParam("enable_thinking", Boolean.FALSE);
        log.info("记忆出站推理兜底重试 instanceId={} firstMaxTokens={} retryMaxTokens={} thinkingChars={} finishReason={}",
                modelInstanceId, options.getMaxTokens(), escalated, first.thinkingChars(), first.finishReason());
        Attempt second;
        try {
            second = attempt(model, modelInstanceId, messages, builder.build());
        } catch (BizException ex) {
            // 重试本身失败（如厂商不接受该扩展键）：保留首次的诊断信息，避免真实原因被覆盖
            log.warn("记忆出站推理兜底重试失败 instanceId={} retryMaxTokens={} type={} msg={}",
                    modelInstanceId, escalated, ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
        if (second.hasText()) {
            log.info("记忆出站推理兜底成功 instanceId={} retryMaxTokens={} textChars={} finishReason={}",
                    modelInstanceId, escalated, second.text().length(), second.finishReason());
            return second.text();
        }
        return null;
    }

    /**
     * 单次订阅并汇总内容块。
     *
     * @return 本次结果，永不返回 null
     * @throws BizException 出站异常（文案固定，不含密钥）
     */
    private Attempt attempt(Model model, long modelInstanceId, List<Msg> messages, GenerateOptions options) {
        List<ChatResponse> frames;
        try {
            frames = model.stream(messages, null, options)
                    .collectList()
                    .block(CALL_TIMEOUT);
        } catch (RuntimeException ex) {
            log.warn("记忆出站失败 instanceId={} type={}", modelInstanceId, ex.getClass().getSimpleName());
            throw new BizException(ErrorCode.BAD_REQUEST, "摘要或抽取模型调用失败");
        }
        StringBuilder text = new StringBuilder();
        int thinkingChars = 0;
        int blocks = 0;
        String finishReason = null;
        if (frames != null) {
            for (ChatResponse frame : frames) {
                if (frame == null) {
                    continue;
                }
                if (frame.getFinishReason() != null && !frame.getFinishReason().isBlank()) {
                    finishReason = frame.getFinishReason();
                }
                if (frame.getContent() == null) {
                    continue;
                }
                for (ContentBlock block : frame.getContent()) {
                    blocks++;
                    if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                        text.append(textBlock.getText());
                    } else if (block instanceof ThinkingBlock thinking && thinking.getThinking() != null) {
                        // 思考单独计数，绝不并入正文：L1 拿思考当判定会把「store 出现在推理里」误当意图
                        thinkingChars += thinking.getThinking().length();
                    }
                }
            }
        }
        return new Attempt(text.toString(), blocks, thinkingChars, finishReason);
    }

    /**
     * 把「无正文」转成可区分的业务异常，文案会进入 L1 的 WARN 与批抽的 FAILED reason。
     *
     * @param modelInstanceId 实例 id
     * @param attempt         最后一次尝试的结果
     * @return 待抛出的业务异常
     */
    private BizException failure(long modelInstanceId, Attempt attempt) {
        if (attempt.thinkingChars() > 0) {
            log.warn("记忆出站仅返回推理内容 instanceId={} finishReason={} thinkingChars={}（已尝试放大上限与关闭 think）",
                    modelInstanceId, attempt.finishReason(), attempt.thinkingChars());
            return new BizException(ErrorCode.BAD_REQUEST,
                    "模型只返回推理内容（finishReason=" + dash(attempt.finishReason())
                            + "，thinkingChars=" + attempt.thinkingChars()
                            + "），已自动放大输出上限并关闭 think 仍未拿到正文");
        }
        if (attempt.blocks() == 0) {
            log.warn("记忆出站无任何内容块 instanceId={} finishReason={}",
                    modelInstanceId, attempt.finishReason());
            return new BizException(ErrorCode.BAD_REQUEST,
                    "模型没有返回任何内容块（finishReason=" + dash(attempt.finishReason()) + "）");
        }
        log.warn("记忆出站正文为空 instanceId={} finishReason={} blocks={}",
                modelInstanceId, attempt.finishReason(), attempt.blocks());
        return new BizException(ErrorCode.BAD_REQUEST,
                "模型返回的正文为空（finishReason=" + dash(attempt.finishReason())
                        + "，blocks=" + attempt.blocks() + "）");
    }

    /**
     * 放大输出上限：至少 8 倍、不低于 2048，封顶 {@link #ESCALATE_CAP}。
     *
     * @param current 首次使用的上限
     * @return 重试上限
     */
    static int escalate(int current) {
        int wanted = Math.max(current * ESCALATE_FACTOR, ESCALATE_FLOOR);
        return Math.min(wanted, ESCALATE_CAP);
    }

    /**
     * 代码常量系统段 + 可空场景说明。场景说明只追加，永远不会替换系统段。
     */
    private static String joinSystem(String systemInstruction, String scenarioExtra) {
        String system = systemInstruction == null ? "" : systemInstruction.trim();
        String extra = scenarioExtra == null ? "" : scenarioExtra.trim();
        if (extra.isEmpty()) {
            return system;
        }
        return system.isEmpty() ? extra : system + "\n\n" + extra;
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /**
     * 一次出站的结果摘要。所属域：runtime 的 infra。
     *
     * @param text          正文拼接（可能为空白）
     * @param blocks        内容块总数，用于区分「上游什么都没给」与「给了但正文空」
     * @param thinkingChars 思考字符数，用于识别「上限被思考吃满」
     * @param finishReason  结束原因，可空
     */
    private record Attempt(String text, int blocks, int thinkingChars, String finishReason) {

        /**
         * @return 是否有可用正文（纯空白视为没有）
         */
        boolean hasText() {
            return text != null && !text.isBlank();
        }
    }
}
