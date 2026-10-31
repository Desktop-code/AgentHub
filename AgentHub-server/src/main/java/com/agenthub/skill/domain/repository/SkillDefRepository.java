package com.agenthub.skill.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillQuery;

import java.util.Optional;

/**
 * Skill 仓储。所属域：skill。实现不得把 Entity 交回应用层。
 */
public interface SkillDefRepository {

    /**
     * 卡片分页。结果不含正文，避免把 MEDIUMTEXT 整列拉出来。
     *
     * @param query 页码、关键词、启停
     * @return 一页 Skill，contentMd 为空
     */
    PageResult<SkillDef> page(SkillQuery query);

    /**
     * @param id 主键
     * @return 未删除的 Skill，含正文
     */
    Optional<SkillDef> findById(Long id);

    /**
     * 未删除范围内名称是否已被占用。逻辑删除行不占名。
     *
     * @param name      调用名
     * @param excludeId 更新时排除自己；新建传 null
     * @return true 表示冲突
     */
    boolean existsByName(String name, Long excludeId);

    /**
     * @param skill      新 Skill，正文可空
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(SkillDef skill, Long operatorId);

    /**
     * 改元数据和正文，不改启停。
     *
     * @param skill      已存在的 Skill
     * @param operatorId 更新人
     */
    void updateProfile(SkillDef skill, Long operatorId);

    /**
     * 只改启停，不重写正文。
     *
     * @param id         主键
     * @param status     1 正常，0 停用
     * @param operatorId 更新人
     */
    void updateStatus(Long id, int status, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 操作人
     */
    void logicalDelete(Long id, Long operatorId);
}
