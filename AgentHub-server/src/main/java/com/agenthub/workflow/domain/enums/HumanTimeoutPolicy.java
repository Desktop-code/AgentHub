package com.agenthub.workflow.domain.enums;

/**
 * HUMAN 节点超时策略。所属域：workflow 的 domain。
 * <p>必填：缺失或非法值时校验器记 error，避免人工节点永久挂起。
 */
public enum HumanTimeoutPolicy {

    /** 超时自动拒绝，流程按拒绝分支继续 */
    AUTO_REJECT,
    /** 超时自动通过，流程按通过分支继续 */
    AUTO_APPROVE,
    /** 超时升级给上级/运维处理，本刀只存配置 */
    ESCALATE;

    /**
     * 解析超时策略，大小写不敏感。
     *
     * @param raw config.timeoutPolicy，可空
     * @return 合法枚举；空值或未知值返回 null
     */
    public static HumanTimeoutPolicy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return HumanTimeoutPolicy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
