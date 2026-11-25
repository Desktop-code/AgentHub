package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.TokenDaily;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趋势序列：多维度合并成一天一点，缺数据的日子补 0，区间长度与点数一致。
 */
class TokenTrendSeriesTest {

    private final LocalDate day = LocalDate.of(2026, 10, 26);

    @Test
    void mergesDimensionsAndFillsGaps() {
        // 同一天两条不同维度的行应合并成一点；中间日无数据应补 0 点
        List<TokenTrendSeries.Point> points = TokenTrendSeries.build(
                List.of(
                        daily(day, 1L, 2L),
                        daily(day, 10L, 20L),
                        daily(day.plusDays(2), 5L, 5L)),
                day, day.plusDays(2));

        assertEquals(3, points.size());
        // 第一天两条 1+2 与 10+20 合并，说明按天累加而不是取最后一条
        assertEquals(day, points.get(0).date());
        assertEquals(11L, points.get(0).prompt());
        assertEquals(22L, points.get(0).completion());
        assertEquals(33L, points.get(0).total());
        // 中间日没有数据，补 0 点
        assertEquals(day.plusDays(1), points.get(1).date());
        assertEquals(0L, points.get(1).total());
        assertEquals(10L, points.get(2).total());
    }

    @Test
    void emptyRangeStillReturnsZeroPoints() {
        List<TokenTrendSeries.Point> points = TokenTrendSeries.build(List.of(), day, day.plusDays(2));

        assertEquals(3, points.size());
        assertTrue(points.stream().allMatch(point -> point.total() == 0L));
    }

    @Test
    void negativeAndNullTokensCountAsZero() {
        TokenDaily row = daily(day, -5L, 6L);
        row.setTokenPrompt(null);
        List<TokenTrendSeries.Point> points = TokenTrendSeries.build(List.of(row), day, day);

        assertEquals(0L, points.get(0).prompt());
        assertEquals(6L, points.get(0).completion());
    }

    @Test
    void reversedRangeReturnsEmpty() {
        assertTrue(TokenTrendSeries.build(List.of(daily(day, 1L, 1L)), day, day.minusDays(1)).isEmpty());
    }

    private TokenDaily daily(LocalDate bizDate, Long prompt, Long completion) {
        TokenDaily row = new TokenDaily();
        row.setBizDate(bizDate);
        row.setTokenPrompt(prompt);
        row.setTokenCompletion(completion);
        row.setTokenTotal((prompt == null ? 0L : prompt) + (completion == null ? 0L : completion));
        return row;
    }
}
