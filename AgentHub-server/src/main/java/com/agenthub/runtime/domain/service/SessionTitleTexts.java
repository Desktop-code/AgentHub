package com.agenthub.runtime.domain.service;

/**
 * 会话标题的清洗与截断。所属域：runtime。
 * <p>产品主路径：模型摘要经 {@link #clean} 写入 session_title（不超过 {@link #TITLE_LIMIT} 字）。
 * {@link #fromFirstMessage} 仅作文本工具保留，<b>不得</b>再当作自动标题的持久化主路径——
 * 用首问截断先落库会挡住异步摘要生成。
 * <p>约束：只做纯文本变换，不查库、不调用模型、不抛业务异常（空输入返回空串）。
 */
public final class SessionTitleTexts {

    /** 标题最大汉字数。与 agent_run_session.session_title 的 64 字节容量相比留足余量 */
    public static final int TITLE_LIMIT = 10;

    /**
     * 「首条用户消息截断」长度上限（仅工具方法；产品自动标题不走此路径）。
     */
    public static final int FIRST_MESSAGE_LIMIT = 24;

    /** 截断后追加的省略号：明确告诉用户标题被裁过 */
    public static final String ELLIPSIS = "…";

    /**
     * 中日韩标点与全角符号。{@code \p{Punct}} 只覆盖 ASCII，中文全角标点必须单独列出，
     * 否则「你好，世界。」清洗后仍带逗号句号。
     */
    private static final String CJK_PUNCT =
            "[\\u3000-\\u303F\\uFF00-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65"
                    + "\\u2018\\u2019\\u201C\\u201D\\u2014\\u2026\\u00B7\\u300A\\u300B\\u3010\\u3011]+";

    private SessionTitleTexts() {
    }

    /**
     * 把模型原始输出清洗成可入库的标题。
     *
     * <p>顺序：先脱敏（防止模型把用户原文里的密钥片段抄进标题）→ 取第一行非空文本 →
     * 去 markdown 围栏 → 去 ASCII 标点与空白 → 去全角标点 → 截断到 {@link #TITLE_LIMIT}。
     * <p>为什么先取第一行：模型常在标题后另起一行写解释，如果先拼接再清洗，
     * 解释里的汉字会挤进标题，把真正的标题截掉。
     * <p>为什么先脱敏再清洗：清洗会剥掉标点，{@code sk-xxx} 之类被打码前若先去掉 {@code -}，
     * 打码规则就匹配不上了。
     *
     * @param raw 模型原文，可空
     * @return 清洗后的标题；无有效字符时返回空串
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String firstLine = firstMeaningfulLine(TrialTexts.redact(raw));
        String cleaned = firstLine
                // 用户指定的基础规则：ASCII 标点与所有空白一并去掉
                .replaceAll("[\\p{Punct}\\s]", "")
                .replaceAll(CJK_PUNCT, "");
        return truncate(cleaned);
    }

    /**
     * 超长标题截断。按 Java char 计，中文与 emoji 之外字符均按 1 计。
     *
     * @param title 已清洗的标题，可空
     * @return 不超过 {@link #TITLE_LIMIT} 的标题
     */
    public static String truncate(String title) {
        if (title == null) {
            return "";
        }
        if (title.length() <= TITLE_LIMIT) {
            return title;
        }
        return title.substring(0, TITLE_LIMIT);
    }

    /**
     * 从首条用户消息直接截出短预览（文本工具，非自动标题主路径）。
     *
     * <p>规则：脱敏 → 空白归一 → 超长截断并追加「…」；保留标点。
     * 产品左栏主标题须走模型 {@link #clean}，不得用本方法持久化挡异步生成。
     *
     * @param raw 用户首条消息原文，可空
     * @return 截断文本；无有效字符时返回空串
     */
    public static String fromFirstMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = TrialTexts.redact(raw)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() <= FIRST_MESSAGE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, FIRST_MESSAGE_LIMIT) + ELLIPSIS;
    }

    /**
     * 取第一行有效文本，并剥掉 markdown 围栏标记。
     *
     * @param raw 模型原文
     * @return 第一行非空文本；全空时返回空串
     */
    private static String firstMeaningfulLine(String raw) {
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.replace("```", "").trim();
            // 去掉常见的「标题：」类前缀，避免前缀里的汉字占掉标题位置
            trimmed = trimmed.replaceFirst("^(标题|会话标题|主题)[:：]?", "").trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
}
