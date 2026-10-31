package com.agenthub.prompt.domain.repository;

import com.agenthub.prompt.domain.model.PromptConstraint;

import java.util.List;
import java.util.Optional;

/**
 * L1 平台约束仓储。所属域：prompt。实现不得把 Entity 交回应用层。
 */
public interface PromptConstraintRepository {

    /**
     * @param locale 语言；空白表示不限
     * @return 未删除版本，按 version 降序
     */
    List<PromptConstraint> listByLocale(String locale);

    /**
     * @param id 主键
     * @return 未删除行
     */
    Optional<PromptConstraint> findById(Long id);

    /**
     * @param locale 语言
     * @return 当前最大版本号；无行则 0
     */
    int maxVersion(String locale);

    /**
     * @param constraint 新草稿
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(PromptConstraint constraint, Long operatorId);

    /**
     * 仅允许改草稿正文；不改 version / status / is_active。
     *
     * @param constraint 已有草稿
     * @param operatorId 更新人
     */
    void updateDraftContent(PromptConstraint constraint, Long operatorId);

    /**
     * 同 locale 下将所有 is_active=1 置 0。
     *
     * @param locale     语言
     * @param operatorId 更新人
     */
    void clearActive(String locale, Long operatorId);

    /**
     * 标记已发布并生效。
     *
     * @param id         主键
     * @param operatorId 更新人
     */
    void markPublishedActive(Long id, Long operatorId);
}
