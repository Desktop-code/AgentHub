package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 跨域只读引用。所属域：agent。
 * 只调用模型、工具、Skill 的领域仓储，不依赖对方 Entity，也不走对方 AppService。
 * Skill 正文读出来后立刻丢掉，绑定列表不需要 Markdown。
 */
@Component
public class AgentRefLookup {

    private final ModelInstanceRepository instances;
    private final ToolItemRepository items;
    private final ToolSourceRepository sources;
    private final SkillDefRepository skills;

    /**
     * @param instances 模型实例仓储
     * @param items     工具项仓储
     * @param sources   工具源仓储，只取名称
     * @param skills    Skill 仓储
     */
    public AgentRefLookup(ModelInstanceRepository instances, ToolItemRepository items,
                          ToolSourceRepository sources, SkillDefRepository skills) {
        this.instances = instances;
        this.items = items;
        this.sources = sources;
        this.skills = skills;
    }

    /**
     * 新绑或重新启用时，实例必须存在且已启用。
     *
     * @param id           模型实例主键
     * @param mustEnabled  true 时拒绝停用实例
     * @return 未删除的实例
     * @throws BizException 不存在或未启用
     */
    public ModelInstance requireModel(Long id, boolean mustEnabled) {
        ModelInstance instance = instances.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模型实例不存在"));
        if (mustEnabled) {
            assertEnabled(instance.getStatus(), "只能绑定已启用的模型实例");
        }
        return instance;
    }

    /**
     * 列表展示用。目标已删时返回空，不让整页失败。
     *
     * @param id 模型实例主键
     * @return 未删除的实例
     */
    public Optional<ModelInstance> findModel(Long id) {
        return id == null ? Optional.empty() : instances.findById(id);
    }

    /**
     * @param id           工具项主键
     * @param mustEnabled  true 时拒绝未进入白名单的项
     * @return 未删除的工具项
     * @throws BizException 不存在或未启用
     */
    public ToolItem requireTool(Long id, boolean mustEnabled) {
        ToolItem item = items.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工具项不存在"));
        if (mustEnabled) {
            assertEnabled(item.getStatus(), "只能绑定已启用的工具项");
        }
        return item;
    }

    /**
     * @param id 工具项主键
     * @return 未删除的工具项
     */
    public Optional<ToolItem> findTool(Long id) {
        return id == null ? Optional.empty() : items.findById(id);
    }

    /**
     * 只返回源名称。不读取、不返回鉴权头密文。
     *
     * @param sourceId 工具源主键
     * @return 名称；源已删时为 null
     */
    public String toolSourceName(Long sourceId) {
        if (sourceId == null) {
            return null;
        }
        return sources.findById(sourceId).map(source -> source.getName()).orElse(null);
    }

    /**
     * @param id           Skill 主键
     * @param mustEnabled  true 时拒绝停用 Skill
     * @return 未删除的 Skill，正文已清空
     * @throws BizException 不存在或未启用
     */
    public SkillDef requireSkill(Long id, boolean mustEnabled) {
        SkillDef skill = skills.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Skill 不存在"));
        if (mustEnabled) {
            assertEnabled(skill.getStatus(), "只能绑定已启用的 Skill");
        }
        skill.setContentMd(null);
        return skill;
    }

    /**
     * @param id Skill 主键
     * @return 未删除的 Skill，正文已清空
     */
    public Optional<SkillDef> findSkill(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return skills.findById(id).map(skill -> {
            skill.setContentMd(null);
            return skill;
        });
    }

    private void assertEnabled(Integer status, String message) {
        if (status == null || status != AgentStatus.ENABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, message);
        }
    }
}
