package com.agenthub.runtime.infra.persistence.mapper;

import com.agenthub.runtime.infra.persistence.entity.RunEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * agent_run_event 映射。所属域：runtime。
 */
@Mapper
public interface RunEventMapper extends BaseMapper<RunEventEntity> {

    /**
     * 收口判定用的轻量行。计划事件才带回正文，其它类型的正文留空。
     *
     * @param ids 会话主键，调用方保证非空
     * @return 按会话、序号升序
     */
    @Select("""
            <script>
            SELECT id, session_id AS sessionId, seq, event_type AS eventType,
                   CASE WHEN event_type = 'plan' THEN content_text ELSE NULL END AS contentText
            FROM agent_run_event
            WHERE session_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            ORDER BY session_id ASC, seq ASC, id ASC
            </script>
            """)
    List<RunEventEntity> listClosureMarks(@Param("ids") List<Long> ids);
}
