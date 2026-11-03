package com.agenthub.eval.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RuleScorer 聚合出参。所属域：eval 的 domain。
 * <p>{@code rulePassed = 所有 blocking 断言均 ok}；warning 失败不翻转本字段。
 */
@Data
public class RuleScoreResult {

    /** 规则门禁是否通过 */
    private boolean rulePassed;
    /** 逐条明细 */
    private List<AssertionResult> details = new ArrayList<>();
    /** blocking 断言总数 */
    private int blockingTotal;
    /** blocking 失败数 */
    private int blockingFailed;
    /** warning 失败数（不影响 rulePassed） */
    private int warningFailed;
}
