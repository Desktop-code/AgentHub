package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelProtocol;

/**
 * 一次协议×用途解析结果。所属域：model。
 * adapted=false 时 message 给人看，且不得触发 Chat URL。
 *
 * @param protocol   供应商协议
 * @param capability 模型用途
 * @param kind       出站形态
 * @param message    适配说明或「用途未适配」文案
 */
public record PurposeRoute(
        ModelProtocol protocol,
        ModelCapability capability,
        PurposeIoKind kind,
        String message) {

    /**
     * @return 是否已接专用连通测 / 出站路径
     */
    public boolean adapted() {
        return kind != PurposeIoKind.UNSUPPORTED;
    }

    /**
     * 构造未适配格：文案固定带协议×用途，便于控制台与单测断言。
     *
     * @param protocol   协议
     * @param capability 用途
     * @param detail     补充原因（可空）
     * @return 未适配路由
     */
    public static PurposeRoute unsupported(ModelProtocol protocol, ModelCapability capability, String detail) {
        String base = "用途未适配：" + protocol.name() + "×" + capability.name();
        String msg = (detail == null || detail.isBlank()) ? base : base + "，" + detail;
        return new PurposeRoute(protocol, capability, PurposeIoKind.UNSUPPORTED, msg);
    }

    /**
     * 构造已适配格。
     *
     * @param protocol   协议
     * @param capability 用途
     * @param kind       非 UNSUPPORTED
     * @param message    简短说明
     * @return 适配路由
     */
    public static PurposeRoute of(ModelProtocol protocol, ModelCapability capability,
                                  PurposeIoKind kind, String message) {
        if (kind == PurposeIoKind.UNSUPPORTED) {
            throw new IllegalArgumentException("未适配请用 unsupported()");
        }
        return new PurposeRoute(protocol, capability, kind, message);
    }
}
