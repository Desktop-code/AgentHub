package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.ToolSourceView;
import com.agenthub.runtime.domain.service.AgentDictionaryReader;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 字典直读实现。所属域：runtime 的 application。
 * <p>
 * 两个用途：缓存关闭时的回退实现（{@code agenthub.agent.dict-cache.enabled=false}），
 * 以及被 {@code CachedAgentDictionary} 包裹作为「真值来源」。
 * 语义与改造前的 {@code TrialPromptFactory} 直读完全一致：只查一次库，不做任何加工。
 */
@Component
public class DirectAgentDictionary implements AgentDictionaryReader {

    private final ToolItemRepository items;
    private final ToolSourceRepository sources;
    private final SkillDefRepository skills;

    /**
     * @param items   工具项仓储
     * @param sources 工具源仓储
     * @param skills  Skill 定义仓储
     */
    public DirectAgentDictionary(ToolItemRepository items, ToolSourceRepository sources,
                                 SkillDefRepository skills) {
        this.items = items;
        this.sources = sources;
        this.skills = skills;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolItem> toolItem(Long itemId) {
        // 脏绑定（id 为空）不查库：MyBatis-Plus 按 id 传 null 会拼出无效条件
        if (itemId == null || itemId <= 0) {
            return Optional.empty();
        }
        return items.findById(itemId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ToolSourceView> toolSource(Long sourceId) {
        if (sourceId == null || sourceId <= 0) {
            return Optional.empty();
        }
        // 投影只保留装配所需字段，密文与出站地址不离开仓储层
        return sources.findById(sourceId).map(ToolSourceView::of);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SkillDef> skill(Long skillId) {
        if (skillId == null || skillId <= 0) {
            return Optional.empty();
        }
        return skills.findById(skillId);
    }
}
