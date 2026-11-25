package com.agenthub.memory.application;

import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0 显式记住快筛：默认短语命中、询问/否定不误伤、极短指令不按长度丢弃。
 */
class RememberIntentDetectorTest {

    private final RememberIntentDetector detector = new RememberIntentDetector(new MemoryProperties());

    @Test
    void defaultPhrasesAreHits() {
        for (String text : List.of(
                "记住我喜欢简洁",
                "请记住我住广州",
                "帮我记住我的工位在 3 楼",
                "以后默认用中文回复",
                "以后都按列表格式",
                "记一下我的邮箱",
                "帮我记着周五要交周报",
                "别忘了我的生日",
                "存一下这个偏好")) {
            assertTrue(detector.wantsRemember(text), text);
        }
    }

    @Test
    void questionsAndNegationAreExcluded() {
        assertFalse(detector.wantsRemember("记不住"));
        assertFalse(detector.wantsRemember("记住了吗"));
        assertFalse(detector.wantsRemember("你还记得吗"));
    }

    @Test
    void shortCommandIsNotDroppedByLength() {
        assertTrue(detector.wantsRemember("记住它"));
        assertTrue(detector.wantsRemember("记一下"));
    }

    @Test
    void plainStatementAndChatAreMisses() {
        assertFalse(detector.wantsRemember("我居住地是广州"));
        assertFalse(detector.wantsRemember("今天天气怎么样"));
        assertFalse(detector.wantsRemember("你好"));
        assertFalse(detector.wantsRemember(""));
        assertFalse(detector.wantsRemember(null));
    }

    @Test
    void ymlPatternsReplaceDefaults() {
        MemoryProperties properties = new MemoryProperties();
        properties.setRememberPatterns(List.of("请保存"));
        RememberIntentDetector configured = new RememberIntentDetector(properties);

        assertTrue(configured.wantsRemember("请保存我的偏好"));
        assertFalse(configured.wantsRemember("请记住我住广州"));
        // 排除规则不随 yml 替换，仍然生效
        assertFalse(configured.wantsRemember("记住了吗"));
    }
}
