package com.agenthub.agent.infra;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 提示词防爆预算。所属域：agent。
 * 绑定个数和字符硬顶在保存时拒绝；运行时注入使用 min(配置, cap)。
 * 策略表没有这几列，不另写 ALTER，按平台 YAML 生效。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.prompt")
public class PromptBudgetProperties {

    private int maxToolsPerAgent = 16;
    private int maxSkillsPerAgent = 8;
    private int routerWarnTools = 2;
    private int routerWarnSkills = 2;
    private int toolsSchemaMaxChars = 8000;
    private int toolsSchemaMaxCharsCap = 16000;
    private int skillsInjectMaxChars = 8000;
    private int skillsInjectMaxCharsCap = 16000;
    private int systemPromptMaxChars = 12000;
    private int systemPromptMaxCharsCap = 24000;

    /**
     * @return 运行时工具 schema 预算
     */
    public int toolsSchemaBudget() {
        return Math.min(Math.max(toolsSchemaMaxChars, 1), Math.max(toolsSchemaMaxCharsCap, 1));
    }

    /**
     * @return 运行时 Skill 注入预算
     */
    public int skillsBudget() {
        return Math.min(Math.max(skillsInjectMaxChars, 1), Math.max(skillsInjectMaxCharsCap, 1));
    }

    /**
     * @return 运行时系统提示（平台前缀和业务段）预算
     */
    public int systemBudget() {
        return Math.min(Math.max(systemPromptMaxChars, 1), Math.max(systemPromptMaxCharsCap, 1));
    }
}
