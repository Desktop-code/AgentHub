package com.agenthub.eval.domain.model;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.util.Locale;

/**
 * 单 case 判定结果。所属域：eval 的 domain。落库为 eval_result.verdict（VARCHAR(16)）。
 * <p>口径：Trial 成功后由 RuleScorer 写 {@link #PASS}/{@link #FAIL}；
 * 基础设施失败写 {@link #ERROR}（不因规则改写）；取消收尾写 {@link #SKIPPED}。
 * 线程：枚举无状态。
 */
public enum EvalVerdict {

    /** 规则门禁通过 */
    PASS("通过"),
    /** 规则门禁未通过 */
    FAIL("未通过"),
    /** 基础设施错误导致该 case 没跑出结论 */
    ERROR("错误"),
    /** 未执行（用户取消 / 跳过） */
    SKIPPED("跳过");

    private final String label;

    EvalVerdict(String label) {
        this.label = label;
    }

    /**
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 库内字符串 → 枚举。
     *
     * @param raw 库内值，可空（未跑完的占位行是 null）
     * @return 枚举；空值返回 null
     * @throws BizException 非空但不是合法取值（库内数据异常）
     */
    public static EvalVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvalVerdict.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.INTERNAL, "评测结果判定非法：" + raw);
        }
    }
}
