package com.agenthub.runtime.domain.model;

import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.billing.domain.model.MeterCommand;

/**
 * 一次试跑模型调用的计量范围。所属域：runtime。
 * 控制台试跑渠道固定为 CONSOLE，appId 不填。
 */
public record MeterScope(Long agentId, Long modelInstanceId, Long sessionId, Long operatorId) {

    /**
     * @param agentId         当前执行的 Agent，Router 判定时是父 Agent
     * @param modelInstanceId 这一轮用的模型实例
     * @param sessionId       试跑会话
     * @param operatorId      平台用户
     * @return 控制台计量范围
     */
    public static MeterScope console(Long agentId, Long modelInstanceId, long sessionId, long operatorId) {
        return new MeterScope(agentId, modelInstanceId, sessionId, operatorId);
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
        return command;
    }
}
