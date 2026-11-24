package com.agenthub.workflow.domain.service;

/**
 * AGENT 节点执行口。所属域：workflow 的 domain。
 * <p>为什么用窄接口而不是直接依赖 runtime：引擎（含单测）只关心「给 agentId + 文本，
 * 拿回助手正文」；真实现放在 infra，复用现网试跑链路，禁止第二套 ReAct 循环。
 * <p>实现方约定：只允许 REACT / PLAN_AND_EXECUTE 等叶子 Agent，ROUTER 由引擎拒绝；
 * 失败抛 {@link com.agenthub.workflow.domain.WorkflowNodeFailure}（中文、已脱敏）。
 */
public interface WorkflowAgentInvoker {

    /**
     * 同步执行一次 Agent。
     *
     * @param agentId 目标 Agent 主键
     * @param message 已由 inputMapping 收口好的用户文本
     * @param userId  平台用户（计费 / 权限口径）
     * @param runId   工作流 run，用作试跑会话侧标识，便于排障串起来
     * @return 助手正文；实现方保证非空
     */
    String invoke(Long agentId, String message, long userId, long runId);

    /**
     * 同 {@link #invoke(Long, String, long, long)}，带上当前节点编码。
     *
     * <p>为什么需要节点编码：工作流卡要把这位子 Agent 的思考 / 工具 / 运行中正文
     * 挂到对应时间轴节点里，而不是堆到整张卡下面。默认实现退回旧签名，
     * 窄测桩与其它实现不必补参数，也不会凭空多出一次出站。
     *
     * @param agentId  目标 Agent 主键
     * @param message  用户文本
     * @param userId   平台用户
     * @param runId    工作流 run
     * @param nodeCode 当前节点编码；可空
     * @return 助手正文
     */
    default String invoke(Long agentId, String message, long userId, long runId, String nodeCode) {
        return invoke(agentId, message, userId, runId);
    }

    /**
     * 带「可点选项」的一次执行（「等用户回复」的询问节点用）。
     *
     * <p>为什么用单独的默认方法而不是给 {@link #invoke} 加参数：`invoke` 是引擎与所有
     * 窄测桩共用的签名，加参数会波及全部实现；默认实现退回 {@link #invoke} 且不带选项，
     * 因此存量调用点与测试替身一行都不用改，也不会凭空多出一次出站。
     *
     * <p>实现方约定：允许该 Agent 产出结构化选项（装配平台内置选项工具），
     * 选项 JSON 已经是落库 / 展示同一份形状（见 {@code WorkbenchChoiceJson}），
     * 工作流域只做透传，不认识字段含义。
     *
     * @param agentId 目标 Agent 主键
     * @param message 用户文本
     * @param userId  平台用户
     * @param runId   工作流 run
     * @return 正文 + 选项 JSON（没有选项时为空串）
     */
    default Reply invokeForReply(Long agentId, String message, long userId, long runId) {
        return new Reply(invoke(agentId, message, userId, runId), "");
    }

    /**
     * 同 {@link #invokeForReply(Long, String, long, long)}，带上当前节点编码。
     *
     * <p>默认实现退回旧签名：不认识节点编码的实现仍按原行为执行，
     * 只是卡片拿不到这位子 Agent 的节点内过程。
     *
     * @param agentId  目标 Agent 主键
     * @param message  用户文本
     * @param userId   平台用户
     * @param runId    工作流 run
     * @param nodeCode 当前节点编码；可空
     * @return 正文 + 选项 JSON
     */
    default Reply invokeForReply(Long agentId, String message, long userId, long runId, String nodeCode) {
        return invokeForReply(agentId, message, userId, runId);
    }

    /**
     * AGENT 节点的一次产出。
     *
     * @param text      助手正文（实现方保证非空）
     * @param choiceJson 结构化选项 JSON；没有选项时为空串
     */
    record Reply(String text, String choiceJson) {
    }
}
