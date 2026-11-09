package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.ToolSourceView;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.tool.domain.model.ToolItem;

import java.util.Optional;

/**
 * 运行期装配所需的「字典只读口」。所属域：runtime。
 * <p>
 * 调用方：{@code TrialPromptFactory}（每轮、每个子 Agent 都会把绑定展开成提示词与可调用工具）。
 * 实现方：
 * <ul>
 *   <li>{@code DirectAgentDictionary} —— 直读库，缓存关闭或单测用；</li>
 *   <li>{@code CachedAgentDictionary} —— 带 Redis 的装饰器（{@code @Primary}，生产走这条）。</li>
 * </ul>
 * <p>
 * 约束：
 * 1. 只暴露装配真正需要的字段，工具源用**无密投影**，禁止把 authHeaderCipher 交出去；
 * 2. 空 id 返回空，不抛异常（绑定指向脏 id 属于可跳过情形，由调用方记装配说明）；
 * 3. 实现不得吞掉 DB 异常：缓存可以降级，真值读不到时必须如实失败。
 */
public interface AgentDictionaryReader {

    /**
     * @param itemId 工具项主键，可空
     * @return 未删除的工具项；不存在时为空
     */
    Optional<ToolItem> toolItem(Long itemId);

    /**
     * @param sourceId 工具源主键，可空
     * @return 未删除工具源的无密投影；不存在时为空
     */
    Optional<ToolSourceView> toolSource(Long sourceId);

    /**
     * @param skillId Skill 主键，可空
     * @return 未删除的 Skill（contentMd 为当前生效已发布版正文）；不存在时为空
     */
    Optional<SkillDef> skill(Long skillId);
}
