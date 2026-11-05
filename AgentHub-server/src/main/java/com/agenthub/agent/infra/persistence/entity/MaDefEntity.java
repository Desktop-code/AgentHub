package com.agenthub.agent.infra.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ma_def。所属域：agent 的 infra。
 *
 * <p>列名与 sql/temp/schema-delta-ma-def.sql 一致；审计列沿用现网
 * created_by / create_time / updated_by / update_time。
 * <p>方案删除采用物理删除（候选一并删除），因此没有 deleted 列；
 * 删除动作仍写 oper_log，可追溯。
 * Entity 不出本包。
 */
@Data
@TableName("ma_def")
public class MaDefEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String template;
    private String configJson;
    private Long rootAgentId;
    private Integer enabled;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
