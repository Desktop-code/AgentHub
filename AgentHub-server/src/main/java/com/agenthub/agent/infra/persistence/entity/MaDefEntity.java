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
    /** 方案说明：作为 MA 候选的意图说明（刀5 新增列） */
    private String description;
    /**
     * 方案自有形象 JSON（与 agent_def.avatar_config 同构）。
     * 列由 sql/temp/schema-delta-ma-avatar-config-2026-11-12.sql 增量添加，缺失时本表查询会报未知列。
     */
    private String avatarConfig;
    private String template;
    private String configJson;
    /** 历史绑根列：已停用，运行与形象都不再读它；列保留不 DROP */
    private Long rootAgentId;
    private Integer enabled;
    /** 1=平台默认接待方案。生成列 reception_slot / entry_slot 不入实体：只由库计算，本层不读不写 */
    private Integer isReceptionDefault;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
