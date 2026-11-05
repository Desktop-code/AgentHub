package com.agenthub.agent.infra;

import com.agenthub.agent.domain.service.AgentPromptProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置域基础设施。所属域：agent。
 * 打开提示词参数与多智能体方案租户参数绑定，不建表、不改 agent_def 列。
 */
@Configuration
@EnableConfigurationProperties({AgentPromptProperties.class, MaProperties.class})
public class AgentInfrastructure {
}
