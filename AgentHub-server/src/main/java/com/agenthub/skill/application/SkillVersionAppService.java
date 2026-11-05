package com.agenthub.skill.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.skill.api.dto.SkillVersionDetailResp;
import com.agenthub.skill.api.dto.SkillVersionDraftReq;
import com.agenthub.skill.api.dto.SkillVersionRowResp;
import com.agenthub.skill.api.dto.SkillVersionSaveReq;
import com.agenthub.skill.domain.enums.SkillPublishStatus;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillVersion;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.domain.repository.SkillVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 版本用例。所属域：skill。
 *
 * <p>状态机（写死，前端与运行时都按这套理解）：
 * <ul>
 *   <li>每个 Skill 至多一条草稿；草稿可改正文与简介，但不会被注入；</li>
 *   <li>发布：草稿 → 已发布 且置为唯一生效，旧生效版自动取消，并把生效号/简介镜像回 skill_def；</li>
 *   <li>设为生效：只能选已发布行，同样先清后置，正文回滚到该版；</li>
 *   <li>版本号在草稿创建时占 max+1，发布不改号，保证同 Skill 单调递增。</li>
 * </ul>
 * 操作日志只记 Skill 名与版本号，不写正文与简介全文。
 */
@Service
public class SkillVersionAppService {

