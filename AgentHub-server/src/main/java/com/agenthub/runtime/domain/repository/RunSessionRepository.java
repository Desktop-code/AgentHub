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
     * 只改左栏标题列（session_title）。用户手动改名走这里，并把 title_source 置 MANUAL。
     *
     * @param id         会话主键
     * @param title      已截断的标题
     * @param titleSource {@link TitleSource} 名称，AUTO / MANUAL
     * @param operatorId 更新人
     */
    void updateTitle(Long id, String title, String titleSource, Long operatorId);

    /**
     * 写入异步生成的标题，并返回是否真的写入成功。
     *
     * <p>为什么带条件更新而不是先查后写：标题生成在异步线程里，从「查库判定为空」到
     * 「写回标题」之间用户可能已经手改，也可能并发跑了两条生成任务。用一条带
     * {@code title_source <> 'MANUAL'} 与 {@code session_title 为空} 条件的 UPDATE 收口，
     * 数据库层面就能保证「MANUAL 不被覆盖」「同一行只写一次」。
     *
     * @param id      会话主键
     * @param title   已清洗并截断的标题
     * @param operatorId 更新人；异步任务用会话所属用户，避免写成 null
     * @return true 表示成功写入；false 表示已有标题或已被手改，本次放弃
     */
    boolean updateAutoTitleIfAbsent(Long id, String title, Long operatorId);

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
