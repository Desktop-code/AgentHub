package com.agenthub.tool.infra.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 工具域的 MCP 客户端。所属域：tool。
 * HttpClient 不注册成 Bean，避免和模型探活的客户端抢同一个类型；SSE/HTTP 共用同一连接池配置。
 */
@Configuration
@EnableConfigurationProperties(ToolMcpProperties.class)
public class ToolInfrastructure {

    /**
     * @param properties        超时
     * @param json              全站 ObjectMapper
     * @param mcpReaderExecutor SSE 读流池，见 AsyncConfig
     * @return SSE 客户端（旧 HTTP+SSE）
     */
    @Bean
    public McpSseClient mcpSseClient(ToolMcpProperties properties, ObjectMapper json,
                                     @Qualifier("mcpReaderExecutor") ThreadPoolTaskExecutor mcpReaderExecutor) {
        return new McpSseClient(sharedHttp(properties), json,
                Math.max(properties.getReadTimeoutMs(), 1), mcpReaderExecutor);
    }

    /**
     * @param properties 超时
     * @param json       全站 ObjectMapper
     * @return Streamable HTTP 客户端
     */
    @Bean
    public McpHttpClient mcpHttpClient(ToolMcpProperties properties, ObjectMapper json) {
        return new McpHttpClient(sharedHttp(properties), json, Math.max(properties.getReadTimeoutMs(), 1));
    }

    private static HttpClient sharedHttp(ToolMcpProperties properties) {
        int connect = Math.max(properties.getConnectTimeoutMs(), 1);
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connect))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
