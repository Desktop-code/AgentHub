package com.agenthub.memory.domain.repository;

import com.agenthub.memory.domain.model.LongExtractMark;

/**
 * 会话级 LONG 批抽幂等闸门。所属域：memory。
 * 调用方：LongExtractService.onSessionEnd；实现落 memory_session_extract。
 * 幂等口径是「同一次空闲」而不是「同一会话终身」：用户再次发言（或清空消息）时调用方须 clear，
 * 否则该 session 行会一直被终态挡住、再也不会批抽。
 */
public interface LongExtractSessionGate {

    /**
     * 是否已有终态标记（DONE / FAILED / SKIPPED）。
     *
     * @param sessionId 试跑会话
     * @return true=已处理，禁止再出站
     */
    boolean isProcessed(long sessionId);

    /**
     * 写入终态。已存在则覆盖（同一次流程内从 claim 到 complete）。
     *
     * @param sessionId 会话
     * @param mark      终态
     * @param reason    原因码，可空；禁止密钥
     */
    void complete(long sessionId, LongExtractMark mark, String reason);

    /**
     * 清空标记，供「用户再次发言」或「清空消息」后同一会话可再次批抽。
     * <p>
     * 语义：删除该 session_id 的整行（不写新状态、不扩枚举）。删完再空闲扫到就是「表中无行」，
     * 视为新一轮批抽；抽完照旧写 DONE / FAILED / SKIPPED。
     *
     * @param sessionId 会话
     */
    void clear(long sessionId);
}
