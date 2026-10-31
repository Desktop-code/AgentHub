package com.agenthub.billing.domain;

import com.agenthub.billing.domain.model.MeterCommand;

/**
 * 供 runtime 在每一次模型调用结束时记账。所属域：billing。
 * 实现不得再去调用模型。调用方在试跑线程上同步调用。
 */
public interface BillingMeter {

    /**
     * 写一条流水。无单价时只记 token，金额留空。
     *
     * @param command 本步的用量和维度；渠道必须是已约定值
     */
    void record(MeterCommand command);
}
