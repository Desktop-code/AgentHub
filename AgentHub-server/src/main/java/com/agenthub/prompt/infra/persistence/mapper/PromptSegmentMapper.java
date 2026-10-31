package com.agenthub.prompt.infra.persistence.mapper;

import com.agenthub.prompt.infra.persistence.entity.PromptSegmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * prompt_template_segment 映射。所属域：prompt。
 * 草稿同步段落时需物理删旧行，否则 uk_tpl_seg 会与已软删行冲突。
 */
@Mapper
public interface PromptSegmentMapper extends BaseMapper<PromptSegmentEntity> {

    /**
     * 按模板物理清空段落（含已软删行），供草稿 replaceAll 使用。
     *
     * @param templateId 模板主键
     * @return 删除行数
     */
    @Delete("DELETE FROM prompt_template_segment WHERE template_id = #{templateId}")
    int physicalDeleteByTemplateId(@Param("templateId") Long templateId);
}
