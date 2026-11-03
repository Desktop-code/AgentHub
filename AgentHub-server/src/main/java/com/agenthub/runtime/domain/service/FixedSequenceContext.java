package com.agenthub.runtime.domain.service;

import java.util.List;

/**
 * 串行模板（FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE）的前置摘要拼装。所属域：runtime。
 *
 * <p>职责：把「本步问题 + 第 1..k-1 步的终答摘要」拼成第 k 步子 Agent 的输入文本。
 * 本步问题在固定串行里是用户原问全文，在路由后串行里是判路由切给该子的 sub_query。
 * <p>关键约束：
 * <ul>
 *   <li>只取前置子 Agent 的<b>最终助手正文</b>，绝不带工具原始结果、思考过程或密钥；</li>
 *   <li>单步摘要硬顶 {@link #SINGLE_STEP_LIMIT} 字符，累计前置摘要硬顶 {@link #TOTAL_PRIOR_LIMIT} 字符，
 *       超限尾截断并追加省略号，避免一步步把上下文撑爆；</li>
 *   <li>本类纯函数、无 IO、无模型调用——本阶段不额外跑摘要 LLM；</li>
 *   <li>失败步用中文占位句进摘要区，后续步知道「这步没成」，但仍然继续执行。</li>
 * </ul>
 */
public final class FixedSequenceContext {

    /** 单个前置步骤摘要的字符上限（超限尾截断） */
    public static final int SINGLE_STEP_LIMIT = 2000;

    /** 累计前置摘要的字符上限（按步骤顺序累加，超限后整体尾截断） */
    public static final int TOTAL_PRIOR_LIMIT = 6000;

    /** 摘要区标题；固定文案，便于人读与排查 */
    private static final String PRIOR_HEADER = "【前置步骤摘要】";

    /** 失败步的占位句前缀 */
    public static final String FAILED_PLACEHOLDER = "（该步未完成）";

    private FixedSequenceContext() {
    }

    /**
     * 一个已完成（或已失败）的前置步骤。
     *
     * @param label  子 Agent 展示名（含 route_key 更佳），进摘要小标题
     * @param answer 该步最终助手正文；失败步可空
     * @param failed 该步是否失败
     */
    public record PriorStep(String label, String answer, boolean failed) {
    }

    /**
     * 拼第 k 步的模型侧输入。
     *
     * @param question 本步问题（固定串行＝用户原问全文；路由后串行＝该步 sub_query）
     * @param prior    前置步骤（按执行顺序）；空表示第 1 步
     * @return 非空的输入文本：本步问题 + （可选）前置摘要区
     */
    public static String render(String question, List<PriorStep> prior) {
        String questionText = question == null ? "" : question.trim();
        if (prior == null || prior.isEmpty()) {
            return questionText;
        }
        StringBuilder block = new StringBuilder();
        int index = 0;
        for (PriorStep step : prior) {
            index++;
            // 每条摘要先单步截断，再进总账；顺序即执行顺序，供后续步理解依赖关系
            String body = stepBody(step);
            block.append("- 第 ").append(index).append(" 步");
            if (step.label() != null && !step.label().isBlank()) {
                block.append("「").append(step.label().trim()).append("」");
            }
            block.append("：").append(body).append('\n');
            if (block.length() >= TOTAL_PRIOR_LIMIT) {
                break;
            }
        }
        String priorText = clip(block.toString().trim(), TOTAL_PRIOR_LIMIT);
        StringBuilder text = new StringBuilder();
        // 标题用「本轮问题」而不是「用户原问」：固定串行是整段原问，路由后串行是判路由切给本步的子问
        text.append("本轮问题：\n").append(questionText).append("\n\n");
        text.append(PRIOR_HEADER).append("（仅终答摘要，供衔接上下文；不要复述工具调用细节）\n");
        text.append(priorText);
        return text.toString();
    }

    /**
     * @param step 前置步骤
     * @return 单步摘要正文：失败占位或截断后的终答
     */
    private static String stepBody(PriorStep step) {
        if (step.failed()) {
            return FAILED_PLACEHOLDER;
        }
        String answer = step.answer() == null ? "" : step.answer().trim();
        if (answer.isEmpty()) {
            return "（无实质回答）";
        }
        return clip(answer, SINGLE_STEP_LIMIT);
    }

    /**
     * 尾截断并打点：超限时保留前 limit 个字符再补省略号，让模型知道还有内容被略过。
     *
     * @param text  原文
     * @param limit 字符上限
     * @return 不超过 limit+1 个字符的文本
     */
    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "…";
    }
}
