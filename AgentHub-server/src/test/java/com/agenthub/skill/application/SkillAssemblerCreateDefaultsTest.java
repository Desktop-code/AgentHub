package com.agenthub.skill.application;

import com.agenthub.skill.api.dto.SkillCreateReq;
import com.agenthub.skill.domain.model.SkillDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 新建缺省口径。所属域：skill 的单测。
 *
 * <p>真版本口径下新建不再手填版本号：定义层 version 落空串（尚未发布），
 * 简介与正文进 v1 草稿（由 SkillAppService 调版本仓储写入），不在 skill_def.content_md 上。
 */
class SkillAssemblerCreateDefaultsTest {

    @Test
    void createWithoutVersionUsesEnabledDefaults() {
        SkillCreateReq req = new SkillCreateReq();
        req.setName("pdf_extract");
        req.setTitle("PDF 抽取");
        // 前端 TagInput 已切割并去重，这里只验证服务端 join 口径
        req.setTriggerKeywords(List.of("退款", "发票"));

        SkillDef skill = new SkillAssembler().fromCreate(req);

        assertEquals("", skill.getVersion(), "未发布前版本号为空串，由发布回写生效号");
        assertEquals(1, skill.getStatus(), "未传状态时必须默认启用");
        assertEquals("退款,发票", skill.getTriggerKeywords(), "触发词仍以英文逗号串入库");
        assertNull(skill.getContentMd(), "正文不落 skill_def，由 v1 草稿承载");
    }
}
