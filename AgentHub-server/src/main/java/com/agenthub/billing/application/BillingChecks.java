package com.agenthub.billing.application;

import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计费入参收口。所属域：billing。
 * 注解挡住明显空值；这里负责空白、小数位和生效期先后。
 */
public final class BillingChecks {

    /** 流水和单价的正常状态。本期流水只写这个值 */
    public static final int ENABLED = 1;
    /** 单价停用 */
    public static final int DISABLED = 0;
    /** 用量列表拼名：实例已软删或不存在时的占位文案 */
    public static final String DELETED_MODEL_NAME = "已删除模型";
    private static final int PRICE_SCALE = 6;
    private static final int PRICE_INTEGER_DIGITS = 6;

    private BillingChecks() {
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
     * @return 去掉首尾空白的文本
     */
    public static String requiredText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * 空白备注当没填。
     *
     * @param raw 原文，可空
     * @return 截断后的备注；空白为 null
     */
    public static String remark(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /**
     * 没填币种时用 CNY，和表默认值一致。
     *
     * @param raw 币种，可空
     * @return 大写前不做转换，只去空白
     */
    public static String currency(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CNY";
        }
        String text = raw.trim();
        if (text.length() > 8) {
            throw new BizException(ErrorCode.BAD_REQUEST, "币种最长 8");
        }
        return text;
    }

    /**
     * @param raw   单价
     * @param label 字段中文名
     * @return 非负且不超过 DECIMAL(12,6) 的单价
     */
    public static BigDecimal price(BigDecimal raw, String label) {
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        if (raw.signum() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为负");
        }
        if (raw.scale() > PRICE_SCALE) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最多 6 位小数");
        }
        int scale = Math.max(raw.stripTrailingZeros().scale(), 0);
        int digits = Math.max(raw.stripTrailingZeros().precision() - scale, 1);
        if (digits > PRICE_INTEGER_DIGITS) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "整数位最多 6 位");
        }
        return raw;
    }

    /**
     * @param from 生效开始
     * @param to   生效结束，可空表示一直有效
     */
    public static void window(LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效开始不能为空");
        }
        if (to != null && to.isBefore(from)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "生效结束不能早于开始");
        }
    }

    /**
     * @param status 目标状态
     * @return 0 或 1
     */
    public static int status(Integer status) {
        if (status == null || (status != ENABLED && status != DISABLED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 0 或 1");
        }
        return status;
    }

    /**
     * @param from 开始日，可空
     * @param to   结束日，可空
     */
    public static void dateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
    }

    /**
     * 空白不当成筛选条件。未知渠道直接拒绝，避免查出脏数据还以为是全部。
     *
     * @param raw 渠道，可空
     * @return 合法渠道；空白为 null
     */
    public static String channelFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (!BillingChannel.known(text)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "渠道只能是 CONSOLE 或 APP_API");
        }
        return text;
    }
}
