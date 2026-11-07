package com.agenthub.memory.infra.persistence.mapper;

import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.infra.persistence.entity.MemoryItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * memory_item 映射。所属域：memory。
 * 已删除分页走 XML（绕过 TableLogic），其余用 BaseMapper。
 */
@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItemEntity> {

    /**
     * 统计逻辑删除行，条件与控制台筛选一致。
     *
     * @param query 筛选（含 agentId / scope 等）
     * @return 条数
     */
    long countDeleted(@Param("q") MemoryQuery query);

    /**
     * 分页取逻辑删除行。
     *
     * @param query  筛选
     * @param offset 偏移
     * @param size   每页条数
     * @return 当前页
     */
    List<MemoryItemEntity> pageDeleted(@Param("q") MemoryQuery query,
                                       @Param("offset") long offset,
                                       @Param("size") long size);

    /**
     * 批量落向量同步状态位。显式 SQL：逻辑删除后的行也要能改状态，
     * 不能走 BaseMapper.updateById（会被 @TableLogic 的 deleted=0 条件挡掉）。
     *
     * @param ids       主键
     * @param status    PENDING / SYNCED / FAILED
     * @param failCount 失败次数（成功/终态传 0）
     * @param error     失败原因（脱敏截断），成功/终态传 null
     * @return 实际更新行数
     */
    int updateVectorSync(@Param("ids") List<Long> ids,
                         @Param("status") String status,
                         @Param("failCount") int failCount,
                         @Param("error") String error);

    /**
     * 只改状态位，不动失败次数与原因（联删遇到「未就绪」时把行拉回 PENDING）。
     *
     * @param ids    主键
     * @param status PENDING / SYNCED / FAILED
     * @return 实际更新行数
     */
    int updateVectorSyncStatus(@Param("ids") List<Long> ids, @Param("status") String status);

    /**
     * 状态位置位并把失败次数 +1（联删失败）。
     * <p>
     * 自增而不是覆盖：联删入口没有行上的历史次数，覆盖会把计数压回 1，三振永远到不了上限。
     *
     * @param ids    主键
     * @param status 目标状态（一般为 FAILED）
     * @param error  失败原因（脱敏截断）
     * @return 实际更新行数
     */
    int bumpVectorSyncFail(@Param("ids") List<Long> ids,
                           @Param("status") String status,
                           @Param("error") String error);

    /**
     * 取应删向量点的 LONG 主键：PENDING/FAILED 且未到失败上限，且已删 / 停用 / 已过期。
     * 显式 SQL 才能看到 {@code deleted = 1} 的行（TableLogic 会自动过滤）。
     *
     * @param now      当前时间
     * @param limit    本批上限
     * @param maxFail  允许重试的最大失败次数
     * @return 主键，按 id 升序
     */
    List<Long> listVectorDeleteCandidates(@Param("now") LocalDateTime now,
                                          @Param("limit") int limit,
                                          @Param("maxFail") int maxFail);

    /**
     * 取有 LONG 行的 Agent（deleted=0），补同步按 Agent 逐个推进。
     *
     * @return agent_id 升序；只含非空值
     */
    List<Long> listLongAgentIds();

    /**
     * 受控重建后把有效 LONG 全部打回 PENDING，并清零失败次数与原因。
     * 只动状态位三列，不改业务字段、不写时间列。
     *
     * @param now 当前时间（过期判定）
     * @return 实际重排行数
     */
    int requeueValidLong(@Param("now") LocalDateTime now);
}
