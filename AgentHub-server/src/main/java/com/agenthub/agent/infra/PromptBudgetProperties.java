package com.agenthub.agent.infra;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 提示词防爆预算。所属域：agent（配置项宿主，供 runtime 与 prompt 读取）。
 * <p>
 * 绑定个数和字符硬顶在保存时拒绝；运行时注入使用 min(配置, cap)。
 * 策略表没有这几列，不另写 ALTER，按平台 YAML 生效。
 * <p>
 * 为什么预算在这里集中：历史上预算分散在两处——{@code assembler.max-chars}（组装门面自己切一刀）
 * 与 {@code system-prompt-max-chars}（注入层再切一刀）。两刀口径不同，谁也说不清「到底按哪个数算」。
 * 现在只留<b>一套</b>：{@code systemPromptMaxChars} 是系统提示的<b>唯一</b>总预算，
 * 由 {@code PromptBudgetTrimmer} 统一按优先级让出，不再有第二次独立截断。
 * <p>
 * 各字段含义（改动前请对照 {@code docs/提示词工程.md} 的优先级表）：
 * <ul>
 *   <li>{@code systemPromptMaxChars}：系统提示总量（保护区 + 可压区 + 记忆 + Skill + 目录）；</li>
 *   <li>{@code skillsInjectMaxChars}：按需 Skill 额度，只管「标题+场景+正文」这一段；</li>
 *   <li>{@code forcedSkillMaxChars}：<b>单条</b>强制 Skill 块的硬上限，超限截断正文并打标；</li>
 *   <li>{@code toolsSchemaMaxChars}：完整 JSON Schema 专款，走模型请求的 {@code tools} 字段。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.prompt")
public class PromptBudgetProperties {

    /** 单 Agent 最多绑定工具数（保存闸） */
    private int maxToolsPerAgent = 16;
    /** 单 Agent 最多绑定 Skill 数（保存闸） */
    private int maxSkillsPerAgent = 8;
    /** ROUTER 父节点绑定工具数超过该值时保存响应带警告 */
    private int routerWarnTools = 2;
    /** ROUTER 父节点绑定 Skill 数超过该值时保存响应带警告 */
    private int routerWarnSkills = 2;

    /** 工具 schema 专款（字符） */
    private int toolsSchemaMaxChars = 8000;
    /** 工具 schema 专款绝对上限；配置大于它时按它生效 */
    private int toolsSchemaMaxCharsCap = 16000;

    /** 按需 Skill 额度（字符）：标题 + 场景 + 正文 */
    private int skillsInjectMaxChars = 4000;
    /** 按需 Skill 额度绝对上限 */
    private int skillsInjectMaxCharsCap = 16000;

    /** 单条强制 Skill 块硬上限（字符）：段头与固定前缀不裁，只掐正文 */
    private int forcedSkillMaxChars = 1800;
    /** 单条强制 Skill 硬上限的绝对上限 */
    private int forcedSkillMaxCharsCap = 8000;

    /**
     * 按需 Skill 是否把正文也灌进系统提示。
     * <p>
     * 默认 {@code false}（只给「标题 + 场景」）：这是 P0 预算收敛的主要来源——
     * 旧实现按「剩余额度贪心」把 8 条正文全灌进 system，单 Skill 段就能吃掉五千多字，
     * 而模型判断「该不该用这条技能」只需要标题 + 场景。
     * <p>
     * 仅当团队确认「模型必须看到正文才会正确套用」时开到 true；开启后 token 会明显回升。
     */
    private boolean onDemandSkillBodyEnabled = false;

    /** 系统提示总预算（字符）：保护区不参与扣减，实际可能略超 */
    private int systemPromptMaxChars = 12000;
    /** 系统提示总预算绝对上限 */
    private int systemPromptMaxCharsCap = 24000;

    /**
     * @return 运行时工具 schema 预算
     */
    public int toolsSchemaBudget() {
        return clamp(toolsSchemaMaxChars, toolsSchemaMaxCharsCap);
    }

    /**
     * @return 运行时按需 Skill 注入预算
     */
    public int skillsBudget() {
        return clamp(skillsInjectMaxChars, skillsInjectMaxCharsCap);
    }

    /**
     * @return 单条强制 Skill 块硬上限；≤0 表示不限（排障用，生产不建议）
     */
    public int forcedSkillBudget() {
        if (forcedSkillMaxChars <= 0) {
            return 0;
        }
        return clamp(forcedSkillMaxChars, forcedSkillMaxCharsCap);
    }

    /**
     * @return 运行时系统提示总预算
     */
    public int systemBudget() {
        return clamp(systemPromptMaxChars, systemPromptMaxCharsCap);
    }

    /**
     * 按需 Skill 的正文注入形态。
     * <p>
     * 返回 runtime 的枚举会让 agent 域反向依赖 runtime，因此这里只给布尔值，
     * 由调用方（{@code TrialPromptFactory}）翻译成 {@code OnDemandBodyMode}。
     *
     * @return true 表示在额度内灌入按需 Skill 正文；false（默认）只给标题 + 场景
     */
    public boolean onDemandSkillBody() {
        return onDemandSkillBodyEnabled;
    }

    /**
     * 把配置值压到合法区间：至少 1，且不超过 cap。
     * <p>
     * cap 本身也按 ≥1 处理：把 cap 配成 0 或负数属于写错，此时只保留「至少 1」这条下界，
     * 而不是让整条链路按 0 预算跑（那会把所有可压段一次性吃光）。
     *
     * @param configured 配置值
     * @param cap        绝对上限
     * @return 生效值
     */
    private static int clamp(int configured, int cap) {
        int upper = Math.max(cap, 1);
        return Math.min(Math.max(configured, 1), upper);
    }
}
