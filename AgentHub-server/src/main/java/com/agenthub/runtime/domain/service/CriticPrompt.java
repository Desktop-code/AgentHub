package com.agenthub.runtime.domain.service;

/**
 * 评审环（CRITIC_LOOP）的两段中文提示拼装。所属域：runtime。
 *
 * <p>只做本模板的「子问/子输入」拼装，不改平台提示词三层架构：
 * 评审专家与执行者仍是普通叶子 Agent，各自沿用自身 CHAT 提示词，
 * 这里拼的是本次调用的用户侧文本（用户原问 + 当前稿 [+ 评审意见]）。
 *
 * <p>为什么把判定契约写进评审输入而不是系统提示词：评审者可以是一个通用 Agent，
 * 契约必须随调用下发才能保证「同一份 Agent 在不同编排根下都按同一格式回答」，
 * 也便于失败时只降级本模板而不影响其它编排。
 */
public final class CriticPrompt {

    private CriticPrompt() {
    }

    /**
     * 评审专家输入：用户原问 + 当前执行稿 + 判定契约。
     *
     * @param userText 用户原问全文
     * @param draft    当前执行稿（执行者输出原文）
     * @return 送评审专家的完整用户侧文本
     */
    public static String review(String userText, String draft) {
        String question = userText == null ? "" : userText.trim();
        String current = draft == null ? "" : draft.trim();
        return "你是本次任务的评审专家。请严格评审「执行者稿件」是否满足用户原问。\n"
                + "只输出一个 JSON 对象，不要输出其它内容：\n"
                + "{\"decision\":\"ACCEPT\",\"feedback\":\"\"}\n"
                + "规则：\n"
                + "1. decision 只能是 ACCEPT 或 REVISE（大小写不敏感）；\n"
                + "2. 选 REVISE 时 feedback 必须用中文写明需要修改的点，具体、可执行；\n"
                + "3. 评审标准：是否直接回答用户原问、事实与约束是否自洽、是否有明显遗漏；\n"
                + "4. 不要重写稿件，不要输出解释段落。\n\n"
                + "【用户原问】\n" + question + "\n\n"
                + "【执行者稿件】\n" + current;
    }

    /**
     * 执行者回修输入：用户原问 + 上一稿 + 评审意见。
     *
     * @param userText 用户原问全文
     * @param draft    上一版执行稿
     * @param feedback 评审意见（已兜底为固定中文）
     * @return 送执行者的完整用户侧文本
     */
    public static String revise(String userText, String draft, String feedback) {
        String question = userText == null ? "" : userText.trim();
        String previous = draft == null ? "" : draft.trim();
        String opinion = feedback == null || feedback.isBlank()
                ? CriticVerdictParser.DEFAULT_FEEDBACK
                : feedback.trim();
        return "你是本次任务的执行者。评审专家认为上一稿需要修改，请按评审意见重写完整答复。\n"
                + "要求：直接给出修改后的最终答复，不要复述评审意见，不要输出 JSON。\n\n"
                + "【用户原问】\n" + question + "\n\n"
                + "【上一稿】\n" + previous + "\n\n"
                + "【评审意见】\n" + opinion;
    }
}
