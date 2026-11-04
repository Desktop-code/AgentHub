package com.agenthub.eval.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单 case 结果行。所属域：eval 的 domain。对应表 eval_result。
 * <p>建跑时为每个启用用例预插一行**占位**：verdict 与全部评分字段为空，
 * 只有 case_key / case_id / sort_order 有值。真正的判定与产物由后续刀回填：
 * <ul>
 *   <li>刀2b：session_id / trace_id / latency_ms / artifact_summary_json；</li>
 *   <li>刀3：verdict / rule_passed / assertion_details_json / scores_json.rule；</li>
 *   <li>刀4：openjudge_* / scores_json.openjudge / prompt_breakdown 增强。</li>
 * </ul>
 * <p>唯一键 (run_id, case_key)：一次跑批里一个用例只能有一行，重复插入会撞 UK。
 * 线程：可变的领域行对象，一次请求内独占。
 */
@Data
public class EvalResult {

    /** 主键 */
    private Long id;
    /** 租户（冗余，便于隔离查询） */
    private Long tenantId;
    /** 所属跑批 */
    private Long runId;
    /** 用例键（对应 eval_case.case_key） */
    private String caseKey;
    /** 钉死的用例主键，便于追溯具体版本行 */
    private Long caseId;
    /** 执行与展示序，越小越前 */
    private Integer sortOrder;
    /** 判定；占位行为 null */
    private EvalVerdict verdict;
    /** 规则门禁是否通过：1/0/null */
    private Integer rulePassed;
    /** OpenJudge 状态：OK|SKIPPED|ERROR|DISABLED；本刀不写 */
    private String openjudgeStatus;
    /** 规则明细 + openjudge 分数（JSON 文本） */
    private String scoresJson;
    /** 终答摘要 / 工具列表 / usage（JSON 文本） */
    private String artifactSummaryJson;
    /** 现网试跑会话 id；本刀不写 */
    private String sessionId;
    /** 链路追踪 id；本刀不写 */
    private String traceId;
    /** 是否命中问答缓存：1/0/null */
    private Integer cacheHit;
    /** 提示词分段计量（JSON 文本） */
    private String promptBreakdownJson;
    /** 单 case 墙钟耗时（毫秒） */
    private Integer latencyMs;
    /** 断言明细（JSON 文本） */
    private String assertionDetailsJson;
    /** OpenJudge 原始返回（JSON 文本，可截断） */
    private String openjudgeRawJson;
    /** 该 case 的错误摘要 */
    private String errorMessage;
    /** 开始时间 */
    private LocalDateTime startedTime;
    /** 结束时间 */
    private LocalDateTime finishedTime;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 是否尚未出判定。取消收尾时正是把这类行打成 SKIPPED。
     *
     * @return true 表示 verdict 为空
     */
    public boolean isUnfinished() {
        return verdict == null;
    }
}
