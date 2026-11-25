package com.agenthub.memory.domain.service;

import com.agenthub.memory.infra.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ttl_days 解析与过期时间换算、LONG Top N 取值（离线，不连库不连模型）。
 *
 * <p>覆盖本刀的硬口径：ttl_days 缺省 / 非法 = 永久（expired_time 留空）；
 * 正整数 = now + N 天；Top N 默认 5、硬顶 20。
 */
class MemoryTtlDaysTest {

    @Test
    void missingTtlMeansPermanent() {
        List<MemoryTexts.PreferenceDraft> drafts = MemoryTexts.parsePreferences(
                "[{\"fact_key\":\"residence_city\",\"title\":\"居住地\",\"content\":\"住广州\",\"importance\":80}]");

        assertEquals(1, drafts.size());
        assertEquals(0, drafts.get(0).getTtlDays());
        assertNull(MemoryTexts.expiredTimeOf(drafts.get(0).getTtlDays(), LocalDateTime.now()));
    }

    @Test
    void positiveTtlIsParsed() {
        List<MemoryTexts.PreferenceDraft> drafts = MemoryTexts.parsePreferences(
                "[{\"fact_key\":\"has_cold\",\"content\":\"这周感冒\",\"importance\":40,\"ttl_days\":7}]");

        assertEquals(7, drafts.get(0).getTtlDays());
    }

    @Test
    void illegalTtlFallsBackToPermanent() {
        // 负数、非数字、超大值都按永久处理：宁可多留一条，也不要把条目写成已过期
        assertEquals(0, parseSingleTtl("-1"));
        assertEquals(0, parseSingleTtl("\"长期\""));
        assertEquals(0, parseSingleTtl("999999"));
    }

    @Test
    void zeroOrNegativeDaysProduceNoExpiry() {
        assertNull(MemoryTexts.expiredTimeOf(0, LocalDateTime.now()));
        assertNull(MemoryTexts.expiredTimeOf(-5, LocalDateTime.now()));
    }

    @Test
    void positiveDaysAddDaysFromNow() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime expired = MemoryTexts.expiredTimeOf(7, base);
        assertNotNull(expired);
        assertEquals(base.plusDays(7), expired);
    }

    @Test
    void topNDefaultsToFiveAndClampsToCap() {
        MemoryProperties properties = new MemoryProperties();
        assertEquals(5, properties.longRecallTopN());

        properties.setRecallLongTopN(0);
        assertEquals(5, properties.longRecallTopN(), "非法配置回退默认 5");

        properties.setRecallLongTopN(50);
        assertEquals(20, properties.longRecallTopN(), "超过硬顶按 20 处理");

        properties.setRecallLongTopN(8);
        assertEquals(8, properties.longRecallTopN());
    }

    /**
     * @param rawJsonTtl ttl_days 的原样 JSON 片段
     * @return 解析出的天数
     */
    private int parseSingleTtl(String rawJsonTtl) {
        String json = "[{\"fact_key\":\"k\",\"content\":\"c\",\"ttl_days\":" + rawJsonTtl + "}]";
        return MemoryTexts.parsePreferences(json).get(0).getTtlDays();
    }
}
