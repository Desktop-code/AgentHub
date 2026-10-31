package com.agenthub.runtime.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_run_event。所属域：runtime。没有 deleted 和更新人，只有 create_time。
 * content_text 对齐 schema-delta-runtime-event-content.sql，可空，不在这里建表。
 */
@Data
@TableName("agent_run_event")
public class RunEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer seq;
    private String eventType;
    private String summary;
    /** 展示正文。列 content_text，MEDIUMTEXT，可空 */
    private String contentText;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private LocalDateTime createTime;
}
