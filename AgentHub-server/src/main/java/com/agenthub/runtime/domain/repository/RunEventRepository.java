package com.agenthub.runtime.domain.repository;

import com.agenthub.runtime.domain.model.RunEvent;

import java.util.List;

/**
 * 试跑事件仓储。所属域：runtime。序号在同一会话内递增，调用方在单飞锁内写入。
 */
public interface RunEventRepository {

    /**
     * @param sessionId 会话主键
     * @return 未分页的全部事件，seq 升序
     */
    List<RunEvent> listBySessionId(Long sessionId);

    /**
     * @param sessionId 会话主键
     * @return 下一条 seq，空会话从 1 开始
     */
    int nextSeq(Long sessionId);

    /**
     * @param event 新事件，seq 已填
     * @return 新主键
     */
    Long insert(RunEvent event);

    /**
     * 清空当前会话的步骤。不删会话行。
     *
     * @param sessionId 会话主键
     */
    void deleteBySessionId(Long sessionId);
}
