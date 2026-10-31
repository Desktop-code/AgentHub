package com.agenthub.prompt.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.prompt.domain.model.PromptTemplate;
import com.agenthub.prompt.domain.model.PromptTemplateQuery;

import java.util.List;
import java.util.Optional;

/**
 * L2 用途模板主表仓储。所属域：prompt。段落由 {@link PromptSegmentRepository} 负责。
 */
public interface PromptTemplateRepository {

    /**
     * 分页；不加载段落正文，避免列表打爆。
     *
     * @param query 筛选
     * @return 一页主表
     */
    PageResult<PromptTemplate> page(PromptTemplateQuery query);

    /**
     * 取某 locale 下全部模板行（不分页、不含段落）。
     * 供管理端左栏目录聚合；locale 空白表示不限。
     *
     * @param locale 语言
     * @return 主表列表，按 key、version 排序
     */
    List<PromptTemplate> listByLocale(String locale);

    /**
     * 取某用途 + 语言下**已发布**的全部版本行（不含段落）。
     * <p>
     * 供 L3 配置页的下拉选择：只列可被钉死的版本，草稿不出现，避免绑到永远不会生效的版本。
     *
     * @param purpose 用途码（已规范化大写）
     * @param locale  语言
     * @return 版本行，按 version 倒序；无则空列表
     */
    List<PromptTemplate> listPublishedByPurpose(String purpose, String locale);

    /**
     * @param id 主键
     * @return 未删除主表（不含段落）
     */
    Optional<PromptTemplate> findById(Long id);

    /**
     * @param templateKey 业务键
     * @param locale      语言
     * @return 最大版本；无则 0
     */
    int maxVersion(String templateKey, String locale);

    /**
     * @param template   新草稿主表
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(PromptTemplate template, Long operatorId);

    /**
     * 更新草稿主信息；不改 version / status / is_active。
     *
     * @param template   草稿
     * @param operatorId 更新人
     */
    void updateDraft(PromptTemplate template, Long operatorId);

    /**
     * 同 template_key+locale 下清 is_active。
     *
     * @param templateKey 业务键
     * @param locale      语言
     * @param operatorId  更新人
     */
    void clearActive(String templateKey, String locale, Long operatorId);

    /**
     * 同 purpose+locale 下清 is_active。
     * 管理端合成页的生效口径是「同一用途同一语言至多一条生效」，比 key 更严：
     * 两个不同 key 若同用途，发布其一会熄灭另一个，避免同用途出现双生效。
     *
     * @param purpose    用途码
     * @param locale     语言
     * @param operatorId 更新人
     */
    void clearActiveByPurpose(String purpose, String locale, Long operatorId);

    /**
     * @param id         主键
     * @param operatorId 更新人
     */
    void markPublishedActive(Long id, Long operatorId);
}
