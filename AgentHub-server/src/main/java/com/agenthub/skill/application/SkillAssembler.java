package com.agenthub.skill.application;

import com.agenthub.skill.api.dto.SkillCardResp;
import com.agenthub.skill.api.dto.SkillCreateReq;
import com.agenthub.skill.api.dto.SkillDetailResp;
import com.agenthub.skill.api.dto.SkillUpdateReq;
import com.agenthub.skill.api.dto.SkillVersionDetailResp;
import com.agenthub.skill.api.dto.SkillVersionRowResp;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillVersion;
import com.agenthub.skill.domain.service.SkillTriggerMatcher;
import org.springframework.stereotype.Component;

/**
 * Skill 的 DTO ↔ 领域模型装配。所属域：skill 的 application 层。
 *
 * <p>关键约束：库内 {@code trigger_keywords} 是逗号字符串，前端与 DTO 用 Tag 数组，
 * 两者的 join / split 只在这里发生（底层口径见 {@link SkillTriggerMatcher}），
 * 别处禁止再自己 split(",")，否则全角逗号、空项、大小写去重会各写一套。
 * 同时集中保证：remark 只出现在配置响应里用于内部展示，不参与任何 Prompt 组装。
 *
 * <p>真版本口径：正文与 version_no 由 skill_version 派生，本类只管把它们搬进响应；
 * 定义层更新（名称/标题/触发词/备注）不再触碰简介、正文与版本号。
 */
@Component
public class SkillAssembler {

    /**
     * 领域模型 → 卡片响应。列表不回正文，避免把 MEDIUMTEXT 带给前端。
     *
     * @param skill 仓储读出的 Skill，contentMd 为空
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
        resp.setActiveVersionNo(skill.getActiveVersionNo());
        resp.setHasDraft(Boolean.TRUE.equals(skill.getHasDraft()));
        resp.setStatus(skill.getStatus());
        resp.setRemark(skill.getRemark());
        resp.setCreateTime(skill.getCreateTime());
        resp.setUpdateTime(skill.getUpdateTime());
        return resp;
    }

    /**
     * 领域模型 → 详情响应。在卡片字段上补 Markdown 正文。
     *
     * @param skill 含生效版正文的 Skill
     * @return 详情；contentMd 是当前生效已发布版本正文（只读展示用）
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
        resp.setActiveVersionNo(skill.getActiveVersionNo());
        resp.setHasDraft(Boolean.TRUE.equals(skill.getHasDraft()));
        resp.setStatus(skill.getStatus());
        resp.setRemark(skill.getRemark());
        resp.setCreateTime(skill.getCreateTime());
        resp.setUpdateTime(skill.getUpdateTime());
        return resp;
    }

    /**
     * 新建入参 → 定义层领域模型。唯一性、启停由调用方另外处理；
     * 简介与正文随后由应用服务写成 v1 草稿，不落 skill_def.content_md。
     *
     * @param req 已通过注解校验的入参
     * @return 待入库的 Skill（version 为空串，表示尚未发布）
     */
    public SkillDef fromCreate(SkillCreateReq req) {
        SkillDef skill = new SkillDef();
        skill.setName(SkillChecks.requiredText(req.getName(), 64, "名称"));
        skill.setTitle(SkillChecks.requiredText(req.getTitle(), 128, "标题"));
        skill.setDescription(SkillChecks.optionalText(req.getDescription(), 1000, "简介"));
        skill.setTriggerKeywords(SkillChecks.keywords(req.getTriggerKeywords()));
        skill.setRemark(SkillChecks.optionalText(req.getRemark(), 500, "备注"));
        // 新建一律从空版本开始：v1 草稿创建后仍未生效，发布才回写 version
        skill.setVersion("");
        skill.setStatus(SkillChecks.initialStatus(req.getStatus()));
        return skill;
    }

    /**
     * 把更新入参写进已存在的领域模型。只管定义层字段；
     * 名称由调用方先做唯一性校验再赋值。
     *
     * @param target 待更新的 Skill，直接改字段
     * @param req    已通过注解校验的入参
     */
    public void applyUpdate(SkillDef target, SkillUpdateReq req) {
        target.setName(SkillChecks.requiredText(req.getName(), 64, "名称"));
        target.setTitle(SkillChecks.requiredText(req.getTitle(), 128, "标题"));
        target.setTriggerKeywords(SkillChecks.keywords(req.getTriggerKeywords()));
        target.setRemark(SkillChecks.optionalText(req.getRemark(), 500, "备注"));
    }

    /**
     * 版本行 → 版本列表行。列表不带正文。
     *
     * @param version 版本领域模型
     * @return 列表行
     */
    public SkillVersionRowResp toVersionRow(SkillVersion version) {
        SkillVersionRowResp resp = new SkillVersionRowResp();
        fillVersion(resp, version);
        return resp;
    }

    /**
     * 版本行 → 版本详情。补 Markdown 正文。
     *
     * @param version 版本领域模型
     * @return 详情
     */
    public SkillVersionDetailResp toVersionDetail(SkillVersion version) {
        SkillVersionDetailResp resp = new SkillVersionDetailResp();
        fillVersion(resp, version);
        resp.setContentMd(version.getContentMd());
        return resp;
    }

    /**
     * 版本公共字段搬运，列表与详情共用。
     *
     * @param resp    目标响应
     * @param version 版本领域模型
     */
    private void fillVersion(SkillVersionRowResp resp, SkillVersion version) {
        resp.setId(version.getId());
        resp.setSkillId(version.getSkillId());
        resp.setVersionNo(version.getVersionNo());
        resp.setDescription(version.getDescription());
        resp.setPublishStatus(version.getPublishStatus());
        resp.setIsActive(version.getIsActive());
        resp.setPublishedAt(version.getPublishedAt());
        resp.setPublishedBy(version.getPublishedBy());
        resp.setCreateTime(version.getCreateTime());
        resp.setUpdateTime(version.getUpdateTime());
    }
}
