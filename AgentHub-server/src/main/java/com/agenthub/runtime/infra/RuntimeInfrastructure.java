package com.agenthub.runtime.infra;

import com.agenthub.runtime.infra.agentscope.RequiredArgProperties;
import com.agenthub.common.config.RuntimeLimitsProperties;
import com.agenthub.runtime.infra.agentscope.TrialProperties;
import com.agenthub.runtime.infra.cache.AgentDictCacheProperties;
import com.agenthub.runtime.model.acl.impl.ModelAdapterFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 试跑基础设施。所属域：runtime。
 * 短期多轮记忆用 AgentScope 的进程内状态，不另建 memory 表。进程重启后这段记忆消失。
 */
@Configuration
@EnableConfigurationProperties({TrialProperties.class, RuntimeLimitsProperties.class,
        RequiredArgProperties.class, VoiceProperties.class, AgentDictCacheProperties.class})
public class RuntimeInfrastructure {

    /**
     * 同一用户、同一会话、同一 Agent 的后续轮次会读到上一轮上下文。
     *
     * @return 单机内存状态
     */
    @Bean
    public InMemoryAgentStateStore trialAgentStateStore() {
        return new InMemoryAgentStateStore();
    }

    /**
     * 模型 ACL 适配器工厂。它是「某协议能否试跑」的唯一判据：
     * 试跑的模型装配只问它有没有注册，不再各自维护协议白名单。
     * 无状态、可并发复用，故注册成单例。
     *
     * @param json 序列化器，复用容器配置
     * @return 工厂
     */
    @Bean
    public ModelAdapterFactory modelAdapterFactory(ObjectMapper json) {
        return new ModelAdapterFactory(json);
    }

    /**
     * 天气工具出站用。Spring Boot 不会自动注册 RestTemplate。
     * 连接和读取都限制在 10 秒，避免试跑被外部天气接口拖住。
     *
     * @param builder Spring Boot 提供的构造器
     * @return 带超时的客户端
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }
}
