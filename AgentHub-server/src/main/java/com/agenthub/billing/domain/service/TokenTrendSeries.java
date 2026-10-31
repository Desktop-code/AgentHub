package com.agenthub.billing.domain.service;

import com.agenthub.billing.domain.model.TokenDaily;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 趋势序列装配。所属域：billing。
 * 把区间内的日汇总明细（一天多行，按 Agent / 模型 / 渠道拆分）压成「一天一个点」，
 * 并补齐区间内没有数据的日期（token 记 0），保证 X 轴连续、柱子不跳动。
 */
public final class TokenTrendSeries {

    private TokenTrendSeries() {
    }

    /**
     * @param rows     区间内的日汇总行，允许为 null / 空
     * @param dateFrom 起始业务日（含）
     * @param dateTo   结束业务日（含）
     * @return 按日期升序、天数等于区间长度的点列
     */
    public static List<Point> build(List<TokenDaily> rows, LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom == null || dateTo == null || dateFrom.isAfter(dateTo)) {
            return List.of();
        }
        Map<LocalDate, long[]> buckets = accumulate(rows);
        List<Point> points = new ArrayList<>();
        for (LocalDate day = dateFrom; !day.isAfter(dateTo); day = day.plusDays(1)) {
            long[] acc = buckets.getOrDefault(day, new long[2]);
            points.add(new Point(day, acc[0], acc[1]));
        }
        return points;
    }

    /**
     * 按业务日累加输入 / 输出 token。
     * 负值当 0：存量脏数据不应把曲线拉成负向。
     *
     * @param rows 日汇总行
     * @return 日期 → [输入合计, 输出合计]
     */
    private static Map<LocalDate, long[]> accumulate(List<TokenDaily> rows) {
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        if (rows == null) {
            return buckets;
        }
        for (TokenDaily row : rows) {
            if (row == null || row.getBizDate() == null) {
                continue;
            }
            long[] acc = buckets.computeIfAbsent(row.getBizDate(), key -> new long[2]);
            acc[0] += nonNegative(row.getTokenPrompt());
            acc[1] += nonNegative(row.getTokenCompletion());
        }
        return buckets;
    }

    /**
     * @param value 原始 token，可空
     * @return 非负值；空与负数都归 0
     */
    private static long nonNegative(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    /**
     * 趋势点：一天一条。
     *
     * @param date       业务日
     * @param prompt     当天输入 token 合计
     * @param completion 当天输出 token 合计
     */
    public record Point(LocalDate date, long prompt, long completion) {

        /**
         * @return 当天输入 + 输出
         */
        public long total() {
            return prompt + completion;
        }
    }
}
