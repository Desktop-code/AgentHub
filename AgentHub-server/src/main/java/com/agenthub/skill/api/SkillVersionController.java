package com.agenthub.skill.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.skill.api.dto.SkillVersionDetailResp;
import com.agenthub.skill.api.dto.SkillVersionDraftReq;
import com.agenthub.skill.api.dto.SkillVersionRowResp;
import com.agenthub.skill.api.dto.SkillVersionSaveReq;
import com.agenthub.skill.application.SkillVersionAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Skill 版本接口。所属域：skill。校验、状态机与审计不在这里。
 *
 * <p>路径挂在该 Skill 下，避免跨 Skill 越权访问版本；权限复用 skill:list / skill:edit，
 * 不新增权限码，因此无需补菜单种子。
 */
@RestController
@RequestMapping("/api/skill/skills/{skillId}/versions")
public class SkillVersionController {

    private final SkillVersionAppService versions;

    /**
     * @param versions 版本用例
     */
    public SkillVersionController(SkillVersionAppService versions) {
        this.versions = versions;
    }

    /**
     * @param skillId Skill 主键
     * @return 版本列表（不含正文），版本号倒序
     */
    @SaCheckPermission(SkillPermissions.LIST)
    @GetMapping
    public Result<List<SkillVersionRowResp>> list(@PathVariable Long skillId) {
        return Result.ok(versions.list(skillId));
    }

    /**
     * @param skillId   Skill 主键
     * @param versionId 版本主键
     * @return 版本详情（含正文）
     */
    @SaCheckPermission(SkillPermissions.LIST)
    @GetMapping("/{versionId}")
    public Result<SkillVersionDetailResp> get(@PathVariable Long skillId, @PathVariable Long versionId) {
        return Result.ok(versions.get(skillId, versionId));
    }

    /**
     * 新建草稿：可空白开稿，也可基于某已发布版本复制正文。
     *
     * @param skillId Skill 主键
     * @param req     来源版本与初稿
     * @return 新草稿详情
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @PostMapping
    public Result<SkillVersionDetailResp> createDraft(@PathVariable Long skillId,
                                                      @Valid @RequestBody(required = false) SkillVersionDraftReq req) {
        return Result.ok(versions.createDraft(skillId, req));
    }

    /**
     * 保存草稿正文与简介；已发布版本只读。
     *
     * @param skillId   Skill 主键
     * @param versionId 草稿主键
     * @param req       简介与正文
     * @return 保存后的详情
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @PutMapping("/{versionId}")
    public Result<SkillVersionDetailResp> saveDraft(@PathVariable Long skillId, @PathVariable Long versionId,
                                                    @Valid @RequestBody SkillVersionSaveReq req) {
        return Result.ok(versions.saveDraft(skillId, versionId, req));
    }

    /**
     * 发布草稿并设为唯一生效版本。
     *
     * @param skillId   Skill 主键
     * @param versionId 草稿主键
     * @return 发布后的详情
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @PostMapping("/{versionId}/publish")
    public Result<SkillVersionDetailResp> publish(@PathVariable Long skillId, @PathVariable Long versionId) {
        return Result.ok(versions.publish(skillId, versionId));
    }

    /**
     * 把历史已发布版本设回生效。
     *
     * @param skillId   Skill 主键
     * @param versionId 已发布版本主键
     * @return 该版本详情
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @PostMapping("/{versionId}/activate")
    public Result<SkillVersionDetailResp> activate(@PathVariable Long skillId, @PathVariable Long versionId) {
        return Result.ok(versions.activate(skillId, versionId));
    }

    /**
     * 删除草稿。已发布版本是历史快照，不可删，后端状态机兜底。
     *
     * @param skillId   Skill 主键
     * @param versionId 草稿主键
     * @return 空结果
     */
    @SaCheckPermission(SkillPermissions.EDIT)
    @DeleteMapping("/{versionId}")
    public Result<Void> deleteDraft(@PathVariable Long skillId, @PathVariable Long versionId) {
        versions.deleteDraft(skillId, versionId);
        return Result.ok();
    }
}
