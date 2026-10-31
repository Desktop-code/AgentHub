package com.agenthub.skill.application;

import com.agenthub.skill.api.dto.SkillCardResp;
import com.agenthub.skill.api.dto.SkillCreateReq;
import com.agenthub.skill.api.dto.SkillDetailResp;
import com.agenthub.skill.api.dto.SkillUpdateReq;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.service.SkillTriggerMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 的 DTO ↔ 领域模型装配。所属域：skill 的 application 层。
 *
 * <p>关键约束：库内 {@code trigger_keywords} 是逗号字符串，前端与 DTO 用 Tag 数组，
 * 两者的 join / split 只在这里发生（底层口径见 {@link SkillTriggerMatcher}），
 * 别处禁止再自己 split(",")，否则全角逗号、空项、大小写去重会各写一套。
 * 同时集中保证：remark 只出现在配置响应里用于内部展示，不参与任何 Prompt 组装。
 */
@Component
public class SkillAssembler {

    /**
     * 领域模型 → 卡片响应。列表不回正文，避免把 MEDIUMTEXT 带给前端。
     *
     * @param skill 仓储读出的 Skill，contentMd 可能为空
     * @return 卡片；triggerKeywords 已拆成 Tag 数组（无关键词时为空数组）
     */
    public SkillCardResp toCard(SkillDef skill) {
        SkillCardResp resp = new SkillCardResp();
        resp.setId(skill.getId());
        resp.setName(skill.getName());
        resp.setTitle(skill.getTitle());
        resp.setDescription(skill.getDescription());
        resp.setTriggerKeywords(SkillTriggerMatcher.split(skill.getTriggerKeywords()));
        resp.setVersion(skill.getVersion());
        resp.setStatus(skill.getStatus());
        resp.setRemark(skill.getRemark());
        resp.setCreateTime(skill.getCreateTime());
        resp.setUpdateTime(skill.getUpdateTime());
        return resp;
    }

    /**
     * 领域模型 → 详情响应。在卡片字段上补 Markdown 正文。
     *
     * @param skill 含正文的 Skill
     * @return 详情
     */
    public SkillDetailResp toDetail(SkillDef skill) {
        SkillDetailResp resp = new SkillDetailResp();
        resp.setId(skill.getId());
        resp.setName(skill.getName());
        resp.setTitle(skill.getTitle());
        resp.setDescription(skill.getDescription());
        resp.setTriggerKeywords(SkillTriggerMatcher.split(skill.getTriggerKeywords()));
        resp.setContentMd(skill.getContentMd());
        resp.setVersion(skill.getVersion());
        resp.setStatus(skill.getStatus());
        resp.setRemark(skill.getRemark());
        resp.setCreateTime(skill.getCreateTime());
        resp.setUpdateTime(skill.getUpdateTime());
        return resp;
    }

    /**
     * 新建入参 → 领域模型。名称、唯一性、启停由调用方另外处理。
     *
     * @param req 已通过注解校验的入参
     * @return 待入库的 Skill；triggerKeywords 已合并成库内逗号串
     */
    public SkillDef fromCreate(SkillCreateReq req) {
        SkillDef skill = new SkillDef();
        skill.setName(SkillChecks.requiredText(req.getName(), 64, "名称"));
        fillProfile(skill, req.getTitle(), req.getDescription(), req.getTriggerKeywords(),
                req.getContentMd(), req.getVersion(), req.getRemark());
        skill.setStatus(SkillChecks.initialStatus(req.getStatus()));
        return skill;
    }

    /**
     * 把更新入参写进已存在的领域模型。名称由调用方先做唯一性校验再赋值。
     *
     * @param target 待更新的 Skill，直接改字段
     * @param req    已通过注解校验的入参
     */
    public void applyUpdate(SkillDef target, SkillUpdateReq req) {
        target.setName(SkillChecks.requiredText(req.getName(), 64, "名称"));
        fillProfile(target, req.getTitle(), req.getDescription(), req.getTriggerKeywords(),
                req.getContentMd(), req.getVersion(), req.getRemark());
    }

    /**
     * 元数据和正文的统一收口。新建与更新共用，保证两条路径的校验与归一一致。
     *
     * @param skill           目标 Skill
     * @param title           标题
     * @param description     简介，会进 Prompt
     * @param triggerKeywords 关键词 Tag 数组，落库前 join 成逗号串
     * @param contentMd       Markdown 正文
     * @param version         版本字符串
     * @param remark          内部备注，永不注入模型
     */
    private void fillProfile(SkillDef skill, String title, String description, List<String> triggerKeywords,
                             String contentMd, String version, String remark) {
        skill.setTitle(SkillChecks.requiredText(title, 128, "标题"));
        skill.setDescription(SkillChecks.optionalText(description, 1000, "简介"));
        skill.setTriggerKeywords(SkillChecks.keywords(triggerKeywords));
        skill.setContentMd(SkillChecks.content(contentMd));
        skill.setVersion(SkillChecks.version(version));
        skill.setRemark(SkillChecks.optionalText(remark, 500, "备注"));
    }
}
