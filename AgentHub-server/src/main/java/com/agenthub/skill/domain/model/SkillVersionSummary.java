package com.agenthub.skill.domain.model;

/**
 * Skill 版本摘要。所属域：skill。
 *
 * <p>列表/卡片只需要「当前生效版本号 + 是否已有草稿」两项，不读正文，
 * 由一次批量查询填充，避免逐行 N+1。
 *
 * @param activeVersionNo 当前生效的已发布版本号；没有生效版时为 null
 * @param hasDraft        true 表示存在一条草稿
 */
public record SkillVersionSummary(Integer activeVersionNo, boolean hasDraft) {

    /** 既无生效版也无草稿的默认值，避免调用方到处判空。 */
    public static final SkillVersionSummary EMPTY = new SkillVersionSummary(null, false);

    /**
     * @param activeVersionNo 生效版本号，可空
     * @param hasDraft        是否有草稿
     * @return 摘要；参数全空时返回 {@link #EMPTY}
     */
    public static SkillVersionSummary of(Integer activeVersionNo, boolean hasDraft) {
        if (activeVersionNo == null && !hasDraft) {
            return EMPTY;
        }
        return new SkillVersionSummary(activeVersionNo, hasDraft);
    }
}
