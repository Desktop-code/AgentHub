package com.agenthub.skill.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.skill.api.dto.SkillCardResp;
import com.agenthub.skill.api.dto.SkillCreateReq;
import com.agenthub.skill.api.dto.SkillDetailResp;
import com.agenthub.skill.api.dto.SkillPageReq;
import com.agenthub.skill.api.dto.SkillStatusReq;
import com.agenthub.skill.api.dto.SkillUpdateReq;
import com.agenthub.skill.domain.model.SkillDef;
import com.agenthub.skill.domain.model.SkillQuery;
import com.agenthub.skill.domain.repository.SkillDefRepository;
import com.agenthub.skill.domain.service.SkillNamePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 用例。所属域：skill。
 *
 * <p>真版本口径（P1）：本类只管定义层（名称/标题/触发词/备注/启停）与新建时落 v1 草稿；
 * 正文、简介与版本号的写入全部走 {@link SkillVersionAppService} 的「保存草稿 → 发布」。
 * 详情里的 contentMd 是生效已发布版正文（只读派生），不是可编辑字段。
 *
 * <p>操作日志只记名称和动作，不写 content_md，也不写 remark（内部备注不进审计）。
 * DTO ↔ 领域模型的字段装配（含 trigger_keywords 的 join/split）全部交给 {@link SkillAssembler}。
 */
@Service
public class SkillAppService {

    private final SkillDefRepository skills;
    private final SkillNamePolicy names;
    private final SkillAssembler assembler;
    private final SkillVersionAppService skillVersions;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param skills         Skill 仓储
     * @param names          未删名称唯一
     * @param assembler      DTO 装配
     * @param skillVersions  版本用例：新建时落 v1 草稿
     * @param operatorContext 操作人
     * @param operLogWriter  操作日志
     */
    public SkillAppService(SkillDefRepository skills, SkillNamePolicy names, SkillAssembler assembler,
                           SkillVersionAppService skillVersions, OperatorContext operatorContext,
                           OperLogWriter operLogWriter) {
        this.skills = skills;
        this.names = names;
        this.assembler = assembler;
        this.skillVersions = skillVersions;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页和可选筛选
     * @return 卡片页，没有正文；带生效版本号与「是否有草稿」摘要
     */
    public PageResult<SkillCardResp> page(SkillPageReq req) {
        SkillQuery query = new SkillQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        query.setStatus(SkillChecks.optionalStatus(req.getStatus()));
        PageResult<SkillDef> page = skills.page(query);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(assembler::toCard).toList());
    }

    /**
     * @param id 主键
     * @return 详情；contentMd 为当前生效已发布版本正文
     */
    public SkillDetailResp get(Long id) {
        return assembler.toDetail(require(id));
    }

    /**
     * 新建定义并落 v1 草稿。草稿不会生效，需再「发布」才对运行时可见。
     *
     * @param req 新 Skill
     * @return 详情（含刚创建的草稿摘要）
     */
    @Transactional
    public SkillDetailResp create(SkillCreateReq req) {
        SkillDef skill = assembler.fromCreate(req);
        names.assertUnique(skill.getName(), null);
        Long operatorId = operatorContext.requireUserId();
        Long id = skills.insert(skill, operatorId);
        // 简介与正文属于版本：新建即生成 v1 草稿，发布后 version_no=1 生效
        skillVersions.createInitialDraft(id, req.getDescription(), req.getContentMd());
        operLogWriter.write("skill", "create", "skill_def", String.valueOf(id), "创建 Skill " + skill.getName());
        return get(id);
    }

    /**
     * 只改定义层元数据；简介、正文与版本号不在这里改。
     *
     * @param id  主键
     * @param req 名称/标题/触发词/备注
     * @return 更新后的详情
     */
    @Transactional
    public SkillDetailResp update(Long id, SkillUpdateReq req) {
        SkillDef existing = require(id);
        // 先做名称唯一性校验，通过后再写字段，避免校验失败留下半改对象
        String name = SkillChecks.requiredText(req.getName(), 64, "名称");
        names.assertUnique(name, id);
        assembler.applyUpdate(existing, req);
        skills.updateProfile(existing, operatorContext.requireUserId());
        operLogWriter.write("skill", "update", "skill_def", String.valueOf(id), "更新 Skill " + name);
        return get(id);
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的详情
     */
    @Transactional
    public SkillDetailResp changeStatus(Long id, SkillStatusReq req) {
        SkillDef existing = require(id);
        int status = SkillChecks.exactStatus(req.getStatus());
        skills.updateStatus(id, status, operatorContext.requireUserId());
        String action = status == SkillChecks.ENABLED ? "enable" : "disable";
        operLogWriter.write("skill", action, "skill_def", String.valueOf(id), "更新 Skill 状态 " + existing.getName());
        return get(id);
    }

    /**
     * 逻辑删除定义。版本行不物理删除：定义删除后版本不可达，保留便于排查。
     *
     * @param id 主键
     */
    @Transactional
    public void remove(Long id) {
        SkillDef existing = require(id);
        skills.logicalDelete(id, operatorContext.requireUserId());
        operLogWriter.write("skill", "remove", "skill_def", String.valueOf(id), "删除 Skill " + existing.getName());
    }

    /**
     * @param id 主键
     * @return 未删除的 Skill
     * @throws BizException 不存在时 NOT_FOUND
     */
    private SkillDef require(Long id) {
        return skills.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "Skill 不存在"));
    }
}
