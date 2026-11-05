package com.agenthub.skill.domain.model;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Skill 版本（正文快照）。所属域：skill。对应表 skill_version。
 *
 * <p>状态机口径：
 * <ul>
 *   <li>每个 Skill 至多一条草稿（publish_status=0）；已发布行可多条且正文只读；</li>
 *   <li>同 Skill 至多一条 is_active=1，且必须已发布；生效位由发布 / 设为生效维护；</li>
 *   <li>version_no 在草稿创建时即占用 max+1，发布不改号，保证同 Skill 单调递增。</li>
 * </ul>
 * 正文可能很大，禁止进入操作日志与 toString。
 */
@Data
public class SkillVersion {

    private Long id;
    private Long skillId;
    /** 版本号：同 Skill 唯一，从 1 递增 */
    private Integer versionNo;
    /** 该版本正文快照；草稿可改，已发布只读 */
    @ToString.Exclude
    private String contentMd;
    /** 该版本简介；发布 / 设生效时镜像回 skill_def.description */
    private String description;
    /** 0 草稿 / 1 已发布，见 {@link com.agenthub.skill.domain.enums.SkillPublishStatus} */
    private Integer publishStatus;
    /** 1 表示当前生效；同 Skill 至多一条 */
    private Integer isActive;
    private LocalDateTime publishedAt;
    private Long publishedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
