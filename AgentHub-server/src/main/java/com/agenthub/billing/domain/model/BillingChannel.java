package com.agenthub.billing.domain.model;

/**
 * 计量渠道。所属域：billing。
 * 一期只有控制台试跑和应用入站两类，入站要等应用授权补全后才有数据。
 */
public final class BillingChannel {

    /** 控制台试跑 */
    public static final String CONSOLE = "CONSOLE";
    /** 应用入站调用，本期只预留，不产生流水 */
    public static final String APP_API = "APP_API";

    private BillingChannel() {
    }

    /**
     * @param channel 渠道码
     * @return 是已约定的渠道时为 true
     */
    public static boolean known(String channel) {
        return CONSOLE.equals(channel) || APP_API.equals(channel);
    }
}
