package com.agenthub.runtime.domain.model;

import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.runtime.domain.service.MeterTurnHolder;

/**
 * 一次试跑模型调用的计量范围。所属域：runtime。
 * 控制台试跑渠道固定为 CONSOLE，appId 不填。
 * <p>
 * 构造时从 {@link MeterTurnHolder} 抓取本轮发号器（可能为空）：抓的是**引用**，
 * 因为真正的记账发生在模型流结束（{@code doFinally}），那时线程与 ThreadLocal 都可能已经不同。
 * 有发号器时每条流水带业务唯一键，重复执行同一轮由账本唯一索引兜底；没有时退化为普通记账。
 */
public final class MeterScope {

    private final Long agentId;
    private final Long modelInstanceId;
    private final Long sessionId;
    private final Long operatorId;
    /** 本轮发号器；空表示本轮没有客户端幂等键，不参与账本去重 */
    private final MeterTurn turn;

    private MeterScope(Long agentId, Long modelInstanceId, Long sessionId, Long operatorId, MeterTurn turn) {
        this.agentId = agentId;
        this.modelInstanceId = modelInstanceId;
        this.sessionId = sessionId;
        this.operatorId = operatorId;
        this.turn = turn;
    }

    /**
     * @param agentId         当前执行的 Agent，Router 判定时是父 Agent
     * @param modelInstanceId 这一轮用的模型实例
     * @param sessionId       试跑会话
     * @param operatorId      平台用户
     * @return 控制台计量范围
     */
    public static MeterScope console(Long agentId, Long modelInstanceId, long sessionId, long operatorId) {
        return new MeterScope(agentId, modelInstanceId, sessionId, operatorId, MeterTurnHolder.current());
    }

    public Long agentId() {
        return agentId;
    }

    public Long modelInstanceId() {
        return modelInstanceId;
    }

    public Long sessionId() {
        return sessionId;
    }

    public Long operatorId() {
        return operatorId;
    }

    /**
     * @param prompt     本轮 prompt token，没有 usage 时为 0
     * @param completion 本轮 completion token
     * @param remark     失败说明，成功时为空
     * @return 交给 BillingMeter 的命令
     */
    public MeterCommand command(int prompt, int completion, String remark) {
        MeterCommand command = new MeterCommand();
        command.setAgentId(agentId);
        command.setModelInstanceId(modelInstanceId);
        command.setSessionId(sessionId);
        command.setOperatorId(operatorId);
        command.setChannel(BillingChannel.CONSOLE);
        command.setTokenPrompt(prompt);
        command.setTokenCompletion(completion);
        command.setRemark(remark);
        // 同一轮同一序号只应有一条流水：重复执行会生成同一键，由 uk_ledger_dedupe 吃掉
        command.setDedupeKey(turn == null ? null : turn.nextDedupeKey(sessionId));
        return command;
    }
}
