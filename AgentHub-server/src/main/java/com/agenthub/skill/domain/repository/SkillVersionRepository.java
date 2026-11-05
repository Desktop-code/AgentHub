package com.agenthub.skill.domain.repository;

import com.agenthub.skill.domain.model.SkillVersion;
import com.agenthub.skill.domain.model.SkillVersionSummary;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 版本仓储。所属域：skill。实现不得把 Entity 交回应用层。
 *
 * <p>唯一生效由「先清后置」两步在事务里完成；DB 侧另有生成列唯一索引兜底，
 * 因此重复发布不会留下两条 is_active=1。
 */
public interface SkillVersionRepository {

    /**
     * 版本列表（不含正文），按版本号倒序，供版本列表 UI。
     *
     * @param skillId Skill 主键
     * @return 该 Skill 全部版本行，contentMd 为空
     */
    List<SkillVersion> listBySkillId(Long skillId);

    /**
     * @param id 版本主键
     * @return 版本详情，含正文
     */
    Optional<SkillVersion> findById(Long id);

    /**
     * 取当前生效版本（已发布且 is_active=1）。
     *
     * @param skillId Skill 主键
     * @return 生效版本，含正文；未发布过时为空
     */
    Optional<SkillVersion> findActive(Long skillId);

    /**
     * @param skillId Skill 主键
     * @return 草稿行，含正文；没有草稿时为空
     */
    Optional<SkillVersion> findDraft(Long skillId);

    /**
     * @param skillId Skill 主键
     * @return 下一个可用版本号（现有最大号 + 1；无版本时为 1）
     */
    int nextVersionNo(Long skillId);

    /**
     * 新建版本行（草稿）。version_no 由调用方先用 {@link #nextVersionNo} 取号。
     *
     * @param version 待插入版本
     * @return 新主键
     */
    Long insert(SkillVersion version);

    /**
     * 只更新草稿的简介与正文；已发布行不会被改到（SQL 带 publish_status=0 条件）。
     *
     * @param version 目标草稿
     * @return true 表示确实更新到一行草稿
     */
    boolean updateDraft(SkillVersion version);

    /**
     * 取消该 Skill 全部生效位（发布 / 设为生效前调用）。
     *
     * @param skillId Skill 主键
     */
    void clearActive(Long skillId);

    /**
     * 草稿 → 已发布：写 publish_status=1 与发布时间/人，不改正文。
     *
     * @param id          版本主键
     * @param operatorId  发布人
     * @param publishedAt 发布时间
     * @return true 表示确实把一行草稿改成已发布；false 表示该行已不是草稿
     */
    boolean markPublished(Long id, Long operatorId, LocalDateTime publishedAt);

    /**
     * 把历史已发布版本设回生效（调用方需先 {@link #clearActive}，且该行必须已发布）。
     *
     * @param id 版本主键
     */
    void activate(Long id);

    /**
     * 删除草稿行。SQL 带 publish_status=0 条件：已发布行即使被误传 id 也删不掉。
     *
     * @param id 版本主键
     * @return true 表示确实删掉了一行草稿
     */
    boolean deleteDraft(Long id);

    /**
     * 批量取版本摘要（生效号 + 是否有草稿），供卡片列表一次查完。
     *
     * @param skillIds Skill 主键集合，可空
     * @return skillId → 摘要；没有版本行的 Skill 不出现在 map 里
     */
    Map<Long, SkillVersionSummary> summariesBySkillIds(Collection<Long> skillIds);
}
