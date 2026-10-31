package com.agenthub.model.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;
import com.agenthub.model.domain.service.ModelPurposeMatrix;
import com.agenthub.model.domain.service.PurposeRoute;
import org.springframework.stereotype.Component;

/**
 * 出站路由工厂。所属域：model。
 * 与连通测共用 {@link ModelPurposeMatrix}；未适配格抛明确业务错，禁止调用方自行降级 Chat。
 */
@Component
public class ModelOutboundFactory {

    /**
     * 要求协议×用途已适配；否则抛业务异常（文案含「用途未适配」）。
     *
     * @param protocol   供应商协议
     * @param capability 实例用途
     * @return 已适配路由
     * @throws BizException 未适配
     */
    public PurposeRoute require(ModelProtocol protocol, ModelCapability capability) {
        PurposeRoute route = ModelPurposeMatrix.resolve(protocol, capability);
        if (!route.adapted()) {
            throw new BizException(ErrorCode.BAD_REQUEST, route.message());
        }
        return route;
    }

    /**
     * 只读解析，不抛错。
     *
     * @param protocol   协议
     * @param capability 用途
     * @return 路由（可能未适配）
     */
    public PurposeRoute resolve(ModelProtocol protocol, ModelCapability capability) {
        return ModelPurposeMatrix.resolve(protocol, capability);
    }
}
