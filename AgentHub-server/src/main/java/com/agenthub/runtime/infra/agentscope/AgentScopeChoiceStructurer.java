package com.agenthub.runtime.infra.agentscope;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.service.ChoiceStructurer;
import com.agenthub.runtime.domain.service.CauseDigest;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
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
 * 用已配置的模型实例把终答整理成选项 JSON。所属域：runtime 的 infra。
 *
 * <p>与「记忆摘要」那条出站的差别（为什么另起一个实现）：
 * <ul>
 *   <li>它发生在<b>用户等待收尾</b>的路径上，必须有更短的墙钟（默认 15 秒）——
 *       摘要是后台任务，可以等 60 秒；这里等太久用户只会觉得「答完了却一直不结束」；</li>
 *   <li>输出是机器可校验的短 JSON，固定 temperature=0，避免同一段终答一会儿能整理、
 *       一会儿整理不出来；</li>
 *   <li>只认 {@link TextBlock} 正文：推理模型的思考不算答案（它可能把候选写在思考里）。</li>
 * </ul>
 *
 * <p>计量：仍走 {@link MeteredModelOpener}，渠道 CONSOLE，与既有出站口径一致。
 * 失败只抛业务异常（文案不含厂商细节），由调用方降级成「本轮没有可点选项」。
 */
@Component
public class AgentScopeChoiceStructurer implements ChoiceStructurer {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChoiceStructurer.class);

    /** 输出上限：一份 2~6 项的 JSON 远小于这个值；给足余量是防推理模型把额度花在思考上 */
    private static final int MAX_TOKENS = 800;

    private final MeteredModelOpener models;
    private final TrialProperties properties;

    /**
     * @param models     打开实例并记账
     * @param properties 试跑参数（整理开关与超时）
     */
    public AgentScopeChoiceStructurer(MeteredModelOpener models, TrialProperties properties) {
        this.models = models;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     * <p>超时按失败处理；不重试（整理失败只是「这一轮没有 chips」，重试只会拖长收尾）。
     */
    @Override
    public String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                           String instruction, String source) {
        Model model = models.open(MeterScope.console(agentId, modelInstanceId,
                sessionId == null ? 0L : sessionId, userId));
        List<Msg> messages = new ArrayList<>(2);
        if (instruction != null && !instruction.isBlank()) {
            messages.add(new SystemMessage(instruction));
        }
        messages.add(new UserMessage(source == null ? "" : source));
        GenerateOptions options = GenerateOptions.builder().temperature(0.0).maxTokens(MAX_TOKENS).build();
        List<ChatResponse> frames;
        try {
            frames = model.stream(messages, null, options)
                    .collectList()
                    .block(Duration.ofMillis(properties.choiceStructTimeoutMs()));
        } catch (RuntimeException ex) {
            // 出站细节只进日志：错误文案会回到页面，不能带厂商 / 地址 / 状态码
            log.warn("选项整理出站失败 instanceId={} type={} cause={}",
                    modelInstanceId, ex.getClass().getSimpleName(), CauseDigest.of(ex));
            throw new BizException(ErrorCode.BAD_REQUEST, "选项整理未能完成");
        }
        String text = textOf(frames);
        if (text.isBlank()) {
            log.info("选项整理未产出正文 instanceId={} frames={}", modelInstanceId,
                    frames == null ? 0 : frames.size());
            throw new BizException(ErrorCode.BAD_REQUEST, "选项整理未产出正文");
        }
        return text;
    }

    /**
     * 只拼接 {@link TextBlock} 正文；思考块与非文本内容一律忽略。
     *
     * @param frames 流式帧，可空
     * @return 正文；没有任何文本时为空串
     */
    private static String textOf(List<ChatResponse> frames) {
        if (frames == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse frame : frames) {
            if (frame == null || frame.getContent() == null) {
                continue;
            }
            for (ContentBlock block : frame.getContent()) {
                if (block instanceof TextBlock delta && delta.getText() != null) {
                    text.append(delta.getText());
                }
            }
        }
        return text.toString().trim();
    }
}
