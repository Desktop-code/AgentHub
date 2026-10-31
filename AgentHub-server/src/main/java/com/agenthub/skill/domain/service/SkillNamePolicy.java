package com.agenthub.skill.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import org.springframework.stereotype.Component;

/**
 * 未删除范围内 name 唯一。所属域：skill。
 * 表上只有普通索引，约束放在这里，不靠改表。
 */
@Component
public class SkillNamePolicy {

    private final SkillDefRepository skills;

    /**
     * @param skills Skill 仓储
     */
    public SkillNamePolicy(SkillDefRepository skills) {
        this.skills = skills;
    }

    /**
     * @param name      已经去掉首尾空白的调用名
     * @param excludeId 更新时排除当前行；新建为 null
     * @throws BizException 名称已被未删除行占用时 DUPLICATE
     */
    public void assertUnique(String name, Long excludeId) {
        if (skills.existsByName(name, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "Skill 名称已存在");
        }
    }
}
