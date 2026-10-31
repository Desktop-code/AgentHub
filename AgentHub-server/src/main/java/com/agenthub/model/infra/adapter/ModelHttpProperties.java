package com.agenthub.model.infra.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连通测试的 HTTP 超时。所属域：model。避免探活占住请求线程。
 */
@ConfigurationProperties(prefix = "agenthub.model.http")
public class ModelHttpProperties {

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 15000;

    /**
     * @return 建立连接的上限，毫秒
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * @param connectTimeoutMs 毫秒
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * @return 等待响应的上限，毫秒
     */
    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    /**
     * @param readTimeoutMs 毫秒
     */
    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
