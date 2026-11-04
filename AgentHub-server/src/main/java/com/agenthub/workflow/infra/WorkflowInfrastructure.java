package com.agenthub.workflow.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流域基础设施装配。所属域：workflow。
 * <p>本刀只绑定租户配置；不装配任何模型客户端、HTTP 客户端或调度器。
 */
@Configuration
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowInfrastructure {
}
