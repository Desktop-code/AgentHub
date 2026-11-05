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
     * @return 未删除的 Skill；contentMd 为当前生效已发布版本正文，activeVersionNo / hasDraft 已填充
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
     * @param skill      新 Skill（只含定义层字段，正文与简介随之进 v1 草稿）
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(SkillDef skill, Long operatorId);

    /**
     * 改定义层元数据（名称/标题/触发词/备注），不碰正文、版本与简介。
     * 简介与生效版本号由「发布 / 设为生效」通过 {@link #updateActiveMirror} 回写。
     *
     * @param skill      已存在的 Skill
     * @param operatorId 更新人
     */
    void updateProfile(SkillDef skill, Long operatorId);

    /**
     * 回写生效版镜像：skill_def.version = 生效号字符串，skill_def.description = 生效版简介。
     * 只允许发布 / 设为生效路径调用，禁止当作普通保存使用。
     *
     * @param id          主键
     * @param version     生效版本号字符串
     * @param description 生效版简介，可空
     * @param operatorId  操作人
     */
    void updateActiveMirror(Long id, String version, String description, Long operatorId);

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
