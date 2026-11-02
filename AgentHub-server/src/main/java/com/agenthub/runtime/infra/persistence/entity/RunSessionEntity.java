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
 * 标题列：session_title（左栏主标题）+ title_source（AUTO / MANUAL），与 last_msg_preview 分离。
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
    /** 会话标题。NULL/空=尚未生成 */
    private String sessionTitle;
    /** AUTO 自动生成 / MANUAL 用户手改 */
    private String titleSource;
    private String lastMsgPreview;
    private String errorSummary;

    @TableLogic
    private Integer deleted;

    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
