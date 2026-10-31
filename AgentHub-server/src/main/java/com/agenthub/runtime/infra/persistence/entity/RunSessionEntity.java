package com.agenthub.runtime.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * agent_run_session。所属域：runtime。
 * 时间列：start_time / end_time / create_time / update_time。
 */
@Data
@TableName("agent_run_session")
public class RunSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String runMode;
    private Long userId;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer messageCount;
    private String lastMsgPreview;
    private String errorSummary;

    @TableLogic
    private Integer deleted;

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
