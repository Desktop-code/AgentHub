package com.agenthub.memory.application;

import com.agenthub.memory.domain.service.MemoryModelClient;
import org.slf4j.Logger;

/**
 * 记忆类出站的统一 INFO 日志格式。所属域：memory 的 application。
 *
 * <p>为什么单独抽出来：L1 分类、L2 显式抽取、会话结束批抽都要打出同样的几项，
 * 分散在各处容易出现「有的带实例名、有的带地址」的不一致；排障时按 {@code 记忆出站} 一搜即可。
 *
 * <p><b>脱敏红线</b>：本类只接收 {@link MemoryModelClient.Outbound} 里已脱敏的 host+path，
 * 不接收 apiKey / Authorization；调用方禁止把 baseUrl 原文或密钥传进来。
 */
final class MemoryOutboundLogs {

    private MemoryOutboundLogs() {
    }

    /**
     * 出站发起时打一行 INFO，用于区分「没调用 / 调用了但空回 / 解析到 normal」。
     *
     * @param log          调用方 logger
     * @param trigger      触发来源：L1 / explicit / session_end / summary
     * @param agentId      Agent
     * @param sessionId    会话，可空
     * @param userId       平台用户
     * @param instanceId   模型实例 id
     * @param out          实例描述，可空（实现未提供时按占位打）
     * @param temperature  采样温度，可空
     * @param maxTokens    输出上限，可空
     * @param systemChars  任务说明字符数（代码常量 + L3）
     * @param sourceChars  正文（用户消息）字符数
     */
    static void start(Logger log, String trigger, long agentId, Long sessionId, long userId,
                      long instanceId, MemoryModelClient.Outbound out,
                      Double temperature, Integer maxTokens, int systemChars, int sourceChars) {
        MemoryModelClient.Outbound desc = out == null
                ? MemoryModelClient.Outbound.unknown(instanceId)
                : out;
        log.info("记忆出站 trigger={} agentId={} sessionId={} userId={} instanceId={} {} temperature={} maxTokens={} systemChars={} sourceChars={}",
                trigger, agentId, sessionId, userId, instanceId, desc.describeLine(),
                temperature == null ? "-" : temperature,
                maxTokens == null ? "-" : maxTokens,
                systemChars, sourceChars);
    }
}
