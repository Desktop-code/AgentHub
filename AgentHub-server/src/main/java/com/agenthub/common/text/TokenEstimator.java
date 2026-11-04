package com.agenthub.common.text;

/**
 * 文本 token 估算器。所属域：common（横切工具，不含业务判断）。
 * <p>
 * 为什么需要它：平台按字符记账（{@code *-max-chars}），而厂商按 token 计费与限流。
 * 试跑面板要回答「这一轮 prompt 的钱花在哪一段」，就必须有字符到 token 的换算口径；
 * 真正精确的 usage 只有上游返回后才知道，这里给的是<b>出站前可解释的估算</b>。
 * <p>
 * 口径（保守、可复现、不依赖具体分词器）：
 * <ul>
 *   <li>中日韩字符（含全角标点）：按 <b>1 字 ≈ 1 token</b> 计（主流分词器对中文约 1～1.5 字/token，
 *       取 1:1 是偏保守的上界）；</li>
 *   <li>其它字符（英文、数字、符号、空白）：按 <b>4 字符 ≈ 1 token</b> 计，向上取整。</li>
 * </ul>
 * 结果是「不会低估」的量级参考，用于分段占比与预算判断，不用于结算金额。
 * <p>
 * 线程安全：无状态，全部静态纯函数。
 */
public final class TokenEstimator {

    /** 非中日韩字符每 token 的字符数（英文经验值） */
    private static final double NARROW_CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可空
     * @return 估算 token；空文本为 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        int narrow = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isWide(text.charAt(i))) {
                wide++;
            } else {
                narrow++;
            }
        }
        return wide + (int) Math.ceil(narrow / NARROW_CHARS_PER_TOKEN);
    }

    /**
     * 只有字符数、没有正文时使用：按全 CJK 的最坏情况估。
     * <p>
     * 为什么按最坏情况：字符数通常来自「已截断/已聚合」的统计，拿不到原文就无法区分中英文；
     * 低估会让预算看起来还有余量，实际出站却撞上厂商上限。
     *
     * @param chars 字符数，负数按 0
     * @return 估算 token（等于字符数）
     */
    public static int estimateChars(int chars) {
        return Math.max(chars, 0);
    }

    /**
     * 是否按「宽字符」（1 字 1 token）计。
     *
     * @param c 单个字符
     * @return 中日韩文字、全角标点、假名、谚文为 true
     */
    private static boolean isWide(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // CJK 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK 兼容表意
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点（。、「」等）
                || (c >= 0xFF00 && c <= 0xFFEF)  // 全角字符
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF);  // 谚文
    }
}
