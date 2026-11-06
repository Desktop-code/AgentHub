package com.agenthub.common.text;

/**
 * 文本截断的**语义明确**实现。所属域：common（纯函数，无状态、无依赖）。
 *
 * <p>为什么不用一个「万能 clip」：项目里的截断共有五种语义（纯截断、trim 后截断、折空白 + 省略号、
 * 带「已截断」标记、按行/按代理对切），把不同语义并到一个方法名下，任何一次「统一」都会静默改变
 * 前端展示与 prompt 预算。本类只收**逐字等价**的两种，名字即语义：
 * <ul>
 *   <li>{@link #raw}：原样截断，不 trim、不补省略号；null 进 null 出。</li>
 *   <li>{@link #trimmed}：先去首尾空白再截断；null 进 null 出。</li>
 * </ul>
 *
 * <p>不在本类的截断（语义不同，禁止合并）：null→空串/「未知错误」的兜底、末尾补「…」、
 * 空白折叠、代理对保护、按可用字符数反算、JSON 压缩后再截。
 */
public final class TextClip {

    /** 工具类禁止实例化。 */
    private TextClip() {
    }

    /**
     * 原样截断：不改动任何字符，只按长度切。
     *
     * <p>等价于原先散落的 {@code raw.length() <= max ? raw : raw.substring(0, max)}。
     *
     * @param text 原文；null 返回 null
     * @param max  最大字符数；&lt;=0 时返回空串
     * @return 不超过 max 个字符的原文前缀
     */
    public static String raw(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 去首尾空白后截断（不补省略号）。
     *
     * <p>等价于原先散落的 {@code String t = raw.trim(); t.length() <= max ? t : t.substring(0, max)}。
     *
     * @param text 原文；null 返回 null
     * @param max  最大字符数
     * @return trim 后不超过 max 个字符的前缀
     */
    public static String trimmed(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
