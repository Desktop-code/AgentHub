package com.agenthub.agent.application;

import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;

/**
 * Agent 入参收口。所属域：agent。
 * 注解挡住空值和超长；这里负责去空白、运行模式、用途和启停取值。
 * system_prompt 只存用户业务段。绝对上限按 Java char 计数，默认与 prompt-max-chars 一致。
 */
public final class AgentChecks {

    /** 用户业务段绝对上限，单位是 Java char（UTF-16 码元），不是 Unicode 码点 */
    public static final int PROMPT_MAX = 12_000;
    /** 未传优先级时与表默认值一致 */
    public static final int PRIORITY_DEFAULT = 100;
    /** 优先级和排序的上限，避免无意义的大数 */
    public static final int ORDER_MAX = 10_000;

    private AgentChecks() {
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
     * @param raw   可空文本
     * @param max   最大长度
     * @param label 字段中文名
     * @return 空白变成 null
     */
    public static String optionalText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * @param raw 请求里的运行模式
     * @return REACT 或 ROUTER
     */
    public static String runMode(String raw) {
        return AgentRunMode.parse(raw).name();
    }

    /**
     * @param raw 列表筛选，可空
     * @return 合法模式或 null
     */
    public static String optionalRunMode(String raw) {
        return AgentRunMode.optional(raw);
    }

    /**
     * 空白提示词存 null。只去掉首尾空白，不改中间换行。
     * 超长文案单独写「超过长度」，方便和安全检查的拒绝原因对齐。
     *
     * @param raw 用户业务段，可空
     * @return 可入库的文本，不含平台前缀
     */
    public static String prompt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > PROMPT_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统提示词超过长度，最长 " + PROMPT_MAX);
        }
        return text;
    }

    /**
     * 空用途表示沿用模型实例的 capability。有值时解析为规范码（旧码映射后落库）。
     *
     * @param raw 用途，可空
     * @return 规范大写枚举名或 null
     */
    public static String purpose(String raw) {
        String text = optionalText(raw, 32, "用途");
        if (text == null) {
            return null;
        }
        try {
            return ModelCapability.parse(text).name();
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "用途只能是 CHAT、ROUTER、REWRITE、SUMMARY、MEMORY、EMBEDDING、RERANK、VISION、ASR、TTS、GUARDRAIL");
        }
    }

    /**
     * @param raw 优先级，可空
     * @return 入库值，默认 100
     */
    public static int priority(Integer raw) {
        if (raw == null) {
            return PRIORITY_DEFAULT;
        }
        return bounded(raw, "优先级");
    }

    /**
     * @param raw 排序，可空
     * @return 入库值，默认 0
     */
    public static int sortOrder(Integer raw) {
        if (raw == null) {
            return 0;
        }
        return bounded(raw, "排序");
    }

    /**
     * 新建绑定未传状态时按正常。
     *
     * @param raw 请求里的状态，可空
     * @return 1 或 0
     */
    public static int initialStatus(Integer raw) {
        if (raw == null) {
            return AgentStatus.ENABLED;
        }
        return exactStatus(raw);
    }

    /**
     * 列表筛选。未传表示不按启停过滤。
     *
     * @param raw 状态，可空
     * @return 1、0 或 null
     */
    public static Integer optionalStatus(Integer raw) {
        if (raw == null) {
            return null;
        }
        return exactStatus(raw);
    }

    /**
     * @param raw 目标状态
     * @return 1 或 0
     */
    public static int exactStatus(Integer raw) {
        if (raw == null || (raw != AgentStatus.ENABLED && raw != AgentStatus.DISABLED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 1 正常或 0 停用");
        }
        return raw;
    }

    private static int bounded(Integer raw, String label) {
        if (raw < 0 || raw > ORDER_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "范围是 0 到 " + ORDER_MAX);
        }
        return raw;
    }
}
