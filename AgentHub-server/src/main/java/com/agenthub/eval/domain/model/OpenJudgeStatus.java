package com.agenthub.eval.domain.model;

/**
 * OpenJudge 软评分状态。所属域：eval 的 domain。落库 eval_result.openjudge_status。
 * <p>钉死四值：{@link #OK} / {@link #SKIPPED} / {@link #ERROR} / {@link #DISABLED}。
 * <b>禁止</b>把 OpenJudge 失败写成 case verdict=FAIL（FAIL 只留给规则门禁）。
 */
public enum OpenJudgeStatus {

    /** 调用成功并拿到分数 */
    OK("成功"),
    /** 未部署 / 健康检查失败 / trial 未成功未评 */
    SKIPPED("跳过"),
    /** 超时 / 5xx / 协议错误 */
    ERROR("错误"),
    /** 全局或本 case 未启用，未发 HTTP */
    DISABLED("未启用");

    private final String label;

    OpenJudgeStatus(String label) {
        this.label = label;
    }

    /**
     * @return 中文标签
     */
    public String getLabel() {
        return label;
    }
}
