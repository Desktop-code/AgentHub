package com.agenthub.agent.infra;

import com.agenthub.agent.domain.service.AgentPromptProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置域基础设施。所属域：agent。
 * 只打开提示词参数绑定，不建表、不改 agent_def 列。
 */
@Configuration
@EnableConfigurationProperties(AgentPromptProperties.class)
public class AgentInfrastructure {
}
