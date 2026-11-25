package com.agenthub.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LIKE 转义契约。所属域：common。
 *
 * <p>验收口径：用户输入里的 {@code %} / {@code _} / {@code \} 必须各自变成带反斜杠的字面量，
 * 且反斜杠要最先转义（避免二次转义）；空白与 null 一律返回 null 让调用方跳过筛选。
 */
class LikeTextTest {

    /** 普通词只 trim，不加多余转义。 */
    @Test
    void plainKeywordIsTrimmedOnly() {
        assertEquals("abc", LikeText.contains("  abc  "));
    }

    /** 百分号与下划线都要按字面匹配，防止用户把模糊查询串成通配。 */
    @Test
    void wildcardsAreEscaped() {
        assertEquals("50\\%", LikeText.contains("50%"));
        assertEquals("a\\_b", LikeText.contains("a_b"));
    }

    /** 反斜杠必须最先转义：输入 `\` 出去应是 `\\`，不能与后插入的转义符混淆。 */
    @Test
    void backslashIsEscapedFirst() {
        assertEquals("\\\\", LikeText.contains("\\"));
        assertEquals("\\\\\\%", LikeText.contains("\\%"));
    }

    /** null 与纯空白视为不筛选。 */
    @Test
    void blankReturnsNull() {
        assertNull(LikeText.contains(null));
        assertNull(LikeText.contains("   "));
    }
}
