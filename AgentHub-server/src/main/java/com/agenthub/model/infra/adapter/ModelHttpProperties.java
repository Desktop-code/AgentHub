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
     * 语音出站（识别 / 合成）的读超时。合成整段回答比探活慢一个量级，
     * 沿用 15 秒会把「正常但稍慢」的合成误判成失败，前端就白降级到浏览器朗读。
     */
    private int speechTimeoutMs = 60000;

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

    /**
     * @return 语音出站读超时，毫秒
     */
    public int getSpeechTimeoutMs() {
        return speechTimeoutMs;
    }

    /**
     * @param speechTimeoutMs 毫秒；至少 1
     */
    public void setSpeechTimeoutMs(int speechTimeoutMs) {
        this.speechTimeoutMs = speechTimeoutMs;
    }
}
