package com.agenthub.model.infra.adapter;

/**
 * 协议探活客户端。所属域：model。Controller 和 AppService 不直接发 HTTP。
 */
public interface ModelClient {

    /**
     * @param command 已解密的出站参数
     * @return 脱敏后的结果
     */
    ProbeResult probe(ProbeCommand command);
}