    private final SkillDefRepository skills;
    private final SkillVersionRepository versions;
    private final SkillAssembler assembler;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param skills          Skill 定义仓储
     * @param versions        版本仓储
     * @param assembler       版本响应装配
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public SkillVersionAppService(SkillDefRepository skills, SkillVersionRepository versions,
                                  SkillAssembler assembler, OperatorContext operatorContext,
                                  OperLogWriter operLogWriter) {
        this.skills = skills;
        this.versions = versions;
        this.assembler = assembler;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 版本列表（不含正文），按版本号倒序。
     *
     * @param skillId Skill 主键
     * @return 版本行；从未建过版本时为空列表
     * @throws BizException Skill 不存在
     */
    public List<SkillVersionRowResp> list(Long skillId) {
        requireSkill(skillId);
        return versions.listBySkillId(skillId).stream().map(assembler::toVersionRow).toList();
    }

    /**
     * 单个版本详情（含正文）。
     *
     * @param skillId   Skill 主键
     * @param versionId 版本主键
     * @return 版本详情
     * @throws BizException Skill 或版本不存在，或版本不属于该 Skill
     */
    public SkillVersionDetailResp get(Long skillId, Long versionId) {
        return assembler.toVersionDetail(requireVersion(skillId, versionId));
    }

    /**
     * 新建草稿。已有草稿时拒绝，保证「至多一条草稿」。
     *
     * @param skillId Skill 主键
     * @param req     可选来源版本与初稿内容
     * @return 新草稿详情
     * @throws BizException 已有草稿、来源版本非法或正文超限
     */
    @Transactional
    public SkillVersionDetailResp createDraft(Long skillId, SkillVersionDraftReq req) {
        SkillDef skill = requireSkill(skillId);
        if (versions.findDraft(skillId).isPresent()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该 Skill 已有草稿，请先发布或继续编辑该草稿");
        }
        String description;
        String contentMd;
        if (req != null && req.getFromVersionId() != null) {
            // 基于历史版本开草稿：来源必须属于当前 Skill，且只能是已发布行（历史快照）
            SkillVersion source = requireVersion(skillId, req.getFromVersionId());
            if (!SkillPublishStatus.isPublished(source.getPublishStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "只能基于已发布版本新建草稿");
            }
            description = source.getDescription();
            contentMd = source.getContentMd();
        } else {
            description = req == null ? null : req.getDescription();
            contentMd = req == null ? null : req.getContentMd();
        }
        Long id = insertDraft(skillId, description, contentMd);
        operLogWriter.write("skill", "createDraft", "skill_version", String.valueOf(id),
                "新建 Skill 草稿 " + skill.getName());
        return get(skillId, id);
    }

    /**
     * 保存草稿的简介与正文。已发布版本只读，直接拒绝。
     *
     * @param skillId   Skill 主键
     * @param versionId 版本主键
     * @param req       简介与正文
     * @return 保存后的详情
     * @throws BizException 版本不存在 / 不属于该 Skill / 已发布 / 正文超限
     */
    @Transactional
    public SkillVersionDetailResp saveDraft(Long skillId, Long versionId, SkillVersionSaveReq req) {
        SkillVersion version = requireVersion(skillId, versionId);
        if (!SkillPublishStatus.isDraft(version.getPublishStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已发布版本只读，改正文请基于该版新建草稿");
        }
        version.setDescription(SkillChecks.optionalText(req.getDescription(), 1000, "简介"));
        version.setContentMd(SkillChecks.content(req.getContentMd()));
        // SQL 带 publish_status=0 条件；影响 0 行说明并发下已被发布，按业务错误返回
        if (!versions.updateDraft(version)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该版本已发布，正文不可再改");
        }
        SkillDef skill = requireSkill(skillId);
        operLogWriter.write("skill", "updateDraft", "skill_version", String.valueOf(versionId),
                "保存 Skill 草稿 " + skill.getName() + " v" + version.getVersionNo());
        return get(skillId, versionId);
    }

    /**
     * 发布草稿并设为唯一生效版本，同时把版本号与简介镜像回 skill_def。
     *
     * @param skillId   Skill 主键
     * @param versionId 草稿主键
     * @return 发布后的详情
     * @throws BizException 版本不是草稿，或并发下状态已变化
     */
    @Transactional
    public SkillVersionDetailResp publish(Long skillId, Long versionId) {
        SkillDef skill = requireSkill(skillId);
        SkillVersion version = requireVersion(skillId, versionId);
        if (!SkillPublishStatus.isDraft(version.getPublishStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有草稿可以发布");
        }
        Long operatorId = operatorContext.requireUserId();
        // 先清旧生效位再发布与置位：任何时刻同 Skill 最多一条 is_active=1
        versions.clearActive(skillId);
        if (!versions.markPublished(versionId, operatorId, LocalDateTime.now())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该版本已不是草稿，请刷新后重试");
        }
        versions.activate(versionId);
        skills.updateActiveMirror(skillId, String.valueOf(version.getVersionNo()),
                version.getDescription(), operatorId);
        operLogWriter.write("skill", "publish", "skill_version", String.valueOf(versionId),
                "发布 Skill " + skill.getName() + " v" + version.getVersionNo());
        return get(skillId, versionId);
    }

    /**
     * 把历史已发布版本设回生效（正文回滚）。草稿行不允许被设为生效。
     *
     * @param skillId   Skill 主键
     * @param versionId 已发布版本主键
     * @return 该版本详情
     * @throws BizException 版本不是已发布行
     */
    @Transactional
    public SkillVersionDetailResp activate(Long skillId, Long versionId) {
        SkillDef skill = requireSkill(skillId);
        SkillVersion version = requireVersion(skillId, versionId);
        if (!SkillPublishStatus.isPublished(version.getPublishStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有已发布版本可以设为生效");
        }
        Long operatorId = operatorContext.requireUserId();
        versions.clearActive(skillId);
        versions.activate(versionId);
        skills.updateActiveMirror(skillId, String.valueOf(version.getVersionNo()),
                version.getDescription(), operatorId);
        operLogWriter.write("skill", "activate", "skill_version", String.valueOf(versionId),
                "切换 Skill 生效版 " + skill.getName() + " v" + version.getVersionNo());
        return get(skillId, versionId);
    }

    /**
     * 删除草稿。已发布版本是历史快照（可能正被 Agent 绑定注入），一律拒绝。
     *
     * @param skillId   Skill 主键
     * @param versionId 草稿主键
     * @throws BizException 版本不存在 / 不属于该 Skill / 不是草稿
     */
    @Transactional
    public void deleteDraft(Long skillId, Long versionId) {
        SkillDef skill = requireSkill(skillId);
        SkillVersion version = requireVersion(skillId, versionId);
        if (!SkillPublishStatus.isDraft(version.getPublishStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有草稿可以删除，已发布版本不可删");
        }
        // 条件删除：并发下若刚好被发布，影响 0 行，按业务错误返回而不是静默成功
        if (!versions.deleteDraft(versionId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该版本已不是草稿，请刷新后重试");
        }
        operLogWriter.write("skill", "deleteDraft", "skill_version", String.valueOf(versionId),
                "删除 Skill 草稿 " + skill.getName() + " v" + version.getVersionNo());
    }

    /**
     * 新建 Skill 时落 v1 草稿。只由 {@link SkillAppService#create} 在同一个事务里调用。
     *
     * @param skillId     Skill 主键
     * @param description 简介，可空
     * @param contentMd   正文，可空
     * @return 草稿主键
     */
    Long createInitialDraft(Long skillId, String description, String contentMd) {
        return insertDraft(skillId, description, contentMd);
    }

    /**
     * 插入草稿行：取号 + 归一再落库。版本号冲突说明并发抢号，转成业务错误。
     *
     * @param skillId     Skill 主键
     * @param description 简介，可空
     * @param contentMd   正文，可空
     * @return 新草稿主键
     */
    private Long insertDraft(Long skillId, String description, String contentMd) {
        SkillVersion draft = new SkillVersion();
        draft.setSkillId(skillId);
        draft.setVersionNo(versions.nextVersionNo(skillId));
        draft.setDescription(SkillChecks.optionalText(description, 1000, "简介"));
        draft.setContentMd(SkillChecks.content(contentMd));
        try {
            return versions.insert(draft);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版本号冲突，请重试");
        }
    }

    /**
     * @param skillId Skill 主键
     * @return 未删除的 Skill
     * @throws BizException 不存在时 NOT_FOUND
     */
    private SkillDef requireSkill(Long skillId) {
        return skills.findById(skillId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Skill 不存在"));
    }

    /**
     * @param skillId   Skill 主键
     * @param versionId 版本主键
     * @return 版本
     * @throws BizException 版本不存在或不属于该 Skill
     */
    private SkillVersion requireVersion(Long skillId, Long versionId) {
        SkillVersion version = versions.findById(versionId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Skill 版本不存在"));
        if (!skillId.equals(version.getSkillId())) {
            // 防越权：不能用 A 的 skillId 读 B 的版本
            throw new BizException(ErrorCode.NOT_FOUND, "Skill 版本不存在");
        }
        return version;
    }
}
