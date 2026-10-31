package com.agenthub.prompt.domain.repository;

import com.agenthub.prompt.domain.model.PromptSegment;

import java.util.List;

/**
 * L2 段落仓储。所属域：prompt。
 */
public interface PromptSegmentRepository {

    /**
     * @param templateId 模板主键
     * @return 未删除段落，按 sort_no、id 升序
     */
    List<PromptSegment> listByTemplateId(Long templateId);

    /**
     * 全量替换某模板段落：物理删旧段再插入（uk_tpl_seg 含软删行，软删会撞唯一键）。
     * 用于草稿保存同步；已发布模板不得调用。
     *
     * @param templateId 模板主键
     * @param segments   新段落列表（可空表示清空）
     */
    void replaceAll(Long templateId, List<PromptSegment> segments);
}
