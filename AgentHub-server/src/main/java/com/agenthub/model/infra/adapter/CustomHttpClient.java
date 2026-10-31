package com.agenthub.model.infra.adapter;

import org.springframework.stereotype.Component;

/**
 * 自定义协议。所属域：model。只带 Authorization 访问 baseUrl，不猜测厂商报文。
 */
@Component
public class CustomHttpClient implements ModelClient {

    private final ModelHttpSupport http;

    /**
     * @param http 共用发送与脱敏
     */
    public CustomHttpClient(ModelHttpSupport http) {
        this.http = http;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ProbeResult probe(ProbeCommand command) {
        return http.get(command.baseUrl(), command.apiKey(), "HTTP 可达");
    }
}
