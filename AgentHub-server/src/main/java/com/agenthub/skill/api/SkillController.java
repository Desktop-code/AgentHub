package com.agenthub.skill.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.skill.api.dto.SkillCardResp;
import com.agenthub.skill.api.dto.SkillCreateReq;
import com.agenthub.skill.api.dto.SkillDetailResp;
import com.agenthub.skill.api.dto.SkillPageReq;
import com.agenthub.skill.api.dto.SkillStatusReq;
import com.agenthub.skill.api.dto.SkillUpdateReq;
import com.agenthub.skill.application.SkillAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 接口。所属域：skill。校验、唯一性和审计不在这里。
 */
@RestController
@RequestMapping("/api/skill/skills")
public class SkillController {

    private final SkillAppService skills;

    /**
     * @param skills Skill 用例
     */
    public SkillController(SkillAppService skills) {
        this.skills = skills;
    }

    /**
     * @param req 分页；可选 keyword、status
     * @return 卡片页，不含正文
     */
    @SaCheckPermission(SkillPermissions.LIST)
    @GetMapping
    public Result<PageResult<SkillCardResp>> page(@Valid SkillPageReq req) {
        return Result.ok(skills.page(req));
    }

    /**
     * @param id 主键
     * @return 含正文的详情
     */
    @SaCheckPermission(SkillPermissions.LIST)
    @GetMapping("/{id}")
    public Result<SkillDetailResp> get(@PathVariable Long id) {
        return Result.ok(skills.get(id));
    }

    /**
     * @param req 新 Skill
     * @return 详情
     */
    @SaCheckPermission(SkillPermissions.ADD)
    @PostMapping
    public Result<SkillDetailResp> create(@Valid @RequestBody SkillCreateReq req) {
        return Result.ok(skills.create(req));
    }

    /**
     * @param id  主键
     * @param req 元数据和正文
     * @return 更新后的详情
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @PutMapping("/{id}")
    public Result<SkillDetailResp> update(@PathVariable Long id, @Valid @RequestBody SkillUpdateReq req) {
        return Result.ok(skills.update(id, req));
    }

    /**
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(SkillPermissions.REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        skills.remove(id);
        return Result.ok();
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的详情
     */
    @SaCheckPermission(SkillPermissions.ENABLE)
    @PutMapping("/{id}/status")
    public Result<SkillDetailResp> changeStatus(@PathVariable Long id, @Valid @RequestBody SkillStatusReq req) {
        return Result.ok(skills.changeStatus(id, req));
    }
}
