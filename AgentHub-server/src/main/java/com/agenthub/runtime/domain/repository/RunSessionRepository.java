package com.agenthub.runtime.domain.repository;

import com.agenthub.common.api.PageResult;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.SessionQuery;

import java.util.List;
import java.util.Optional;

/**
 * 试跑会话仓储。所属域：runtime。实现不得把 Entity 交回应用层。
 */
public interface RunSessionRepository {

    /**
     * @param query 页码、用户和 Agent。userId 为空时不按用户过滤
     * @return 一页未删除会话，新的在前
     */
    PageResult<RunSession> page(SessionQuery query);

    /**
     * @param id 会话主键
     * @return 未删除会话
     */
    Optional<RunSession> findById(Long id);

    /**
     * 进行中的会话。userId、agentId 为空时不按该项过滤，给定时扫全部用。
     *
     * @param userId  平台用户，可空
     * @param agentId Agent，可空
     * @return 未删除且 status=RUNNING 的会话
     */
    List<RunSession> listRunning(Long userId, Long agentId);

    /**
     * @param session    新会话，status 由调用方给出
     * @param operatorId 创建人
     * @return 新主键
     */
    Long insert(RunSession session, Long operatorId);

    /**
     * 更新状态、计数和两条摘要，并刷新 updated_at（空闲结案时钟）。
     * ended_at 仅当 session.endTime 非空时写入（结案）；多轮 RUNNING 推进不碰该列。
     *
     * @param session    已存在的会话
     * @param operatorId 更新人
     */
    void updateOutcome(RunSession session, Long operatorId);

    /**
     * 只改会话标题。标题存在 last_msg_preview，不另加列。
     *
     * @param id         会话主键
     * @param title      已截断的标题
     * @param operatorId 更新人
     */
    void updateTitle(Long id, String title, Long operatorId);

    /**
     * 清空消息后的会话：状态回到 RUNNING，轮次和错误摘要清掉，结束时间置空。标题保留。
     *
     * @param id         会话主键
     * @param operatorId 更新人
     */
    void resetMessages(Long id, Long operatorId);

    /**
     * 逻辑删除。表上有 deleted，不物理删行。
     *
     * @param id 会话主键
     */
    void logicDelete(Long id);
}
