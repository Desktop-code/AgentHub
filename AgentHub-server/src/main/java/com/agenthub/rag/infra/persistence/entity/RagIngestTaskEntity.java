package com.agenthub.rag.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * rag_ingest_task。所属域：rag 的 infra。
 * 时间列：start_time / finish_time / create_time / update_time。
 * deleted 显式 @TableLogic，与全局 logic-delete-field 对齐，避免插入 null 后进度 UPDATE 命中 0 行。
 */
@Data
@TableName("rag_ingest_task")
public class RagIngestTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long kbId;
    private Long docId;
    private String status;
    private String stage;
    private Integer progress;
    private Integer attempts;
    private String errorMsg;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    @TableLogic
    private Integer deleted;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
