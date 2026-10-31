package com.agenthub.runtime.infra.agentscope;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.service.MemoryModelClient;
import com.agenthub.runtime.domain.model.MeterScope;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 用已配置的模型实例做摘要和偏好抽取。所属域：runtime 的 infra。
 * 每次调用都套 BillingMeter 和 LLM span，渠道仍是 CONSOLE。
 */
@Component
public class AgentScopeMemoryModelClient implements MemoryModelClient {

    private final MeteredModelOpener models;

    /**
     * @param models 打开实例并记账
     */
    public AgentScopeMemoryModelClient(MeteredModelOpener models) {
        this.models = models;
    }

    /**
     * {@inheritDoc}
     * 失败抛业务异常，调用方不得改用主聊天模型顶上。
     */
    @Override
    public String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                           String instruction, String source) {
        Model model = models.open(MeterScope.console(agentId, modelInstanceId,
                sessionId == null ? 0L : sessionId, userId));
        String prompt = instruction + "\n\n" + (source == null ? "" : source);
        List<ChatResponse> frames;
        try {
            frames = model.stream(List.of(new UserMessage(prompt)), null, null)
                    .collectList()
                    .block(Duration.ofSeconds(60));
        } catch (RuntimeException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "摘要或抽取模型调用失败");
        }
        StringBuilder text = new StringBuilder();
        if (frames != null) {
            for (ChatResponse frame : frames) {
                if (frame == null || frame.getContent() == null) {
                    continue;
                }
                for (ContentBlock block : frame.getContent()) {
                    if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                        text.append(textBlock.getText());
                    }
                }
            }
        }
        if (text.length() == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "摘要或抽取模型没有返回文本");
        }
        return text.toString();
    }
}
