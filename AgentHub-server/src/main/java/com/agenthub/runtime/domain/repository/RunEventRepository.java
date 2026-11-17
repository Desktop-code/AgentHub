package com.agenthub.runtime.domain.repository;

import com.agenthub.runtime.domain.model.RunEvent;

import java.util.Collection;
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
     * 只取判定「这一轮写没写完」要用的字段：类型、序号，以及计划事件的正文。
     * 助手正文不读出来，避免左栏分页和启动扫描把整段回答拉进内存。
     *
     * @param sessionIds 会话主键，可空
     * @return 这些会话的轻量事件，按会话和序号升序
     */
    List<RunEvent> listClosureMarks(Collection<Long> sessionIds);

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
