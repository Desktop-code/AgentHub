package com.agenthub.memory.domain.service;

/**
 * 摘要和长期偏好抽取的出站调用。所属域：memory。
 * 实现在 runtime，走模型管理和 BillingMeter，禁止在这里写死厂商。
 */
public interface MemoryModelClient {

    /**
     * @param agentId         当前 Agent，计量用
     * @param sessionId       试跑会话，可空
     * @param userId          平台用户
     * @param modelInstanceId 已解析的实例
     * @param instruction     任务说明
     * @param source          待处理正文，不得含密钥
     * @return 模型文本
     */
    String complete(long agentId, Long sessionId, long userId, long modelInstanceId, String instruction, String source);
}
