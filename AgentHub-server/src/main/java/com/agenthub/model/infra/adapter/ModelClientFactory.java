package com.agenthub.model.infra.adapter;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelProtocol;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 按协议选择探活客户端。所属域：model。新增协议时在这里登记，不要在应用服务里分支。
 * 各客户端内部必须再经 {@link com.agenthub.model.domain.service.ModelPurposeMatrix} 按用途分流。
 */
@Component
public class ModelClientFactory {

    private final Map<ModelProtocol, ModelClient> clients = new EnumMap<>(ModelProtocol.class);

    /**
     * @param openAi     OpenAI 兼容
     * @param dashScope  灵积
     * @param anthropic  Anthropic
     * @param custom     自定义 HTTP
     */
    public ModelClientFactory(OpenAiCompatClient openAi, DashScopeClient dashScope,
                              AnthropicClient anthropic, CustomHttpClient custom) {
        clients.put(ModelProtocol.OPENAI_COMPAT, openAi);
        clients.put(ModelProtocol.DASHSCOPE, dashScope);
        clients.put(ModelProtocol.ANTHROPIC, anthropic);
        clients.put(ModelProtocol.CUSTOM, custom);
    }

    /**
     * @param protocol 供应商协议
     * @return 对应客户端
     * @throws BizException 协议没有客户端时
     */
    public ModelClient require(ModelProtocol protocol) {
        ModelClient client = clients.get(protocol);
        if (client == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的协议");
        }
        return client;
    }
}
