package com.agenthub.eval.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测用例。所属域：eval。对应表 eval_case。
 * <p>版本钉死：(suiteId, suiteVersion, caseKey) 唯一。历史版本行永不原地改，
 * 改内容一律写新版本，保证已被 run 引用的版本可复现。
 * <p>所有 *_json 字段都是 JSON 文本；{@code assertions_json} 由 Runner 交给 {@code RuleScorer} 执行。
 */
@Data
public class EvalCase {

    /** 主键 */
    private Long id;
    /** 所属套件 */
    private Long suiteId;
    /** 钉死的套件版本 */
    private Integer suiteVersion;
    /** 版本内用例键 */
    private String caseKey;
    /** 标题 */
    private String title;
    /** 标签数组 JSON，如 ["rag","smoke"] */
    private String tagsJson;
    /** 输入 JSON（message / locale / plan_action / cache_policy） */
    private String inputJson;
    /** 期望 JSON（reference / expect_tools / forbidden_tools） */
    private String expectJson;
    /** 断言列表 JSON（RuleScorer 硬门禁输入） */
    private String assertionsJson;
    /** 打分覆盖 JSON（OpenJudge 用；本刀不接） */
    private String scoringJson;
    /** 1 启用 0 停用 */
    private Integer enabled;
    /** 展示与执行序，越小越前 */
    private Integer sortOrder;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;
}
