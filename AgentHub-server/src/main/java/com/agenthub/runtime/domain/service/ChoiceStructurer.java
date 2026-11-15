package com.agenthub.runtime.domain.service;

/**
 * 把终答整理成结构化选项的一次小模型出站。所属域：runtime 的 domain。
 *
 * <p>为什么要有这一层接口：整理属于「出站」，必须走模型管理与计量；
 * 业务侧（AppService）只关心「能不能收成一份合法选项」，不关心厂商协议与超时实现。
 *
 * <p>约定：
 * <ul>
 *   <li>实现方必须给这次出站一个明确超时，超时按失败返回/抛出，不无限挂起；</li>
 *   <li>失败不得影响已经完成的这一轮对话（调用方捕获后按「没有选项」处理）；</li>
 *   <li>返回的是模型原文，是否合法由调用方用 {@code WorkbenchChoiceJson.parseJson} 校验。</li>
 * </ul>
 */
public interface ChoiceStructurer {

    /**
     * 让模型把一段终答整理成选项 JSON。
     *
     * @param agentId         计量归属 Agent
     * @param sessionId       会话主键，可空
     * @param userId          平台用户
     * @param modelInstanceId 用哪个实例（取叶子专家自己的对话模型）
     * @param instruction     平台锁定整理指令（系统段）
     * @param source          待整理的终答正文，不得含密钥
     * @return 模型原文；可能不是合法 JSON，由调用方校验
     * @throws com.agenthub.common.error.BizException 出站失败、超时或无正文
     */
    String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                    String instruction, String source);
}
